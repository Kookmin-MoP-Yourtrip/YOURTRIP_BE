#!/usr/bin/env python3
"""빠른 API 계단 부하(fast-api-ramp.js) 회차를 계단별로 집계한다.

설계: docs/tasks/ai-bulkhead-loadtest/async-variant.md 3-7절

입력:
  --k6-log      k6 stderr. FASTRUN 줄(계단 구성과 시작 시각)
  --k6-summary  --summary-export. 계단별 커스텀 지표
  --poll        1초 지표 스냅샷(metrics.prom)
  --journal     앱 로그. 피닝 추적과 Redis 폴백 경고를 센다
  --k6-vmstat   k6 EC2 의 vmstat(선택). k6 가 먼저 밀렸는지 본다

한계점 = 오류율 1% 미만, p99 1초 미만, 달성률 97% 이상을 처음부터 연속으로 만족한 마지막 계단의 도착률.
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
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            i = line.find('FASTRUN ')
            if i >= 0:
                # k6 console.error 는 msg="..." 로 감싸 따옴표를 이스케이프한다
                raw = line[i + 8:].strip().replace('\\"', '"')
                return json.loads(raw[:raw.rindex('}') + 1])
    raise SystemExit('FASTRUN 줄이 없다')


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
    return {'pinnedFrames': pinned_total, 'pinnedTop': top, 'redisFallbackLogs': redis_fallback}


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

    invalid = []
    flags = []
    for r in rows:
        if r['other']:
            invalid.append(f"계단 {r['step']}: 2xx·5xx·타임아웃이 아닌 응답 {r['other']}건 — 토큰 만료 등 도구 문제")
        if r['step'] != 'recovery' and r['sentRatio'] is not None and r['sentRatio'] < K6_SENT_SHORT:
            flags.append(f"계단 {r['step']}({r['rps']}/s): k6 가 예정의 {r['sentRatio']:.1%} 만 보냈다 — 이 계단은 k6 한계일 수 있다")
    if cpu is not None and cpu >= K6_CPU_BUSY:
        flags.append(f'k6 EC2 CPU 최대 {cpu}% — 상위 계단은 k6 가 병목일 수 있다')

    result = {'label': args.label, 'run': run, 'knee': knee(rows), 'steps': rows, 'server': server,
              'journal': journal, 'k6CpuMax': cpu, 'invalid': invalid, 'flags': flags}
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
    for f_ in flags:
        print('주의:', f_)
    for i_ in invalid:
        print('무효:', i_)
    return 4 if invalid else 0


if __name__ == '__main__':
    sys.exit(main())
