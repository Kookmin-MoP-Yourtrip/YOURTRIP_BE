#!/usr/bin/env python3
"""run-batch.sh가 남긴 결과 디렉터리를 훑어 run별 요약을 한 표로 만든다.

<run>.window(t0/t1)·<run>.prom·<run>.host.tsv·<run>.k6.json을 짝지어 aggregate.py를 호출하고,
run 이름 `<tag>_<arm>_vu<N>_r<rep>`을 파싱해 (arm, vu, rep) 순으로 정렬한다.

사용:  summarize-batch.py <results-dir> [--json out.json] [--md out.md]
"""
import argparse
import json
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RUN_RE = re.compile(r'^(?P<tag>[^_]+)_(?P<arm>.+?)_(?P<level>vu\d+|mixed)_r(?P<rep>\d+)$')

COLS = [('label', 'run'), ('tps', 'TPS'), ('server_avg_ms', '서버 평균(ms)'),
        ('redis_first_ms', 'Redis first(ms)'), ('redis_completion_ms', 'Redis compl(ms)'),
        ('cmds_per_req', '명령/요청'), ('redis_ms_per_req', 'Redis/요청(ms)'), ('redis_share_pct', 'Redis 비중%'),
        ('busy_max', 'busy 최대'), ('conn_wait_max', '대기 커넥션 최대'), ('proc_cpu_avg', 'CPU proc'),
        ('host_load1_avg', 'load1'), ('host_procs_running_avg', 'runnable 평균'), ('host_steal_pct', 'steal%'),
        ('pending_max', 'pending 최대'),
        # #97 — 26%의 분해에 쓰는 열. 요청당 CPU를 user/sys로 가르고, 전환 횟수와 GC를 따로 세운다.
        ('req_cpu_ms', '요청당 CPU(vCPU-ms)'), ('user_ms_per_req', '요청당 user(ms)'),
        ('sys_ms_per_req', '요청당 sys(ms)'), ('cpu_xcheck_pct', 'CPU 교차검증%'),
        ('ctxt_per_req', '요청당 전환'), ('gc_ms_per_req', '요청당 GC(ms)'),
        ('alloc_kb_per_req', '요청당 할당(KB)'), ('minflt_per_req', '요청당 minflt'),
        ('gc_names', 'GC'), ('jvm_threads_max', 'JVM 스레드 최대'), ('host_ncpu', 'vCPU'),
        ('pid_changed', 'PID 변경'),
        ('k6_avg_ms', 'k6 avg'), ('k6_p95_ms', 'p95'), ('k6_p99_ms', 'p99'),
        ('k6_fail_rate', '실패율'), ('errors_5xx', '5xx'), ('scheduler_cmds', '스케줄러 명령'), ('sql', 'SQL')]


def arm_key(arm):
    m = re.match(r'T(\d+)(\+snc)?', arm)
    if not m:
        return (999, arm)
    return (-int(m.group(1)), 1 if m.group(2) else 0)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('dir')
    ap.add_argument('--json')
    ap.add_argument('--md')
    a = ap.parse_args()

    rows = []
    for fn in sorted(os.listdir(a.dir)):
        if not fn.endswith('.window'):
            continue
        run = fn[:-7]
        m = RUN_RE.match(run)
        if not m:
            print(f'skip {run}', file=sys.stderr)
            continue
        with open(os.path.join(a.dir, fn)) as f:
            w = dict(kv.split('=') for kv in f.read().split())
        cmd = [sys.executable, os.path.join(HERE, 'aggregate.py'), os.path.join(a.dir, run + '.prom'),
               '--from', w['t0'], '--to', w['t1'], '--label', run, '--json']
        host = os.path.join(a.dir, run + '.host.tsv')
        k6 = os.path.join(a.dir, run + '.k6.json')
        if os.path.exists(host):
            cmd += ['--host', host]
        if os.path.exists(k6):
            cmd += ['--k6', k6]
        out = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8')
        if out.returncode != 0:
            print(f'{run}: {out.stderr.strip()}', file=sys.stderr)
            continue
        r = json.loads(out.stdout)
        r.update(arm=m['arm'], level=m['level'], rep=int(m['rep']))
        lvl = m['level']
        r['vu'] = int(lvl[2:]) if lvl.startswith('vu') else 0
        rows.append(r)

    rows.sort(key=lambda r: (arm_key(r['arm']), r['vu'], r['rep']))

    if a.json:
        with open(a.json, 'w', encoding='utf-8') as f:
            json.dump(rows, f, ensure_ascii=False, indent=1)

    def fmt(v):
        return '—' if v is None else str(v)
    lines = ['| ' + ' | '.join(h for _, h in COLS) + ' |', '|' + '---|' * len(COLS)]
    for r in rows:
        lines.append('| ' + ' | '.join(fmt(r.get(k)) for k, _ in COLS) + ' |')
    md = '\n'.join(lines)
    print(md)
    if a.md:
        with open(a.md, 'w', encoding='utf-8') as f:
            f.write(md + '\n')


if __name__ == '__main__':
    main()
