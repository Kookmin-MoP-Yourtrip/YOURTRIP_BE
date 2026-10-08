#!/usr/bin/env python3
"""여행 일수로 가중한 AI 입장 제한의 일수별 거절률을 계산한다 (#200 4단계 — 긴 일정이 굶는가).

입장 제한은 대기 없이 즉시 429 를 내는 손실 시스템이다. 요청 하나가 자리 (1 + 일수)개를 한꺼번에
쥐므로 "자리 수가 다른 여러 종류의 손님"이 섞인 손실 시스템이 되고, 종류별 거절 확률은
Kaufman–Roberts 재귀가 정확히 준다. 얼랑 B 의 일반화라 같은 무감응성(처리 시간 분포와 무관하게
평균만으로 성립)을 가진다 — #193 에서 얼랑 B 가 실측 거절률과 1~4%p 로 맞은 근거가 그대로 이어진다.

비교하는 정책:
  fixed4     요청 수 상한 4 (지금 구조). 얼랑 B, 일수와 무관하게 거절률이 같다
  weighted   자리 총량 16, 요청은 1 + 일수 자리. Kaufman–Roberts
  reserve-r  weighted + 자리 예약: 가장 긴 일정이 아닌 요청은 들어간 뒤에도 빈자리가 r 개 이상
             남을 때만 받는다(trunk reservation). 상태 의존이라 재귀가 성립하지 않아 시뮬레이션으로 잰다

사용:
  admission-blocking.py                 # 기본 가정 표
  admission-blocking.py --scale 1.3     # 처리 시간을 30% 늘린 민감도
"""
import argparse
import heapq
import random

DAYS = (1, 2, 3, 4, 5)
TOTAL = 16

# 일수별 평균 처리 시간(초). 1·3일은 이번 기준선(동시 4) p50 11.3 · 19.0초, 그 사이를 일당 4초로 잇고
# 5일은 같은 기울기로 27초 — 기준선 5일 p50(27~29초)과 맞는다. 가중 입장에서는 슬롯 경합이
# 총량 16 안에 묶이므로 이보다 짧을 가능성이 크다(보수적 가정).
HOLD = {d: 11.0 + 4.0 * (d - 1) for d in DAYS}

# 일수 분포 가정 — 실제 트래픽이 없어 셋을 나란히 본다.
MIXES = {
    '국내 여행형 (2·3일 중심)': {1: .15, 2: .35, 3: .35, 4: .10, 5: .05},
    '균등': {d: .2 for d in DAYS},
    '긴 일정형 (5일 40%)': {1: .10, 2: .15, 3: .15, 4: .20, 5: .40},
}


def need(d):
    return 1 + d


def erlang_b(servers, load):
    b = 1.0
    for k in range(1, servers + 1):
        b = load * b / (k + load * b)
    return b


def kaufman_roberts(total, loads):
    """loads: {자리 수: 제공 부하(얼랑)}. 반환: {자리 수: 거절 확률}."""
    q = [0.0] * (total + 1)
    q[0] = 1.0
    for n in range(1, total + 1):
        q[n] = sum(a * b * q[n - b] for b, a in loads.items() if b <= n) / n
    norm = sum(q)
    p = [x / norm for x in q]
    # b 자리를 원하는 요청은 사용 중 자리가 total - b 보다 많으면 거절된다
    return {b: sum(p[n] for n in range(total - b + 1, total + 1)) for b in loads}


def simulate(rate_per_sec, mix, hold, total, reserve, horizon=2_000_000, seed=200):
    """포아송 도착 · 지수 처리 시간 이벤트 시뮬레이션. 반환: {일수: 거절률}."""
    rng = random.Random(seed)
    longest = max(mix)
    days, weights = zip(*mix.items())
    used, t = 0, 0.0
    departures = []
    offered = {d: 0 for d in days}
    blocked = {d: 0 for d in days}
    while t < horizon:
        t += rng.expovariate(rate_per_sec)
        while departures and departures[0][0] <= t:
            used -= heapq.heappop(departures)[1]
        d = rng.choices(days, weights)[0]
        b = need(d)
        offered[d] += 1
        margin = 0 if d == longest else reserve
        if total - used - b >= margin:
            used += b
            heapq.heappush(departures, (t + rng.expovariate(1 / hold[d]), b))
        else:
            blocked[d] += 1
    return {d: blocked[d] / offered[d] for d in days}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scale', type=float, default=1.0, help='처리 시간 배율(민감도)')
    ap.add_argument('--rates', default='3,5,8', help='분당 도착 수 목록')
    ap.add_argument('--reserves', default='2,4', help='시뮬레이션할 예약 자리 수 목록')
    args = ap.parse_args()
    hold = {d: h * args.scale for d, h in HOLD.items()}
    rates = [float(x) for x in args.rates.split(',')]
    reserves = [int(x) for x in args.reserves.split(',')]

    print(f'처리 시간(초): {", ".join(f"{d}일 {hold[d]:.0f}" for d in DAYS)}  · 총량 {TOTAL}\n')
    for name, mix in MIXES.items():
        print(f'### {name}: ' + ' · '.join(f'{d}일 {int(p * 100)}%' for d, p in mix.items()))
        header = '| 분당 | 정책 | ' + ' | '.join(f'{d}일' for d in DAYS) + ' | 전체 | 받은 자리-초/초 |'
        print(header)
        print('|' + '---|' * (len(DAYS) + 4))
        for rpm in rates:
            lam = rpm / 60
            rows = []
            # 지금 구조: 요청 4개, 일수 무관
            mean_hold = sum(p * hold[d] for d, p in mix.items())
            b4 = erlang_b(4, lam * mean_hold)
            rows.append(('상한 4건', {d: b4 for d in DAYS}))
            loads = {need(d): lam * p * hold[d] for d, p in mix.items()}
            kr = kaufman_roberts(TOTAL, loads)
            rows.append((f'총량 {TOTAL} (계산)', {d: kr[need(d)] for d in DAYS}))
            for r in [0] + reserves:
                label = f'총량 {TOTAL} (시뮬)' if r == 0 else f'총량 {TOTAL} + 예약 {r}'
                rows.append((label, simulate(lam, mix, hold, TOTAL, r)))
            for label, block in rows:
                overall = sum(p * block[d] for d, p in mix.items())
                # 받아들인 작업량 — 같은 거절률이라도 긴 요청을 더 받으면 슬롯을 더 쓴다
                carried = sum(lam * p * (1 - block[d]) * hold[d] * need(d) for d, p in mix.items())
                cells = ' | '.join(f'{block[d] * 100:.1f}%' for d in DAYS)
                print(f'| {rpm:g} | {label} | {cells} | {overall * 100:.1f}% | {carried:.1f} |')
        print()


if __name__ == '__main__':
    main()
