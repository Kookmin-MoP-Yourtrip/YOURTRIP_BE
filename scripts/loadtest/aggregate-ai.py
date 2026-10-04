#!/usr/bin/env python3
"""AI 코스 동시 요청 시나리오(scripts/k6/ai-course-concurrent.js) 한 run을 요약한다 (이슈 #175).

세 출처를 합친다.
  - k6 stderr 의 "AIREQ {json}" 줄: AI 요청별 상태·소요 — 클라이언트가 실제로 기다린 시간
  - k6 summary JSON: 빠른 API(api:fast) 지연 — 서버에 http.server.requests 히스토그램이 없어 여기서 본다
  - poll-metrics.sh 스냅샷: 슬롯 게이지 최대, 슬롯 대기 포기·폴백·LLM 결말의 run 전체 Δ

카운터는 첫/끝 스냅샷 Δ, 게이지는 1초 샘플의 최대다(aggregate.py 와 같은 규칙·같은 파서).

사용:
  aggregate-ai.py <poll-file> --k6-log c3.log --k6-summary c3.json --label "N=3"
"""
import argparse
import json
import math
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import gauge_series, parse_snapshots, sum_metric  # noqa: E402


def nearest_rank(sorted_values, p):
    """최근접 순위법 — AiCourseLatencyBaselineTest 와 같은 정의라 두 표를 나란히 읽을 수 있다."""
    if not sorted_values:
        return 0
    index = math.ceil(p / 100 * len(sorted_values)) - 1
    return sorted_values[min(max(index, 0), len(sorted_values) - 1)]


def read_ai_requests(path):
    rows = []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            pos = line.find('AIREQ ')
            if pos < 0:
                continue
            payload = line[pos + len('AIREQ '):].strip()
            # k6 는 console 출력을 time=... msg="AIREQ {...}" source=console 로 감싸고 안쪽 따옴표를
            # 이스케이프한다. 꼬리표를 먼저 떼고 닫는 따옴표·이스케이프를 푼다.
            payload = payload.split(' source=')[0]
            if payload.endswith('"'):
                payload = payload[:-1].replace('\\"', '"')
            rows.append(json.loads(payload))
    return rows


def fast_api(summary_path):
    with open(summary_path, encoding='utf-8') as f:
        metrics = json.load(f)['metrics']
    duration = metrics.get('http_req_duration{api:fast}', {})
    failed = metrics.get('http_req_failed{api:fast}', {})
    return duration, failed.get('value', failed.get('rate'))


def delta(snaps, name, pred=lambda l: True):
    return sum_metric(snaps[-1][1], name, pred) - sum_metric(snaps[0][1], name, pred)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('poll')
    ap.add_argument('--k6-log', required=True)
    ap.add_argument('--k6-summary', required=True)
    ap.add_argument('--label', default='')
    args = ap.parse_args()

    reqs = read_ai_requests(args.k6_log)
    durations = sorted(r['durationMs'] for r in reqs)
    ok = sum(1 for r in reqs if r['status'] == 201)
    errors = {}
    for r in reqs:
        if r['status'] != 201:
            key = f"{r['status']} {r.get('errorCode', '')}".strip()
            errors[key] = errors.get(key, 0) + 1

    snaps = [s for s in parse_snapshots(args.poll) if s[1]]
    if len(snaps) < 2:
        sys.exit('스냅샷이 2개 미만이다 — poll-metrics.sh 가 돌았는지 확인하라')

    def max_gauge(name):
        series = gauge_series(snaps, name)
        return max(series) if series else float('nan')

    timeouts = delta(snaps, 'ai_llm_permit_wait_seconds_count', lambda l: l.get('result') == 'timeout')
    curator_wait_sum = delta(snaps, 'ai_llm_permit_wait_seconds_sum', lambda l: l.get('agent') == 'curator')
    slot = {res: delta(snaps, 'ai_curation_slot_total', lambda l, res=res: l.get('result') == res)
            for res in ('curator', 'fallback', 'unfilled')}
    calls = delta(snaps, 'ai_llm_call_seconds_count')
    non_success = calls - delta(snaps, 'ai_llm_call_seconds_count', lambda l: l.get('outcome') == 'success')
    planner_non_success = delta(snaps, 'ai_llm_call_seconds_count',
                                lambda l: l.get('agent') == 'planner' and l.get('outcome') != 'success')
    slots_total = sum(slot.values())

    fast_duration, fast_failed = fast_api(args.k6_summary)

    print(f"### {args.label}")
    print(f"- AI 요청 {len(reqs)}건, 201 {ok}건, 오류 {errors or '없음'}")
    print(f"- AI 클라이언트 소요(ms) p50 {nearest_rank(durations, 50):,} · p95 {nearest_rank(durations, 95):,}"
          f" · max {durations[-1] if durations else 0:,}")
    print(f"- 슬롯 in_use 최대 {max_gauge('ai_llm_permits_in_use'):.0f}, waiting 최대 {max_gauge('ai_llm_permits_waiting'):.0f},"
          f" tomcat busy 최대 {max_gauge('tomcat_threads_busy_threads'):.0f}")
    print(f"- 슬롯 대기 포기 {timeouts:.0f}회, Curator 대기 합 {curator_wait_sum:.1f}s"
          f" (요청당 {curator_wait_sum / max(len(reqs), 1):.1f}s)")
    print(f"- Curator 폴백 슬롯 {slot['fallback']:.0f} / {slots_total:.0f}"
          f" ({100 * slot['fallback'] / slots_total if slots_total else 0:.1f}%), unfilled {slot['unfilled']:.0f}")
    print(f"- LLM 호출 {calls:.0f}회, 비성공 {non_success:.0f}회 (Planner {planner_non_success:.0f})")
    print(f"- 빠른 API(ms) med {fast_duration.get('med', 0):.1f} · p95 {fast_duration.get('p(95)', 0):.1f}"
          f" · max {fast_duration.get('max', 0):.1f}, 실패율 {fast_failed}")


if __name__ == '__main__':
    main()
