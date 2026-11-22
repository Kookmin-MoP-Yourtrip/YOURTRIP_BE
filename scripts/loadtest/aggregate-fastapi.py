#!/usr/bin/env python3
"""빠른 API 계단 부하(fast-api-ramp.js) 회차를 계단별로 집계한다.

설계: docs/tasks/ai-bulkhead-loadtest/async-variant.md 3-7절

입력:
  --k6-log      k6 stderr. FASTRUN 줄(계단 구성과 시작 시각)
  --k6-summary  --summary-export. 계단별 커스텀 지표
  --poll        1초 지표 스냅샷(metrics.prom)
  --journal     앱 로그. 피닝 추적과 Redis 폴백 경고를 센다
  --k6-vmstat   k6 EC2 의 vmstat(선택). k6 가 먼저 밀렸는지 본다
  --host        sample-host.sh 의 host.tsv(선택). 계단별 프로세스 RSS·RssAnon·MemAvailable(3-10절)
  --nmt-dir     NMT 요약 스냅샷 디렉터리(선택). 계단의 마지막 스냅샷으로 영역별 커밋량(3-10절)
  --kernel-oom  커널 로그의 OOM killer 기록(선택). 줄 수를 센다(3-10절 K23)

한계점 =오류율 1% 미만, p99 1초 미만, 달성률 97% 이상을 처음부터 연속으로 만족한 마지막 계단의 도착률.
"""
import argparse
import json
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import labels_of, parse_snapshots  # noqa: E402

ERROR_RATE_LIMIT = 0.01
P99_LIMIT_MS = 1000
ACHIEVED_LIMIT = 0.97
K6_SENT_SHORT = 0.99
K6_CPU_BUSY = 90


def read_run(path):
    # 마지막 FASTRUN 을 쓴다. --console-output 은 덧붙이기라, 같은 라벨로 다시 재면 이전 시도의 줄이 앞에 남는다(s-T1).
    found = None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            i = line.find('FASTRUN ')
            if i >= 0:
                # k6 console.error 는 msg="..." 로 감싸 따옴표를 이스케이프한다
                raw = line[i + 8:].strip().replace('\\"', '"')
                found = json.loads(raw[:raw.rindex('}') + 1])
    if found is None:
        raise SystemExit('FASTRUN 줄이 없다')
    return found


def k6_steps(summary, run):
    metrics = summary['metrics']

    def count(name):
        return metrics.get(name, {}).get('count', 0)

    out = []
    for i in range(len(run['steps']) + 1):
        recovery = i == len(run['steps'])
        rps = run['recoveryRps'] if recovery else run['steps'][i]
        hold = run['recoverySec'] if recovery else run['holdSec']
        ms = metrics.get(f'fast_ms_s{i}', {})
        sent = count(f'fast_sent_s{i}')
        ok = count(f'fast_2xx_s{i}')
        timeout = count(f'fast_timeout_s{i}')
        err5xx = count(f'fast_5xx_s{i}')
        other = count(f'fast_other_s{i}')
        expected = rps * hold
        per_api = {}
        for api in run['shares']:
            v = metrics.get(f'fast_ms_s{i}_{api}', {})
            per_api[api] = {'p50': v.get('med'), 'p99': v.get('p(99)')}
        out.append({
            'step': 'recovery' if recovery else i, 'rps': rps, 'expected': expected, 'sent': sent, 'ok': ok,
            'timeout': timeout, 'err5xx': err5xx, 'other': other,
            'errorRate': round((timeout + err5xx + other) / sent, 5) if sent else None,
            'sentRatio': round(sent / expected, 4) if expected else None,
            'achievedRps': round(ok / hold, 1), 'achievedRatio': round(ok / expected, 4) if expected else None,
            'p50Ms': ms.get('med'), 'p95Ms': ms.get('p(95)'), 'p99Ms': ms.get('p(99)'), 'maxMs': ms.get('max'),
            'perApi': per_api,
        })
    return out


