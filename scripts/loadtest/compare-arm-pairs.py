#!/usr/bin/env python3
"""같은 회차의 arm 쌍(기준 vs 처치)을 비교해 비율·방향 일치·사전 등록 판정을 낸다(#210).

run-batch.sh 결과 디렉터리를 summarize-batch.py로 run별 요약(JSON)한 뒤, 지정 레벨(기본 vu200)의
<run>.schedstat.txt를 함께 읽어 쌍마다 "처치 ÷ 기준"을 계산한다. 회차(rep)가 같은 두 run을 한 쌍으로
본다 — run-batch는 홀수 회차 정순·짝수 회차 역순이라, 같은 회차의 두 arm은 시간상 인접해 표류를 공유한다.

판정 규칙은 docs/tasks/tomcat-thread-sizing/lettuce-priority.md 4-3을 **측정 전에 적은 그대로** 옮겼다.
결과를 본 뒤 기준을 바꾸면 사전 등록의 의미가 사라지므로, 이 파일의 THRESH를 고치려면 문서부터 고친다.

사용:  compare-arm-pairs.py <results-dir> --pair T32:T32+nice10 [--pair T200:T200+nice10]
                            [--level vu200] [--md out.md]
"""
import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))

# lettuce-priority.md 4-3 — 측정 전에 등록한 값
THRESH = {'tps': 0.05, 'p95': 0.10, 'req_cpu': 0.05}
MIN_AGREE = 3  # 4쌍 중 3쌍

ROW_RE = re.compile(r'^\| (\w+) \| ([\d.]+) \| ([\d.]+) \| ([\d.]+)% \| ([\d.]+)% \| (\d+) \| (\d+) \| (\d+) \| (\d+) \|')
HEAD_RE = re.compile(r'pid=\d+ wall=(\d+)ms requests=(\d+)')
NICE_RE = re.compile(r'tid=\d+ \S+ nice=(-?\d+|\?)')


def parse_schedstat(path):
    """sample-schedstat.sh 출력 → 그룹별 수치 + 창 정보 + lettuce 스레드별 nice."""
    out = {'groups': {}, 'nices': []}
    with open(path, encoding='utf-8') as f:
        for line in f:
            m = HEAD_RE.search(line)
            if m:
                out['wall_ms'], out['requests'] = int(m.group(1)), int(m.group(2))
                continue
            m = ROW_RE.match(line)
            if m and m.group(1) not in out['groups']:  # 첫 표만(두 번째 표는 열 구성이 다르다)
                out['groups'][m.group(1)] = {
                    'run_ms': float(m.group(2)), 'wait_ms': float(m.group(3)),
                    'switches': int(m.group(7)), 'vol': int(m.group(8)), 'nonvol': int(m.group(9))}
                continue
            m = NICE_RE.search(line)
            if m:
                out['nices'].append(m.group(1))
    return out


def derive(run, ss):
    """판정·메커니즘 지표를 한 dict로. ss가 없으면 schedstat 파생값은 None."""
    d = {
        'tps': run.get('tps'), 'p95': run.get('k6_p95_ms'), 'k6_avg': run.get('k6_avg_ms'),
        'req_cpu': run.get('req_cpu_ms'), 'redis_first': run.get('redis_first_ms'),
        'redis_per_req': run.get('redis_ms_per_req'), 'cmds_per_req': run.get('cmds_per_req'),
        'ctxt_per_req': run.get('ctxt_per_req'), 'host_busy': run.get('host_busy_pct'),
    }
    if not ss or 'lettuce' not in ss['groups'] or not ss.get('wall_ms'):
        return d
    wall_s, reqs = ss['wall_ms'] / 1e3, ss['requests']
    le, tc = ss['groups']['lettuce'], ss['groups'].get('tomcat', {})
    d.update({
        'nices': ','.join(ss['nices']),
        'let_preempt_per_s': le['nonvol'] / wall_s,
        'let_vol_share': le['vol'] / le['switches'] if le['switches'] else None,
        'let_wait_ratio': le['wait_ms'] / (le['run_ms'] + le['wait_ms']) if le['run_ms'] + le['wait_ms'] else None,
        'let_cpu_share': le['run_ms'] / ss['wall_ms'],
        'let_dispatch_per_s': le['switches'] / wall_s,
        # 배정당 명령 수 = 창 안 명령 수 ÷ lettuce 배정 수(lettuce-priority.md 2절)
        'cmds_per_dispatch': (d['cmds_per_req'] or 0) * reqs / le['switches'] if le['switches'] else None,
        'tomcat_wait_per_req': tc.get('wait_ms', 0) / reqs if reqs else None,
    })
    return d


METRICS = [  # (키, 표 이름, 판정용인가)
    ('tps', 'TPS', True), ('p95', 'p95(ms)', True), ('req_cpu', '요청당 CPU(vCPU-ms)', True),
    ('k6_avg', 'k6 평균(ms)', False), ('redis_first', 'Redis 지연(ms)', False),
    ('redis_per_req', '요청당 Redis(ms)', False), ('tomcat_wait_per_req', '요청당 tomcat 런큐 대기(ms)', False),
    ('let_preempt_per_s', 'lettuce 선점/초', False), ('let_vol_share', 'lettuce 자발 비중', False),
    ('let_wait_ratio', 'lettuce 대기 비율', False), ('let_cpu_share', 'lettuce CPU 점유', False),
    ('let_dispatch_per_s', 'lettuce 배정/초', False), ('cmds_per_dispatch', '배정당 명령 수', False),
    ('ctxt_per_req', '요청당 전환', False), ('cmds_per_req', '명령/요청', False), ('host_busy', 'host busy%', False),
]


