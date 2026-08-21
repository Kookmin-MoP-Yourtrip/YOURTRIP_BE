#!/usr/bin/env python3
"""poll-metrics.sh 산출물(+ sample-host.sh TSV, k6 summary JSON)을 한 run의 요약 행으로 집계한다.

카운터형 지표는 창의 첫/끝 스냅샷 Δ로 계산한다(Δsum ÷ Δcount = 구간 평균). 게이지형은 창 안
샘플의 평균/최대다. 1초 폴링이라 게이지 최대는 "1초 해상도에서의 최대"라는 한계가 있다.

사용:
  aggregate.py <poll-file> [--from EPOCH --to EPOCH] [--host host.tsv] [--k6 summary.json]
               [--label "T32 VU200"] [--json]

출력(마크다운 한 행):
  label | 요청 수 | TPS | Redis GET/MGET 평균(first/completion ms) | 요청당 명령 | 요청당 Redis(ms)
  | busy 최대 | 대기 커넥션 최대(current-busy) | CPU proc/sys 평균 | load1 평균 | steal% | pending 최대
  | 요청당 CPU(vCPU-ms) | 요청당 user/sys(ms) | 요청당 전환 | 요청당 GC(ms)
  | 힙/논힙 committed·RSS(MB) | MemAvailable 최소(MB)
  | k6 avg/p95/p99 | k6 실패 | 스케줄러 틱(EVALSHA/DEL Δ)
"""
import argparse
import json
import re
import statistics
import sys

# 라벨 값에 '}'가 들어갈 수 있다(uri="/api/upload-courses/{uploadCourseId}") — [^}]*로 끊으면 그 라인을 통째로 놓친다.
LINE_RE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{.*\})?\s+([-+0-9.eE]+|NaN)\s*$')
LABEL_RE = re.compile(r'(\w+)="([^"]*)"')


def parse_snapshots(path):
    """[(ts, {(name, labels_tuple): value})]"""
    snaps = []
    cur_ts, cur = None, None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.startswith('# ts='):
                if cur is not None:
                    snaps.append((cur_ts, cur))
                cur_ts, cur = float(line[5:].strip()), {}
                continue
            if cur is None or line.startswith('#'):
                continue
            m = LINE_RE.match(line)
            if not m:
                continue
            name, labels, val = m.group(1), m.group(2) or '', m.group(3)
            if val == 'NaN':
                continue
            cur[(name, labels)] = float(val)
    if cur is not None:
        snaps.append((cur_ts, cur))
    return snaps


def labels_of(labels_str):
    return dict(LABEL_RE.findall(labels_str))


def sum_metric(snap, name, pred=lambda l: True):
    return sum(v for (n, l), v in snap.items() if n == name and pred(labels_of(l)))


def gauge_series(snaps, name, pred=lambda l: True):
    out = []
    for _, s in snaps:
        vals = [v for (n, l), v in s.items() if n == name and pred(labels_of(l))]
        if vals:
            out.append(sum(vals))
    return out


def is_app_uri(l):
    return not l.get('uri', '').startswith('/actuator')


