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
            if tot > 0:
                r['host_steal_pct'] = round((int(d1['cpu_steal']) - int(d0['cpu_steal'])) / tot * 100, 2)
                r['host_busy_pct'] = round((tot - (int(d1['cpu_idle']) - int(d0['cpu_idle'])) - (int(d1['cpu_iowait']) - int(d0['cpu_iowait']))) / tot * 100, 1)

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
        f"{f(r.get('k6_avg_ms'))} / {f(r.get('k6_p95_ms'))} / {f(r.get('k6_p99_ms'))}",
        f(r.get('k6_fail_rate')), f(r['scheduler_cmds']),
    ]) + ' |')


if __name__ == '__main__':
    main()