def fmt(v, key):
    if v is None:
        return '—'
    if key in ('let_vol_share', 'let_wait_ratio', 'let_cpu_share'):
        return f'{v * 100:.1f}%'
    if key in ('tps', 'let_preempt_per_s', 'let_dispatch_per_s'):
        return f'{v:,.0f}'
    return f'{v:.3f}' if abs(v) < 10 else f'{v:.1f}'


def judge(pairs):
    """4-3 판정. pairs = [(base, treat), ...] (derive 결과)."""
    def ratios(k):
        return [t[k] / b[k] - 1 for b, t in pairs if b.get(k) and t.get(k) is not None]
    tps, p95, cpu = ratios('tps'), ratios('p95'), ratios('req_cpu')
    worse = [
        ('TPS −5% 이하', sum(r <= -THRESH['tps'] for r in tps)),
        ('p95 +10% 이상', sum(r >= THRESH['p95'] for r in p95)),
        ('요청당 CPU +5% 이상', sum(r >= THRESH['req_cpu'] for r in cpu)),
    ]
    hit = [f'{name} {n}/{len(tps)}쌍' for name, n in worse if n >= MIN_AGREE]
    if hit:
        return '악화', hit
    mean_tps = statistics.mean(tps) if tps else 0
    agree = sum(r > 0 for r in tps)
    p95_bad = sum(r > THRESH['p95'] for r in p95)
    if mean_tps >= THRESH['tps'] and agree >= MIN_AGREE and p95_bad < MIN_AGREE:
        return '개선', [f'TPS 평균 {mean_tps:+.1%}, 같은 방향 {agree}/{len(tps)}쌍']
    return '효과 없음', [f'TPS 평균 {mean_tps:+.1%}(같은 방향 {agree}/{len(tps)}쌍) — 기준 ±5%·3쌍 미충족']


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('results')
    ap.add_argument('--pair', action='append', required=True, help='기준arm:처치arm')
    ap.add_argument('--level', default='vu200')
    ap.add_argument('--md')
    a = ap.parse_args()

    # 결과 디렉터리에 쓰지 않는다 — 이미 있는 summary 산출물을 덮어쓸 수 있다
    with tempfile.TemporaryDirectory() as td:
        tmp = os.path.join(td, 'summary.json')
        subprocess.run([sys.executable, os.path.join(HERE, 'summarize-batch.py'), a.results, '--json', tmp],
                       check=True, stdout=subprocess.DEVNULL)
        with open(tmp, encoding='utf-8') as f:
            runs = [r for r in json.load(f) if r.get('level') == a.level]

    def find(arm, rep):
        for r in runs:
            if r['arm'] == arm and int(r['rep']) == rep:
                ss_path = os.path.join(a.results, f"{r['label']}.schedstat.txt")
                return derive(r, parse_schedstat(ss_path) if os.path.exists(ss_path) else None)
        return None

    lines = []
    for spec in a.pair:
        base_arm, treat_arm = spec.split(':')
        reps = sorted({int(r['rep']) for r in runs if r['arm'] in (base_arm, treat_arm)})
        pairs = [(find(base_arm, k), find(treat_arm, k)) for k in reps]
        pairs = [(b, t) for b, t in pairs if b and t]
        lines.append(f'## {treat_arm} ÷ {base_arm} ({a.level}, {len(pairs)}쌍)\n')
        lines.append('| 지표 | ' + ' | '.join(f'r{k} 기준 → 처치' for k in reps) + ' | **평균 비** | 증가 쌍 |')
        lines.append('|---|' + '---|' * len(reps) + '---|---|')
        for key, name, primary in METRICS:
            cells, rs = [], []
            for b, t in pairs:
                bv, tv = b.get(key), t.get(key)
                cells.append(f'{fmt(bv, key)} → {fmt(tv, key)}')
                if bv and tv is not None:
                    rs.append(tv / bv)
            mean = f'×{statistics.mean(rs):.3f}' if rs else '—'
            up = f'{sum(r > 1 for r in rs)}/{len(rs)}' if rs else '—'
            label = f'**{name}**' if primary else name
            lines.append(f'| {label} | ' + ' | '.join(cells) + f' | {mean} | {up} |')
        nices = [f"r{k} {b.get('nices', '?')} / {t.get('nices', '?')}" for k, (b, t) in zip(reps, pairs)]
        lines.append(f"\nlettuce nice(창 끝, 기준 / 처치): {'; '.join(nices)}")
        verdict, why = judge(pairs)
        lines.append(f'\n**판정(4-3): {verdict}** — ' + '; '.join(why) + '\n')

    text = '\n'.join(lines)
    print(text)
    if a.md:
        with open(a.md, 'w', encoding='utf-8') as f:
            f.write(text + '\n')


if __name__ == '__main__':
    main()