def main():
    # Windows 콘솔(cp949)에서 '—' 같은 문자가 깨지지 않게 stdout을 UTF-8로 고정한다.
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('poll')
    ap.add_argument('--from', dest='t_from', type=float)
    ap.add_argument('--to', dest='t_to', type=float)
    ap.add_argument('--host')
    ap.add_argument('--k6')
    ap.add_argument('--label', default='')
    ap.add_argument('--json', action='store_true')
    a = ap.parse_args()

    snaps = parse_snapshots(a.poll)
    if a.t_from is not None:
        snaps = [s for s in snaps if s[0] >= a.t_from]
    if a.t_to is not None:
        snaps = [s for s in snaps if s[0] <= a.t_to]
    if len(snaps) < 2:
        sys.exit('need >= 2 snapshots in window')
    (t0, s0), (t1, s1) = snaps[0], snaps[-1]
    wall = t1 - t0

    def delta(name, pred=lambda l: True):
        return sum_metric(s1, name, pred) - sum_metric(s0, name, pred)

    r = {'label': a.label, 'samples': len(snaps), 'wall_s': round(wall, 1)}

    req = delta('http_server_requests_seconds_count', is_app_uri)
    req_sum = delta('http_server_requests_seconds_sum', is_app_uri)
    r['requests'] = int(req)
    r['tps'] = round(req / wall, 1) if wall else None
    r['server_avg_ms'] = round(req_sum / req * 1000, 2) if req else None

    def redis(kind):
        p = lambda l: l.get('command') in ('GET', 'MGET')
        c = delta(f'lettuce_command_{kind}_seconds_count', p)
        s = delta(f'lettuce_command_{kind}_seconds_sum', p)
        return (round(s / c * 1000, 3) if c else None), int(c)
    r['redis_first_ms'], cmds = redis('firstresponse')
    r['redis_completion_ms'], _ = redis('completion')
    r['redis_cmds'] = cmds
    r['cmds_per_req'] = round(cmds / req, 3) if req else None
    r['redis_ms_per_req'] = (round(r['redis_first_ms'] * r['cmds_per_req'], 3)
                             if r['redis_first_ms'] is not None and r['cmds_per_req'] is not None else None)
    r['redis_share_pct'] = (round(r['redis_ms_per_req'] / r['server_avg_ms'] * 100, 1)
                            if r['redis_ms_per_req'] is not None and r['server_avg_ms'] else None)
    sched = lambda l: l.get('command') in ('EVALSHA', 'DEL', 'EVAL')
    r['scheduler_cmds'] = int(delta('lettuce_command_completion_seconds_count', sched))

    busy = gauge_series(snaps, 'tomcat_threads_busy_threads')
    cur = gauge_series(snaps, 'tomcat_connections_current_connections')
    r['busy_max'] = int(max(busy)) if busy else None
    r['busy_avg'] = round(statistics.mean(busy), 1) if busy else None
    r['conn_current_max'] = int(max(cur)) if cur else None
    r['conn_wait_max'] = int(max(c - b for c, b in zip(cur, busy))) if cur and busy else None
    r['config_max_threads'] = int(gauge_series(snaps, 'tomcat_threads_config_max_threads')[-1])
    pcpu = gauge_series(snaps, 'process_cpu_usage')
    scpu = gauge_series(snaps, 'system_cpu_usage')
    r['proc_cpu_avg'] = round(statistics.mean(pcpu), 3) if pcpu else None
    r['sys_cpu_avg'] = round(statistics.mean(scpu), 3) if scpu else None
    la = gauge_series(snaps, 'system_load_average_1m')
    r['load1_avg'] = round(statistics.mean(la), 2) if la else None
    pend = gauge_series(snaps, 'hikaricp_connections_pending')
    r['pending_max'] = int(max(pend)) if pend else None
    r['conn_borrows'] = int(delta('hikaricp_connections_usage_seconds_count'))
    r['sql'] = int(delta('hibernate_statements_total'))
    r['errors_5xx'] = int(delta('http_server_requests_seconds_count',
                                lambda l: is_app_uri(l) and l.get('status', '').startswith('5')))

    # JVM/GC — #97에서 추가했다. 요청당 유저 CPU 시간이 arm마다 다른 이유가 캐시 지역성이 아니라
    # GC나 JIT일 수 있으므로 그 몫을 차감할 수 있어야 한다(스레드 200개 = TLAB 200개 → young GC
    # 빈도가 오를 수 있다). 원자료는 poll-metrics.sh가 전량 저장해 왔으므로 집계만 추가하면 된다.
    r['gc_count'] = int(delta('jvm_gc_pause_seconds_count'))
    r['gc_ms'] = round(delta('jvm_gc_pause_seconds_sum') * 1000, 1)
    r['gc_alloc_mb'] = round(delta('jvm_gc_memory_allocated_bytes_total') / 1024 / 1024, 1)
    r['jit_ms'] = round(delta('jvm_compilation_time_ms_total'), 1)
    # gc 라벨 값 — 이 박스가 G1으로 뜨는지 Serial로 뜨는지가 여기서 갈린다(-XX 플래그가 없어
    # ergonomics가 정한다: t3.micro면 SerialGC "Copy", t3.small이면 G1 "G1 Young Generation").
    r['gc_names'] = ','.join(sorted({labels_of(l).get('gc', '')
                                     for (n, l) in s1 if n == 'jvm_gc_pause_seconds_count'} - {''}))
    live = gauge_series(snaps, 'jvm_threads_live_threads')
    r['jvm_threads_max'] = int(max(live)) if live else None

    # JVM 메모리 영역 — #101에서 추가했다. -Xmx448m은 t3.micro(1GB) 전제로 잡힌 값이라
    # t3.small(2GB) 기준으로 재산정해야 하는데, 그러려면 "힙 밖에 실제로 얼마나 쓰는가"를
    # 알아야 한다. 전부 게이지라 창 안 최대를 쓴다(1초 해상도의 최대라는 한계는 위와 같다).
    MB = 1024 * 1024

    def mem_max_mb(name, pred=lambda l: True):
        vals = gauge_series(snaps, name, pred)
        return round(max(vals) / MB, 1) if vals else None

    in_area = lambda want: (lambda l: l.get('area') == want)
    r['heap_used_max_mb'] = mem_max_mb('jvm_memory_used_bytes', in_area('heap'))
    r['heap_committed_max_mb'] = mem_max_mb('jvm_memory_committed_bytes', in_area('heap'))
    # Compressed Class Space는 Metaspace 풀에 **포함**된다 — 논힙 풀을 그냥 다 더하면
    # 이중계상이다. JDK 21에서 NMT로 직접 확인했다: Metadata committed 9,306,112 +
    # Class space committed 1,376,256 = 10,682,368 이 MXBean의 Metaspace committed와
    # 정확히 일치했다. 그래서 합계에서는 CCS를 빼고, 값 자체는 따로 낸다(약 19MB 차이).
    nonheap = lambda l: l.get('area') == 'nonheap' and l.get('id') != 'Compressed Class Space'
    r['nonheap_used_max_mb'] = mem_max_mb('jvm_memory_used_bytes', nonheap)
    r['nonheap_committed_max_mb'] = mem_max_mb('jvm_memory_committed_bytes', nonheap)
    r['ccs_committed_mb'] = mem_max_mb('jvm_memory_committed_bytes',
                                       lambda l: l.get('id') == 'Compressed Class Space')
    # 논힙의 70%가 메타스페이스라 따로 뽑는다. 코드 캐시는 CodeHeap 3종(non-nmethods,
    # non-profiled nmethods, profiled nmethods)의 합이다.
    r['metaspace_mb'] = mem_max_mb('jvm_memory_used_bytes', lambda l: l.get('id') == 'Metaspace')
    r['codecache_mb'] = mem_max_mb('jvm_memory_used_bytes',
                                   lambda l: l.get('id', '').startswith('CodeHeap'))
    r['direct_buffer_mb'] = mem_max_mb('jvm_buffer_memory_used_bytes',
                                       lambda l: l.get('id') == 'direct')
    # 힙 상한 — 힙 arm이 실제로 적용됐는지 검증하는 열이다(config_max_threads가 Tomcat arm을
    # 검증하는 것과 같은 역할). G1은 Eden/Survivor의 max를 -1로 내보내므로 양수만 더한다.
    heap_max = []
    for _, snap in snaps:
        vals = [v for (n, l), v in snap.items()
                if n == 'jvm_memory_max_bytes' and labels_of(l).get('area') == 'heap' and v > 0]
        if vals:
            heap_max.append(sum(vals))
    r['heap_max_mb'] = round(max(heap_max) / MB, 1) if heap_max else None

    if a.host:
        rows = []
        with open(a.host) as f:
            hdr = f.readline().rstrip('\n').split('\t')
            for line in f:
                p = line.rstrip('\n').split('\t')
                if len(p) != len(hdr):
                    continue
                d = dict(zip(hdr, p))
                ts = float(d['ts'])
                if (a.t_from is None or ts >= a.t_from) and (a.t_to is None or ts <= a.t_to):
                    rows.append(d)
        if len(rows) >= 2:
            r['host_load1_avg'] = round(statistics.mean(float(x['load1']) for x in rows), 2)
            r['host_procs_running_avg'] = round(statistics.mean(float(x['procs_running']) for x in rows), 1)
            r['host_procs_running_max'] = max(int(x['procs_running']) for x in rows)
            keys = ['cpu_user', 'cpu_nice', 'cpu_system', 'cpu_idle', 'cpu_iowait', 'cpu_irq', 'cpu_softirq', 'cpu_steal']
            d0, d1 = rows[0], rows[-1]
            tot = sum(int(d1[k]) - int(d0[k]) for k in keys)
            hwall = float(d1['ts']) - float(d0['ts'])
            if tot > 0:
                r['host_steal_pct'] = round((int(d1['cpu_steal']) - int(d0['cpu_steal'])) / tot * 100, 2)
                r['host_busy_pct'] = round((tot - (int(d1['cpu_idle']) - int(d0['cpu_idle'])) - (int(d1['cpu_iowait']) - int(d0['cpu_iowait']))) / tot * 100, 1)
                # vCPU 수는 하드코딩하지 않고 cpu 행 누적 jiffies에서 되돌린다(USER_HZ=100).
                # 요청당 CPU 비용을 내려면 필요한데, 인스턴스 타입이 바뀌어도 자동으로 따라온다.
                if hwall > 0:
                    r['host_ncpu'] = round(tot / (hwall * 100), 2)

            # 창 안에서 JVM PID가 바뀌면 /proc/<pid>/stat의 누적 카운터가 리셋되므로
            # 그 run의 JVM 파생값은 통째로 버린다(재기동이 창에 걸린 경우).
            pids = {x.get('pid') for x in rows if x.get('pid') not in (None, '', '0')}
            r['pid_changed'] = len(pids) > 1

            def hdelta(key):
                if key not in d0 or not d0.get(key) or not d1.get(key):
                    return None
                return int(d1[key]) - int(d0[key])

            d_ctxt = hdelta('ctxt')
            if d_ctxt is not None:
                r['ctxt'] = d_ctxt
            if not r['pid_changed']:
                # utime/stime은 jiffies(USER_HZ=100) → ms 환산은 x10.
                # 이 둘의 분리가 #97의 핵심이다: 요청당 유저 모드 명령어 수는 arm과 무관하게
                # 같으므로, 유저 시간/요청의 차이는 "일이 늘어난 것"이 아니라 "같은 일이 느려진 것"
                # = 캐시/TLB 지역성이다. 전환 경로·스케줄러·시스템콜은 커널 시간에 잡힌다.
                du, ds = hdelta('jvm_utime'), hdelta('jvm_stime')
                if du is not None:
                    r['jvm_user_ms'] = du * 10
                if ds is not None:
                    r['jvm_sys_ms'] = ds * 10
                mf = hdelta('jvm_minflt')
                if mf is not None:
                    r['jvm_minflt'] = mf
                # RSS는 누적 카운터가 아니라 게이지라 Δ가 아니라 창 안 최대를 쓴다.
                # sample-host.sh가 이미 KB로 환산해 남기므로 페이지 크기를 여기서 가정하지 않는다.
                def hmax_mb(key):
                    v = [int(x[key]) for x in rows if x.get(key) not in (None, '')]
                    return round(max(v) / 1024, 1) if v else None

                def hmin_mb(key):
                    v = [int(x[key]) for x in rows if x.get(key) not in (None, '')]
                    return round(min(v) / 1024, 1) if v else None

                r['rss_max_mb'] = hmax_mb('jvm_rss_kb')
                r['mem_total_mb'] = hmax_mb('mem_total_kb')
                # MemAvailable은 최소값이 안전 판정 기준이다 — 창 안에서 가장 빠듯했던 순간.
                r['mem_avail_min_mb'] = hmin_mb('mem_avail_kb')

    # 파생 — 요청당 값. #97의 분해는 전부 이 정규화 위에서 이뤄진다.
    # arm마다 TPS가 다르므로(2,517 vs 2,921) 초당 값을 그대로 비교하면 안 된다.
    def per_req(v, scale=1.0, nd=4):
        return round(v * scale / req, nd) if req and v is not None else None

    ncpu = r.get('host_ncpu')
    # 요청 하나를 처리하는 데 실제로 쓴 CPU. #88이 200 -> 32에서 0.747 -> 0.555로 26% 줄었다고
    # 보고한 그 지표다(vCPU-ms).
    r['req_cpu_ms'] = (round(r['proc_cpu_avg'] * ncpu * 1000 / r['tps'], 4)
                       if ncpu and r.get('proc_cpu_avg') and r.get('tps') else None)
    r['user_ms_per_req'] = per_req(r.get('jvm_user_ms'))
    r['sys_ms_per_req'] = per_req(r.get('jvm_sys_ms'))
    if r.get('user_ms_per_req') is not None and r.get('sys_ms_per_req') is not None:
        r['jvm_cpu_ms_per_req'] = round(r['user_ms_per_req'] + r['sys_ms_per_req'], 4)
        # 교차검증: /proc에서 direct로 잰 요청당 CPU와 process_cpu_usage 기반 값이 맞아야 한다.
        # 5% 넘게 벌어지면 창 정렬이나 PID가 어긋난 것이므로 그 run을 의심한다.
        if r['req_cpu_ms']:
            r['cpu_xcheck_pct'] = round((r['jvm_cpu_ms_per_req'] / r['req_cpu_ms'] - 1) * 100, 1)
    r['ctxt_per_req'] = per_req(r.get('ctxt'), nd=2)
    r['minflt_per_req'] = per_req(r.get('jvm_minflt'), nd=3)
    r['gc_ms_per_req'] = per_req(r.get('gc_ms'), nd=4)
    r['alloc_kb_per_req'] = per_req(r.get('gc_alloc_mb'), scale=1024, nd=2)

    # 힙 밖 잔여 = RSS − (힙 committed + 논힙 committed + direct buffer).
    # 스레드 스택과 GC/컴파일러/심볼 같은 네이티브가 여기 들어간다 — Actuator가 노출하지
    # 않는 영역이라 이 뺄셈이 유일한 근사치다. NMT 프로파일 run(5~10% 오버헤드가 있어
    # 비교 run과 분리한다)의 jcmd 덤프로 교차검증한다.
    _known = ('heap_committed_max_mb', 'nonheap_committed_max_mb', 'direct_buffer_mb')
    if all(r.get(k) is not None for k in _known):
        # JVM이 스스로 보고하는 몫. CCS는 논힙에 이미 포함돼 있으므로 더하지 않는다.
        r['jvm_known_mb'] = round(sum(r[k] for k in _known), 1)
    if r.get('jvm_known_mb') is not None and r.get('rss_max_mb') is not None:
        r['native_other_mb'] = round(r['rss_max_mb'] - r['jvm_known_mb'], 1)
    if r.get('mem_total_mb') is not None and r.get('rss_max_mb') is not None:
        # 박스에 남는 여유. -Xmx 상한을 정하는 안전 조건이다.
        r['mem_headroom_mb'] = round(r['mem_total_mb'] - r['rss_max_mb'], 1)

    if a.k6:
        with open(a.k6) as f:
            k = json.load(f)
        m = k.get('metrics', {})
        dur = m.get('http_req_duration', {})
        # k6 summary-export는 버전에 따라 값이 최상위 또는 "values" 아래에 있다.
        dv = dur.get('values', dur)
        r['k6_avg_ms'] = round(dv.get('avg', 0), 2)
        r['k6_p95_ms'] = round(dv.get('p(95)', 0), 2)
        r['k6_p99_ms'] = round(dv.get('p(99)', 0), 2)
        reqs = m.get('http_reqs', {})
        rv = reqs.get('values', reqs)
        r['k6_reqs'] = int(rv.get('count', 0))
        r['k6_rps'] = round(rv.get('rate', 0), 1)
        fails = m.get('http_req_failed', {})
        fv = fails.get('values', fails)
        r['k6_fail_rate'] = fv.get('rate', fv.get('value'))

    if a.json:
        print(json.dumps(r, ensure_ascii=False))
        return

    def f(v, fmt='{}'):
        return '—' if v is None else fmt.format(v)
    print('| ' + ' | '.join([
        r['label'], f(r['requests']), f(r['tps']),
        f"{f(r['redis_first_ms'])} / {f(r['redis_completion_ms'])}", f(r['cmds_per_req']),
        f"{f(r['redis_ms_per_req'])} ({f(r['redis_share_pct'])}%)",
        f(r['busy_max']), f(r['conn_wait_max']),
        f"{f(r['proc_cpu_avg'])} / {f(r['sys_cpu_avg'])}", f(r.get('host_load1_avg', r['load1_avg'])),
        f(r.get('host_steal_pct')), f(r['pending_max']),
        f(r.get('req_cpu_ms')),
        f"{f(r.get('user_ms_per_req'))} / {f(r.get('sys_ms_per_req'))}",
        f(r.get('ctxt_per_req')), f(r.get('gc_ms_per_req')),
        f"{f(r.get('heap_committed_max_mb'))} / {f(r.get('nonheap_committed_max_mb'))} / {f(r.get('rss_max_mb'))}",
        f(r.get('mem_avail_min_mb')),
        f"{f(r.get('k6_avg_ms'))} / {f(r.get('k6_p95_ms'))} / {f(r.get('k6_p99_ms'))}",
        f(r.get('k6_fail_rate')), f(r['scheduler_cmds']),
    ]) + ' |')


if __name__ == '__main__':
    main()
