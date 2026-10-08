"""장애 격리 주실험(#197) — 회차 하나의 배경 API 지연을 10초 창별 p50·p95·p99·에러 수로 펼친다.

집계기(aggregate-bulkhead.py)는 판정에 필요한 p99 만 본다. 이 스크립트는 "p99 를 골라서 장애가 나온 것
아니냐"는 물음에 답하려고, 같은 창에서 p50·p95 도 함께 보여 준다(결과 문서의 절벽형 붕괴 표).

시각 기준은 집계기와 같다 — t0 = min(sentAtMs − offsetMs), 표의 초는 AI 도착 시작(t0 + 웜업 + 기준선) 기준이다.
백분위수는 집계기와 같은 최근접 순위 방식이다.

사용:  python bulkhead-windows.py <k6.log> [--from -40] [--to 360] [--markdown]
"""
import argparse
import json
import math
import re

LINE_RE = re.compile(r'msg="(BHRUN|AIREQ|BGREQ) (.*)" source=')
WINDOW_MS = 10000


def load(path):
    run, ai, bg = None, [], []
    with open(path, encoding='utf-8') as f:
        for line in f:
            m = LINE_RE.search(line)
            if not m:
                continue
            d = json.loads(m.group(2).replace('\\"', '"'))
            if m.group(1) == 'BHRUN':
                run = d
            elif m.group(1) == 'AIREQ':
                ai.append(d)
            else:
                bg.append(d)
    return run, ai, bg


def pct(values, p):
    if not values:
        return None
    v = sorted(values)
    return v[max(0, math.ceil(p * len(v)) - 1)]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('k6_log')
    ap.add_argument('--from', dest='start', type=int, default=-40, help='AI 도착 시작 기준 초')
    ap.add_argument('--to', type=int, default=360)
    ap.add_argument('--markdown', action='store_true')
    args = ap.parse_args()

    run, ai, bg = load(args.k6_log)
    t0 = min(r['sentAtMs'] - r['offsetMs'] for r in ai)
    ai_start_ms = run['phases']['aiStartMs'] if run and 'aiStartMs' in run.get('phases', {}) else None
    if ai_start_ms is None:
        # BHRUN 에 AI 시작 시각이 없으면 본 단계 첫 도착의 오프셋으로 대신한다
        ai_start_ms = min(r['offsetMs'] for r in ai if r['phase'] == 'main')
        ai_start_ms -= ai_start_ms % WINDOW_MS

    if args.markdown:
        print('| 구간(초) | 요청 | p50 | p95 | p99 | 에러 |')
        print('|---|---|---|---|---|---|')
    else:
        print('구간(초)\t요청\tp50\tp95\tp99\t에러')
    for s in range(args.start, args.to, WINDOW_MS // 1000):
        lo = t0 + ai_start_ms + s * 1000
        rows = [r for r in bg if lo <= r['t'] < lo + WINDOW_MS]
        ms = [r['ms'] for r in rows]
        err = sum(1 for r in rows if r['s'] != 200)
        cells = [s, len(rows), pct(ms, .5), pct(ms, .95), pct(ms, .99), err]
        if args.markdown:
            print('| ' + ' | '.join(str(c) for c in cells) + ' |')
        else:
            print('\t'.join(str(c) for c in cells))


if __name__ == '__main__':
    main()