def server_steps(poll_path, run, step_rows):
    snaps = [(ts, s) for ts, s in parse_snapshots(poll_path, skip_suffixes=('_bucket',)) if s]
    t0 = run['setupEpochMs'] / 1000
    hold = run['holdSec']

    def val(snap, name, pred=lambda l: True):
        vals = [v for (n, l), v in snap.items() if n == name and pred(labels_of(l))]
        return sum(vals) if vals else None

    def each_max(rows, name):
        return max((v for _, s in rows for (n, _l), v in s.items() if n == name), default=None)

    def series(rows, name, pred=lambda l: True):
        return [v for v in (val(s, name, pred) for _, s in rows) if v is not None]

    def peak(rows, name, pred=lambda l: True):
        return max(series(rows, name, pred), default=None)

    def avg(rows, name):
        v = series(rows, name)
        return sum(v) / len(v) if v else None

    def delta(rows, name, pred=lambda l: True):
        v = series(rows, name, pred)
        return v[-1] - v[0] if len(v) >= 2 else None

    def mb(v):
        return round(v / 1048576) if v is not None else None

    def old(l):
        return l.get('area') == 'heap' and 'Old' in l.get('id', '')

    def heap(l):
        return l.get('area') == 'heap'

    # Compressed Class Space 는 Metaspace 풀에 포함돼 있어 더하면 이중계상이다(jvm-heap-sizing README 2절)
    def nonheap_no_ccs(l):
        return l.get('area') == 'nonheap' and 'Compressed Class Space' not in l.get('id', '')

    out = []
    for i, row in enumerate(step_rows):
        recovery = row['step'] == 'recovery'
        begin = t0 + i * hold
        end = begin + (run['recoverySec'] if recovery else hold)
        rows = [(ts, s) for ts, s in snaps if begin <= ts < end]
        if len(rows) < 2:
            out.append({'snapshots': len(rows)})
            continue
        gaps = [b[0] - a[0] for a, b in zip(rows, rows[1:])]
        lettuce_sum = delta(rows, 'lettuce_command_completion_seconds_sum')
        lettuce_count = delta(rows, 'lettuce_command_completion_seconds_count')
        out.append({
            'snapshots': len(rows),
            'scrapeGapMaxSec': round(max(gaps), 1),
            'processCpuAvg': round(avg(rows, 'process_cpu_usage') or 0, 3),
            'systemCpuAvg': round(avg(rows, 'system_cpu_usage') or 0, 3),
            'load1Max': peak(rows, 'system_load_average_1m'),
            'httpActiveMax': peak(rows, 'http_server_requests_active_seconds_count'),
            'tomcatBusyMax': peak(rows, 'tomcat_threads_busy_threads'),
            'tomcatConnectionsMax': peak(rows, 'tomcat_connections_current_connections'),
            'hikariActiveMax': peak(rows, 'hikaricp_connections_active'),
            'hikariPendingMax': peak(rows, 'hikaricp_connections_pending'),
            'hikariTimeoutDelta': delta(rows, 'hikaricp_connections_timeout_total'),
            'lettuceAvgMs': round(lettuce_sum / lettuce_count * 1000, 2) if lettuce_count else None,
            'lettuceMaxMs': round((each_max(rows, 'lettuce_command_completion_seconds_max') or 0) * 1000, 1),
            'heapOldMaxMb': mb(peak(rows, 'jvm_memory_used_bytes', old)),
            'heapUsedMaxMb': mb(peak(rows, 'jvm_memory_used_bytes', heap)),
            'heapCommittedMaxMb': mb(peak(rows, 'jvm_memory_committed_bytes', heap)),
            'nonheapCommittedMaxMb': mb(peak(rows, 'jvm_memory_committed_bytes', nonheap_no_ccs)),
            'directMaxMb': mb(peak(rows, 'jvm_buffer_memory_used_bytes')),
            'gcPauseSumMs': round((delta(rows, 'jvm_gc_pause_seconds_sum') or 0) * 1000),
            'gcPauseMaxMs': round((each_max(rows, 'jvm_gc_pause_seconds_max') or 0) * 1000),
            'liveThreadsMax': peak(rows, 'jvm_threads_live_threads'),
            'warnDelta': delta(rows, 'logback_events_total', lambda l: l.get('level') == 'warn'),
            'errorLogDelta': delta(rows, 'logback_events_total', lambda l: l.get('level') == 'error'),
        })
    return out


def journal_counts(path):
    pinned = {}
    redis_fallback = 0
    pinned_total = 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if '<== monitors' in line:
                pinned_total += 1
                key = line.strip()[:90]
                pinned[key] = pinned.get(key, 0) + 1
            elif re.search(r'RedisCacheErrorHandler|Redis.*(timed out|Timeout)', line):
                redis_fallback += 1
    top = sorted(pinned.items(), key=lambda kv: -kv[1])[:5]
    oom_heap, oom_other = count_oom(path)
    return {'pinnedFrames': pinned_total, 'pinnedTop': top, 'redisFallbackLogs': redis_fallback,
            'oomHeapLines': oom_heap, 'oomOtherLines': oom_other}


