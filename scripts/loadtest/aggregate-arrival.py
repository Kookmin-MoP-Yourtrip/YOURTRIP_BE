#!/usr/bin/env python3
"""도착률 기반 AI 코스 생성 측정(scripts/k6/ai-course-arrival.js) 한 run을 요약한다 (이슈 #193).

aggregate-ai.py 와 같은 세 출처(k6 AIREQ 로그·k6 summary·poll-metrics 스냅샷)를 읽지만, 라운드가 아니라
"측정 구간" 하나를 본다.

  - 구간: 예정 도착 시각(scheduledMs)이 [웜업, durationSec) 안인 요청. 처음에는 서버가 빈 상태라
    거절이 구조적으로 적으므로 앞 웜업 구간을 뺀다
  - 거절률은 얼랑 B 예측과 나란히 낸다 — 자리 c개·즉시 거절 구조의 이론값이다. 제공 부하 a 는
    실제 도착 수와 받아들인 요청의 실측 평균 시간으로 계산한다(설정 도착률이 아니라)
  - 서버 지표는 구간 시작 직전 스냅샷부터 마지막 스냅샷까지의 Δ다. 구간 직전에 도착해 구간 안에서
    끝난 요청 몇 건이 섞이지만, 구간 뒤 꼬리(마지막 도착의 응답)는 빠짐없이 들어간다
  - 게이지는 구간 안의 1초 샘플로 "입장 자리가 상한에 붙어 있던 시간 비율"을 낸다

사용:
  aggregate-arrival.py <poll-file> --k6-log r5.log --k6-summary r5.json --limit 4 --label "상한4 r5"
"""
import argparse
import json
import os
import statistics
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import gauge_series, parse_snapshots, sum_metric  # noqa: E402
from importlib import import_module  # noqa: E402

aggregate_ai = import_module('aggregate-ai')
nearest_rank = aggregate_ai.nearest_rank
fast_api = aggregate_ai.fast_api

BUDGET_MS = 35000


def read_tagged(path, tag):
    """k6 stderr 의 "<tag> {json}" 줄을 읽는다. 감싸는 형식은 aggregate-ai.read_ai_requests 와 같다."""
    rows = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            pos = line.find(tag + ' ')
            if pos < 0:
                continue
            payload = line[pos + len(tag) + 1:].strip().split(' source=')[0]
            if payload.endswith('"'):
                payload = payload[:-1].replace('\\"', '"')
            rows.append(json.loads(payload))
    return rows


