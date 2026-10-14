#!/usr/bin/env python3
"""게이트 G4 — bpftrace 추적 부하가 측정 대상을 바꿨는가.

같은 run 안에서 "추적 창 안의 TPS"와 "창 밖의 TPS"를 비교한다.
  - TPS: k6 EC2의 1초 폴링(<run>.prom, poll-metrics.sh)에서 http_server_requests_seconds_count
         (actuator 제외) 합계의 초당 증가분
  - 추적 창: App EC2의 sample-sched-latency.sh가 남긴 <run>.env.txt의 window_start/window_end(epoch)
    두 호스트 모두 chrony 동기화라 1초 폴링 해상도에서는 충분하다.
  - 창 밖: run 시작 15초(램프업·JIT)와 끝 5초를 버린 나머지 중 창 앞뒤 2초를 뺀 구간
판정: 창 안 TPS가 창 밖보다 3% 이상 낮으면 FAIL.

사용:  python sched-latency-overhead.py <run-prefix> [<run-prefix> ...]
"""
import os
import re
import sys

REQ = re.compile(r'^http_server_requests_seconds_count\{(?P<labels>[^}]*)\}\s+(?P<v>\S+)')


def load_series(path):
    """[(ts, 누적 요청 수)]"""
    out = []
    ts, total, ok = None, 0.0, False
    with open(path, encoding="utf-8", errors="replace") as f:
        for line in f:
            if line.startswith("# ts="):
                if ts is not None and ok:
                    out.append((ts, total))
                ts, total, ok = float(line[5:].strip()), 0.0, False
                continue
            m = REQ.match(line)
            if m and 'uri="/actuator' not in m.group("labels"):
                total += float(m.group("v"))
                ok = True
    if ts is not None and ok:
        out.append((ts, total))
    return out


def load_window(path):
    w = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            if "=" in line:
                k, v = line.strip().split("=", 1)
                w[k] = v
    return float(w["window_start"]), float(w["window_end"])


def rate(series, lo, hi):
    pts = [(t, c) for t, c in series if lo <= t <= hi]
    if len(pts) < 2:
        return None, 0
    (t0, c0), (t1, c1) = pts[0], pts[-1]
    return (c1 - c0) / (t1 - t0), t1 - t0


def main(prefix):
    series = load_series(prefix + ".prom")
    ws, we = load_window(prefix + ".env.txt")
    start, end = series[0][0] + 15, series[-1][0] - 5
    inside, in_sec = rate(series, ws, we)
    before, b_sec = rate(series, start, ws - 2)
    after, a_sec = rate(series, we + 2, end)
    out_sec = b_sec + a_sec
    outside = ((before or 0) * b_sec + (after or 0) * a_sec) / out_sec if out_sec else None
    if inside is None or not outside:
        print(f"| {prefix} | — | — | — | 데이터 부족 |")
        return
    diff = (inside - outside) / outside
    verdict = "FAIL" if diff <= -0.03 else "PASS"
    print(f"| {os.path.basename(prefix)} | {inside:.0f} ({in_sec:.0f}s) | {outside:.0f} ({out_sec:.0f}s) | {diff:+.1%} | {verdict} |")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    print("| run | 창 안 TPS | 창 밖 TPS | 차이 | G4 |")
    print("|---|---|---|---|---|")
    for p in sys.argv[1:]:
        main(p)