def count_oom(path):
    """앱 로그의 OutOfMemoryError 를 종류별로 센다 — Java 힙 OOM 인지 그 밖(스택·네이티브 등)인지 가른다(3-10절 K23)."""
    heap = other = 0
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if 'OutOfMemoryError' not in line:
                continue
            if 'Java heap space' in line:
                heap += 1
            else:
                other += 1
    return heap, other


def step_windows(run, step_rows):
    """계단별 [시작, 끝) 초. server_steps 와 같은 규칙이다."""
    t0 = run['setupEpochMs'] / 1000
    hold = run['holdSec']
    out = []
    for i, row in enumerate(step_rows):
        begin = t0 + i * hold
        out.append((begin, begin + (run['recoverySec'] if row['step'] == 'recovery' else hold)))
    return out


HOST_MIN_SAMPLES = 80   # 90초 계단에 1초 샘플 — 3-10절 유효성 게이트


def host_steps(path, run, step_rows):
    """sample-host.sh TSV 에서 계단별 프로세스 메모리. 값은 MB, RSS 는 게이지라 계단 안 최대(MemAvailable 은 최소)."""
    with open(path, encoding='utf-8', errors='replace') as f:
        header = f.readline().rstrip('\n').split('\t')
        rows = [dict(zip(header, line.rstrip('\n').split('\t'))) for line in f if line.strip()]

    def num(r, key):
        v = r.get(key)
        try:
            return float(v) if v not in (None, '') else None
        except ValueError:
            return None

    out = []
    for begin, end in step_windows(run, step_rows):
        win = [r for r in rows if num(r, 'ts') is not None and begin <= num(r, 'ts') < end]

        def kmax(key):
            v = [num(r, key) for r in win if num(r, key) is not None]
            return round(max(v) / 1024, 1) if v else None

        def kmin(key):
            v = [num(r, key) for r in win if num(r, key) is not None]
            return round(min(v) / 1024, 1) if v else None

        pids = {r.get('pid') for r in win if r.get('pid') not in (None, '', '0')}
        nthreads = [num(r, 'jvm_nthreads') for r in win if num(r, 'jvm_nthreads') is not None]
        out.append({
            'samples': len(win),
            'pids': sorted(pids),
            'rssMaxMb': kmax('jvm_rss_kb'),
            'rssAnonMaxMb': kmax('jvm_rss_anon_kb'),
            'rssFileMaxMb': kmax('jvm_rss_file_kb'),
            'hwmMaxMb': kmax('jvm_hwm_kb'),
            'memAvailMinMb': kmin('mem_avail_kb'),
            'osThreadsMax': max(nthreads) if nthreads else None,
        })
    return out


NMT_CATEGORY = re.compile(r'^-\s+(.+?)\s+\(reserved=(\d+)KB, committed=(\d+)KB\)')
NMT_TOTAL = re.compile(r'^Total: reserved=(\d+)KB, committed=(\d+)KB')


def parse_nmt(path):
    """jcmd VM.native_memory summary 한 장을 {영역: committed MB} 로. 읽지 못하면 None."""
    cats = {}
    total = None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            line = line.rstrip()
            m = NMT_TOTAL.match(line)
            if m:
                total = round(int(m.group(2)) / 1024, 1)
                continue
            m = NMT_CATEGORY.match(line)
            if m:
                cats[m.group(1).strip()] = round(int(m.group(3)) / 1024, 1)
    if total is None:
        return None
    return {'totalCommittedMb': total, 'categories': cats}


def nmt_steps(nmt_dir, run, step_rows):
    """계단마다 그 구간의 마지막 NMT 스냅샷(파일 이름이 epoch 초)."""
    snaps = []
    for name in os.listdir(nmt_dir):
        try:
            ts = float(name[:-4]) if name.endswith('.txt') else None
        except ValueError:
            ts = None
        if ts is not None:
            snaps.append((ts, os.path.join(nmt_dir, name)))
    snaps.sort()
    out = []
    for begin, end in step_windows(run, step_rows):
        inside = [(ts, p) for ts, p in snaps if begin <= ts < end]
        parsed = None
        for ts, p in reversed(inside):
            parsed = parse_nmt(p)
            if parsed:
                parsed['ts'] = ts
                break
        out.append({'snapshots': len(inside), **(parsed or {})})
    return out


def count_lines(path):
    with open(path, encoding='utf-8', errors='replace') as f:
        return sum(1 for line in f if line.strip())


def k6_cpu(path):
    """vmstat 5 출력에서 사용률(100 - idle)의 최댓값."""
    busy = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            p = line.split()
            if len(p) >= 15 and p[0].isdigit():
                busy.append(100 - int(p[14]))
    # 첫 줄은 부팅 이후 평균이다
    return max(busy[1:], default=None)