def erlang_b(c, a):
    """자리 c개에 제공 부하 a(얼랑)가 들어올 때 도착이 거절될 확률. 점화식으로 계산한다."""
    b = 1.0
    for k in range(1, c + 1):
        b = a * b / (k + a * b)
    return b


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('poll')
    ap.add_argument('--k6-log', required=True)
    ap.add_argument('--k6-summary', required=True)
    ap.add_argument('--limit', type=int, required=True, help='측정 때의 입장 상한')
    ap.add_argument('--warmup', type=int, default=60, help='집계에서 뺄 앞 구간(초)')
    ap.add_argument('--label', default='')
    args = ap.parse_args()

    run = read_tagged(args.k6_log, 'AIRUN')
    if not run:
        sys.exit('AIRUN 줄이 없다 — ai-course-arrival.js 로 잰 로그인지 확인하라')
    run = run[0]
    reqs = read_tagged(args.k6_log, 'AIREQ')
    duration_ms = run['durationSec'] * 1000
    warmup_ms = args.warmup * 1000
    window = [r for r in reqs if warmup_ms <= r['scheduledMs'] < duration_ms]
    window_min = (duration_ms - warmup_ms) / 60000

    # 예정 시각 0 의 실제 epoch. 요청마다 (보낸 시각 - 예정 시각)이 같아야 하므로 중앙값을 쓴다.
    zero_ms = statistics.median(r['sentAtMs'] - r['scheduledMs'] for r in reqs)
    lag = [r['sentAtMs'] - r['scheduledMs'] - zero_ms for r in reqs]
    window_start_s = (zero_ms + warmup_ms) / 1000
    window_end_s = (zero_ms + duration_ms) / 1000

    admitted = [r for r in window if r['status'] == 201]
    rejected = [r for r in window if r['status'] == 429]
    others = {}
    for r in window:
        if r['status'] not in (201, 429):
            key = f"{r['status']} {r.get('errorCode', '')}".strip()
            others[key] = others.get(key, 0) + 1
    adm_dur = sorted(r['durationMs'] for r in admitted)
    rej_dur = sorted(r['durationMs'] for r in rejected)
    budget_hit = sum(1 for d in adm_dur if d >= BUDGET_MS)

    rate_obs = len(window) / window_min
    w_obs = statistics.mean(adm_dur) / 1000 if adm_dur else 0
    a_obs = rate_obs / 60 * w_obs
    reject_rate = len(rejected) / len(window) if window else 0

    snaps = [s for s in parse_snapshots(args.poll) if s[1]]
    before = [s for s in snaps if s[0] <= window_start_s]
    first = before[-1] if before else snaps[0]
    last = snaps[-1]
    in_window = [s for s in snaps if window_start_s <= s[0] <= window_end_s]

    def delta(name, pred=lambda l: True):
        return sum_metric(last[1], name, pred) - sum_metric(first[1], name, pred)

    slot = {res: delta('ai_curation_slot_total', lambda l, res=res: l.get('result') == res)
            for res in ('curator', 'fallback', 'unfilled')}
    slots_total = sum(slot.values())
    timeouts = delta('ai_llm_permit_wait_seconds_count', lambda l: l.get('result') == 'timeout')
    calls = delta('ai_llm_call_seconds_count')
    in_use = gauge_series(in_window, 'ai_course_admission_in_use')
    at_limit = sum(1 for v in in_use if v >= args.limit)
    slots_in_use = gauge_series(in_window, 'ai_llm_permits_in_use')
    tour_hit = delta('ai_candidate_retrieval_total',
                     lambda l: l.get('source') == 'tour_api' and l.get('result') == 'hit')
    fast_duration, fast_failed = fast_api(args.k6_summary)

    print(f"### {args.label}")
    print(f"- 설정: 분당 {run['ratePerMin']}건 · {run['durationSec']}초 · 시드 {run['seed']} · 상한 {args.limit}"
          f" · 웜업 {args.warmup}초 제외")
    print(f"- 출발 지연(예정 대비): 최대 {max(lag):.0f}ms · 최소 {min(lag):.0f}ms")
    print(f"- 구간 도착 {len(window)}건 (실측 분당 {rate_obs:.2f}건) · 받아들임 {len(admitted)} · 거절 {len(rejected)}"
          f" · 기타 {others or '없음'}")
    print(f"- **거절률 {100 * reject_rate:.1f}%** · 얼랑 B 예측 {100 * erlang_b(args.limit, a_obs):.1f}%"
          f" (a = {a_obs:.2f}, W 실측 {w_obs:.1f}초) · W=22초 가정 {100 * erlang_b(args.limit, rate_obs / 60 * 22):.1f}%")
    if adm_dur:
        print(f"- 받아들인 요청(ms) p50 {nearest_rank(adm_dur, 50):,} · p95 {nearest_rank(adm_dur, 95):,}"
              f" · max {adm_dur[-1]:,} · **예산 소진 {budget_hit}/{len(adm_dur)}"
              f" ({100 * budget_hit / len(adm_dur):.1f}%)**")
    if rej_dur:
        print(f"- 거절 응답(ms) p50 {nearest_rank(rej_dur, 50):,} · max {rej_dur[-1]:,}")
    print(f"- **Curator 폴백 {slot['fallback']:.0f} / {slots_total:.0f}"
          f" ({100 * slot['fallback'] / slots_total if slots_total else 0:.1f}%)**, unfilled {slot['unfilled']:.0f}")
    if in_use:
        print(f"- 입장 자리 상한 도달 시간 {100 * at_limit / len(in_use):.1f}% ({at_limit}/{len(in_use)}초)"
              f" · 평균 점유 {statistics.mean(in_use):.2f}")
    if slots_in_use:
        print(f"- LLM 슬롯 평균 점유 {statistics.mean(slots_in_use):.2f} / 최대 {max(slots_in_use):.0f}")
    print(f"- LLM 호출 {calls:.0f}회 · 슬롯 대기 포기 {timeouts:.0f}회 · TourAPI 성공 {tour_hit:.0f}건")
    print(f"- 빠른 API(ms) med {fast_duration.get('med', 0):.1f} · p95 {fast_duration.get('p(95)', 0):.1f}"
          f" · max {fast_duration.get('max', 0):.1f}, 실패율 {fast_failed}")


if __name__ == '__main__':
    main()
