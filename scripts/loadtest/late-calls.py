#!/usr/bin/env python3
"""AI 코스 동시 요청 한 run에서 '마감 뒤에 끝난 Curator 호출'을 센다 (이슈 #189, 로드맵 6단계).

예산(ai.course.budget-ms)을 넘기면 요청은 실패하지 않고, 못 끝난 day 를 후보 목록 상위로 채운
폴백으로 나간다. 그런데 버려진 Curator 호출은 취소되지 않고 끝까지 돌아 success 로 기록된다
(STEP-3). 그래서 그 호출이 마감을 몇 초 넘겨 끝났는지 알면 "예산이 T초였다면 몇 회가 쓰였을까"를
추가 측정 없이 역산할 수 있다. 버려진 호출은 이미 슬롯을 쥐고 끝까지 돌았으므로, 예산을 늘려도
LLM 부하는 그대로이고 결과를 쓰게 될 뿐이라는 점이 이 역산의 전제다.

요청별 이벤트 로그가 없어도 되는 이유는 측정 구조에 있다.
  - ai-course-concurrent.js 는 한 라운드의 N명을 같은 순간에 출발시킨다 → 라운드 안의 마감이 같다
  - poll-metrics.sh 는 1초마다 누적 카운터를 남긴다 → ai_llm_call_seconds_count{agent=curator}
    증가분을 그 스냅샷 시각에 귀속하면 호출 완료 시각이 1초 해상도로 나온다

한계: 스냅샷 귀속이라 시각이 최대 약 1초 늦게 잡힌다(폴백 기록 시각이 마감 + 0.7~2초로 보인다).

사용:
  late-calls.py <poll-file> --k6-log c5.log [--budget 30] [--label "N=5"]
"""
import argparse
import json
import re
import sys
from datetime import datetime

AIREQ = re.compile(r'time="([^"]+)".*?AIREQ (\{.*\})"')
SNAPSHOT_TS = re.compile(r'^# ts=([0-9.]+)')
# 같은 지표의 여러 시리즈(outcome 등)는 더한다.
SERIES = {
    'curator': re.compile(r'^ai_llm_call_seconds_count\{agent="curator",[^}]*\} ([0-9.]+)'),
    'fallback': re.compile(r'^ai_curation_slot_total\{result="fallback"\} ([0-9.]+)'),
    'curated': re.compile(r'^ai_curation_slot_total\{result="curator"\} ([0-9.]+)'),
}
BINS = [(0, 20), (20, 25), (25, 30), (30, 32), (32, 35), (35, 40), (40, 45), (45, 50),
        (50, 60), (60, 999)]


def read_requests(path):
    """AIREQ 줄 → 요청별 시작 시각. 끝난 시각은 k6 로그의 초 해상도라 시작도 ±1초다."""
    rows = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = AIREQ.search(line)
            if not m:
                continue
            # k6 는 console 출력을 msg="..." 로 감싸고 안쪽 따옴표를 이스케이프한다
            payload = json.loads(m.group(2).replace('\\"', '"'))
            end = datetime.fromisoformat(m.group(1)).timestamp()
            payload['start'] = end - payload['durationMs'] / 1000
            rows.append(payload)
    return rows


def read_snapshots(path):
    snaps, current = [], None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = SNAPSHOT_TS.match(line)
            if m:
                current = {'ts': float(m.group(1))}
                snaps.append(current)
                continue
            if current is None or not line.startswith('ai_'):
                continue
            for key, pattern in SERIES.items():
                mm = pattern.match(line)
                if mm:
                    current[key] = current.get(key, 0.0) + float(mm.group(1))
    return snaps


def events_by_round(snaps, round_starts):
    """누적 카운터 증가분을 (라운드 시작 기준 상대 시각, 증가량)으로 바꾼다."""
    events = {key: [] for key in SERIES}
    for prev, now in zip(snaps, snaps[1:]):
        started = [r for r, s in round_starts.items() if s <= now['ts']]
        if not started:
            continue
        rel = now['ts'] - round_starts[max(started)]
        for key in SERIES:
            delta = now.get(key, 0) - prev.get(key, 0)
            if delta > 0:
                events[key].append((rel, delta))
    return events


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('poll')
    parser.add_argument('--k6-log', required=True)
    parser.add_argument('--budget', type=float, default=30, help='측정 때의 예산(초)')
    parser.add_argument('--label', default='')
    args = parser.parse_args()
    sys.stdout.reconfigure(encoding='utf-8')

    reqs = read_requests(args.k6_log)
    if not reqs:
        sys.exit('AIREQ 줄이 없다')
    rounds = {}
    for r in reqs:
        rounds.setdefault(r['round'], []).append(r)
    starts = {rd: min(x['start'] for x in rs) for rd, rs in rounds.items()}
    spread = max(max(x['start'] for x in rs) - starts[rd] for rd, rs in rounds.items())

    budget_ms = args.budget * 1000
    durations = sorted(x['durationMs'] for x in reqs)
    over = [d - budget_ms for d in durations if d > budget_ms]
    print(f'## late-calls {args.label} (예산 {args.budget:g}초)')
    print(f'- 요청 {len(reqs)}건 · 라운드 {len(rounds)}개 · 라운드 안 출발 편차 최대 {spread:.2f}초')
    print(f'- 요청 p50 {durations[len(durations) // 2] / 1000:.1f}초 · '
          f'max {durations[-1] / 1000:.2f}초 · 예산 초과 {len(over)}건'
          + (f' (초과분 = 후처리: 최대 {max(over):.0f}ms · 평균 {sum(over) / len(over):.0f}ms)'
             if over else ''))

    events = events_by_round(read_snapshots(args.poll), starts)
    curator = events['curator']
    total = sum(d for _, d in curator)
    late = sum(d for rel, d in curator if rel >= args.budget)
    print(f'\nCurator 호출 완료 시각 (라운드 시작 기준, 1초 해상도) — 총 {total:.0f}회')
    for lo, hi in BINS:
        n = sum(d for rel, d in curator if lo <= rel < hi)
        if n:
            print(f'  {lo:>3}~{hi:<3}초: {n:4.0f}회')
    print(f'- 예산 뒤 완료 = 버려진 호출: {late:.0f}회'
          + (f' ({late / total * 100:.1f}%)' if total else ''))
    for extra in (5, 10, 15, 20):
        horizon = args.budget + extra
        saved = sum(d for rel, d in curator if args.budget <= rel < horizon)
        print(f'  예산 {horizon:g}초였다면 쓰였을 호출: {saved:.0f}회')

    fallback = sum(d for _, d in events['fallback'])
    curated = sum(d for _, d in events['curated'])
    if fallback + curated:
        print(f'\n- 슬롯: curator {curated:.0f} · fallback {fallback:.0f} '
              f'({fallback / (fallback + curated) * 100:.1f}%)')
    times = [rel for rel, _ in events['fallback']]
    if times:
        print(f'- 폴백 기록 시각(라운드 기준): {min(times):.1f} ~ {max(times):.1f}초')


if __name__ == '__main__':
    main()