def knee(rows):
    last = None
    for r in rows:
        if r['step'] == 'recovery':
            break
        good = (r['errorRate'] is not None and r['errorRate'] < ERROR_RATE_LIMIT
                and r['p99Ms'] is not None and r['p99Ms'] < P99_LIMIT_MS
                and r['achievedRatio'] is not None and r['achievedRatio'] >= ACHIEVED_LIMIT)
        if not good:
            break
        last = r['rps']
    return last


def fmt(v, width=7):
    text = '-' if v is None else (f'{v:.0f}' if isinstance(v, float) else str(v))
    return f'{text:>{width}}'


def main():
    # Windows 콘솔·파이프 기본 인코딩(cp949)은 em dash 를 못 쓴다
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('--k6-log', required=True)
    ap.add_argument('--k6-summary', required=True)
    ap.add_argument('--poll', required=True)
    ap.add_argument('--journal', required=True)
    ap.add_argument('--k6-vmstat')
    # 회차 스크립트가 남긴 무효 사유 파일(선택). 파일이 있으면 그 내용을 무효 사유로 더한다 — 예: 루프 분산 게이트 실패
    ap.add_argument('--invalid-file', action='append', default=[])
    ap.add_argument('--host')
    ap.add_argument('--nmt-dir')
    ap.add_argument('--kernel-oom')
    ap.add_argument('--label', required=True)
    ap.add_argument('--out-json', required=True)
    args = ap.parse_args()

    run = read_run(args.k6_log)
    with open(args.k6_summary, encoding='utf-8') as f:
        summary = json.load(f)
    rows = k6_steps(summary, run)
    server = server_steps(args.poll, run, rows)
    journal = journal_counts(args.journal)
    cpu = k6_cpu(args.k6_vmstat) if args.k6_vmstat else None
    host = host_steps(args.host, run, rows) if args.host and os.path.isfile(args.host) else None
    nmt = nmt_steps(args.nmt_dir, run, rows) if args.nmt_dir and os.path.isdir(args.nmt_dir) else None
    kernel_oom = count_lines(args.kernel_oom) if args.kernel_oom and os.path.isfile(args.kernel_oom) else None
    if host:
        # 힙 밖 잔여 = RSS 최대 − (힙 커밋 + 비힙 커밋 + 다이렉트). 각 최대를 따로 구해 더하므로 잔여는 하한 쪽으로 기운다
        for h, s in zip(host, server):
            known = [s.get('heapCommittedMaxMb'), s.get('nonheapCommittedMaxMb'), s.get('directMaxMb')]
            if h['rssMaxMb'] is not None and None not in known:
                h['jvmKnownMb'] = sum(known)
                h['nativeOtherMb'] = round(h['rssMaxMb'] - h['jvmKnownMb'], 1)

    invalid = []
    flags = []
    for path in args.invalid_file:
        if os.path.isfile(path):
            with open(path, encoding='utf-8') as f:
                reason = f.read().strip()
            if reason:
                invalid.append(reason)
    for r in rows:
        if r['other']:
            invalid.append(f"계단 {r['step']}: 2xx·5xx·타임아웃이 아닌 응답 {r['other']}건 — 토큰 만료 등 도구 문제")
        if r['step'] != 'recovery' and r['sentRatio'] is not None and r['sentRatio'] < K6_SENT_SHORT:
            flags.append(f"계단 {r['step']}({r['rps']}/s): k6 가 예정의 {r['sentRatio']:.1%} 만 보냈다 — 이 계단은 k6 한계일 수 있다")
    if cpu is not None and cpu >= K6_CPU_BUSY:
        flags.append(f'k6 EC2 CPU 최대 {cpu}% — 상위 계단은 k6 가 병목일 수 있다')
    # 메모리 회차 유효성 게이트(3-10절)
    if host:
        all_pids = set()
        for r, h in zip(rows, host):
            all_pids.update(h['pids'])
            if h['samples'] < HOST_MIN_SAMPLES and r['step'] != 'recovery':
                flags.append(f"계단 {r['step']}({r['rps']}/s): RSS 샘플 {h['samples']}개 — {HOST_MIN_SAMPLES}개 미만이라 메모리 판정에서 뺀다")
        if len(all_pids) > 1:
            invalid.append(f"k6 구간에 앱 PID 가 바뀌었다({', '.join(sorted(all_pids))}) — 재기동이 섞였다")
    if nmt:
        for r, n in zip(rows, nmt):
            if r['step'] != 'recovery' and 'totalCommittedMb' not in n:
                flags.append(f"계단 {r['step']}({r['rps']}/s): NMT 스냅샷이 없다")

    result = {'label': args.label, 'run': run, 'knee': knee(rows), 'steps': rows, 'server': server,
              'journal': journal, 'k6CpuMax': cpu, 'invalid': invalid, 'flags': flags,
              'host': host, 'nmt': nmt, 'kernelOomLines': kernel_oom}
    with open(args.out_json, 'w', encoding='utf-8') as f:
        json.dump(result, f, ensure_ascii=False, indent=1)

    print(f"== {args.label} 한계점 {result['knee']} req/s · k6 CPU 최대 {cpu}")
    print('  계단   도착   달성   오류%     p50     p99     max |  CPU%  진행요청 Tomcat DB활성 DB대기 DB초과 Redis평균ms 최대ms OldMB GC합ms GC최대 공백s  warn')
    for r, s in zip(rows, server):
        err = '-' if r['errorRate'] is None else f"{r['errorRate'] * 100:.2f}"
        cpu_pct = '-' if 'processCpuAvg' not in s else f"{s['processCpuAvg'] * 100:.0f}"
        print(f"{str(r['step']):>6} {r['rps']:>6}{fmt(r['achievedRps'])} {err:>7}{fmt(r['p50Ms'])} {fmt(r['p99Ms'])} {fmt(r['maxMs'])} | "
              f"{cpu_pct:>4}{fmt(s.get('httpActiveMax'), 8)}{fmt(s.get('tomcatBusyMax'))}{fmt(s.get('hikariActiveMax'))}"
              f"{fmt(s.get('hikariPendingMax'))}{fmt(s.get('hikariTimeoutDelta'))} {str(s.get('lettuceAvgMs', '-')):>9} {str(s.get('lettuceMaxMs', '-')):>7}"
              f"{fmt(s.get('heapOldMaxMb'), 6)}{fmt(s.get('gcPauseSumMs'), 6)}{fmt(s.get('gcPauseMaxMs'), 6)} {str(s.get('scrapeGapMaxSec', '-')):>5}{fmt(s.get('warnDelta'), 6)}")
    print(f"피닝 스택 {journal['pinnedFrames']}줄 · Redis 폴백 관련 로그 {journal['redisFallbackLogs']}줄")
    for key, n in journal['pinnedTop']:
        print(f'   {n}회 {key}')
    if host:
        print('  계단   도착 | 샘플  RSS최대  RssAnon  RssFile    HWM  가용최소 | 힙커밋 비힙커밋 다이렉트  힙밖잔여 | OS스레드')
        for r, h, s in zip(rows, host, server):
            print(f"{str(r['step']):>6} {r['rps']:>6} | {h['samples']:>4}{fmt(h['rssMaxMb'], 8)}{fmt(h['rssAnonMaxMb'], 9)}{fmt(h['rssFileMaxMb'], 9)}"
                  f"{fmt(h['hwmMaxMb'])}{fmt(h['memAvailMinMb'], 9)} | {fmt(s.get('heapCommittedMaxMb'), 6)}{fmt(s.get('nonheapCommittedMaxMb'), 8)}"
                  f"{fmt(s.get('directMaxMb'), 8)}{fmt(h.get('nativeOtherMb'), 9)} | {fmt(h['osThreadsMax'], 7)}")
    if nmt:
        print('  NMT 계단 마지막 스냅샷 committed MB (Total · Java Heap · Thread · GC · Code · Class · Symbol · Other)')
        for r, n in zip(rows, nmt):
            c = n.get('categories', {})
            print(f"{str(r['step']):>6} {r['rps']:>6} | {n['snapshots']}장 {fmt(n.get('totalCommittedMb'))}{fmt(c.get('Java Heap'))}{fmt(c.get('Thread'))}"
                  f"{fmt(c.get('GC'))}{fmt(c.get('Code'))}{fmt(c.get('Class'))}{fmt(c.get('Symbol'))}{fmt(c.get('Other'))}")
    print(f"OutOfMemoryError: Java heap space {journal['oomHeapLines']}줄 · 그 밖 {journal['oomOtherLines']}줄"
          + ('' if kernel_oom is None else f" · 커널 OOM killer 기록 {kernel_oom}줄"))
    for f_ in flags:
        print('주의:', f_)
    for i_ in invalid:
        print('무효:', i_)
    return 4 if invalid else 0


if __name__ == '__main__':
    sys.exit(main())
