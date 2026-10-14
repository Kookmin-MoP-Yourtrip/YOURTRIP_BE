#!/usr/bin/env python3
"""sample-sched-latency.sh 결과(<prefix>.schedlat.json + <prefix>.schedstat.txt)를 표로 요약한다.

- bpftrace lhist 버킷에서 p50/p90/p99를 낸다. 분위수는 해당 버킷의 **상한**으로 보고한다(보수적).
  2ms 미만이면 정밀 버킷(10μs 간격, *_fine_us)을, 그 이상이면 일반 버킷(100μs 간격, 50ms까지)을
  쓴다. 50ms를 넘는 값은 overflow 버킷이라 ">50000"으로 표시한다.
- 같은 창의 schedstat 표와 대조해 유효성 게이트를 판정한다.
    G1   bpftrace @dispatch                     vs schedstat 전환(pcount)   ±5%
    G1b  bpftrace 총 대기(wake+preempt 합계)     vs schedstat 런큐 대기      ±5%
         — "시작점 모름" 배정(추적 창 경계 등)이 대기 시간을 얼마나 놓쳤는지 본다
         — 또는 배정당 차이가 3μs 이내면 통과. bpftrace는 프로브 실행 시간이 대기 구간에 얹혀
           배정마다 약 2μs의 고정 오프셋이 있다(게이트 실측: 합성 워크로드 워커 8, 대기가 1μs뿐인
           lettuce에서 218 대 71ms = 배정당 +2.1μs). 대기가 수 μs인 구간에서는 이 오프셋이 상대
           오차를 수백 %로 부풀리므로 절대 기준을 함께 둔다. 같은 이유로 10μs 미만의 bpftrace
           분위수는 해석하지 않고, 평균은 schedstat 쪽을 기준으로 삼는다.
    G2   bpftrace @out_preempt                  vs schedstat 비자발 전환     ±10%
         — 단 schedstat 쪽이 100건 미만이면 판정하지 않는다("표본 부족"). 게이트에서 lettuce가
           37 대 33건(4건 차이)으로 +12%가 나왔는데, 같은 판별이 tomcat 19,387 대 19,452건에서는
           −0.3%였다 — 판별 오류가 아니라 작은 표본에서 상대 오차가 의미를 잃은 것이다.
  어긋나면 bpftrace 쪽 숫자는 쓰지 않는다(필터·선점 판별이 틀렸다는 뜻이다).
- lettuce 선점자 표(#207): 선점자 그룹 × 그 스레드를 마지막으로 깨운 쪽, 마지막 wakee 일치 여부.
  같은 bpftrace 분기 안에서 센 값끼리라 외부 출처 없이 내부 정합성으로 검증한다.
    G6a  @preemptor 합                         = @out_preempt[lettuce]    3건 또는 0.1% 이내
    G6b  @preempt_waker 합, lastwk+not_lastwk 합 = @preemptor 합           3건 또는 0.1% 이내
         — 원리상 같아야 하지만 창 끝 print()가 비동기라 맵을 읽는 사이 한두 건이 더 세진다
    G6c  idle(pid 0)이 선점자                   = 0건 (선점이면 runnable이 올라와야 한다)
  다중 키(@preempt_waker)의 JSON 키 표기는 버전마다 다를 수 있어 정수만 뽑아 읽는다 — 파싱이 틀리면 G6b가 잡는다.

bpftrace JSON 출력은 버전마다 조금씩 다르다. 줄마다 {"type": ..., "data": {"@map": {key: value}}}
형태를 가정하고, 모르는 형태는 건너뛴다.

사용:  python summarize-sched-latency.py <prefix> [<prefix> ...]
"""
import json
import re
import sys

GROUPS = {"1": "lettuce", "2": "tomcat"}
# trace-sched-latency.bt의 @preemptor 그룹 번호, @preempt_waker의 깨운 쪽 번호와 같아야 한다
PREEMPTOR_GROUPS = {"0": "분류 밖", "1": "lettuce", "2": "tomcat", "3": "gc", "4": "jit",
                    "5": "VM Thread", "6": "커널 스레드", "7": "bpftrace", "9": "idle"}
WAKER_COLS = (1, 2, 8, 3, 0)  # lettuce, tomcat, 인터럽트, 그 밖, 모름 — 표의 열 순서
LHIST_MAPS = ("wake_us", "preempt_us", "run_us")


def load_bpftrace(path):
    maps = {}
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line.startswith("{"):
                continue
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            data = obj.get("data")
            if not isinstance(data, dict):
                continue
            for name, val in data.items():
                # 같은 맵이 두 번 나오면 첫 번째를 쓴다 — trace 스크립트가 창 끝 tick에서 결과를 출력하고
                # END에서는 비운 맵(빈 값)이 다시 나올 수 있다. END에서만 출력하던 예전 결과에도 그대로 맞는다.
                if not name.startswith("@") or name[1:] in maps:
                    continue
                if isinstance(val, dict):
                    maps[name[1:]] = {str(k): v for k, v in val.items()}
                elif isinstance(val, list):
                    # 키 없는 hist/lhist(@preempt_lastwk_gap_us)는 버킷 목록이 바로 온다
                    maps[name[1:]] = val
    return maps


def key_ints(key):
    """다중 키 맵의 키("2,1" 등 버전마다 표기가 조금 다르다)에서 정수만 뽑는다."""
    return tuple(int(x) for x in re.findall(r"-?\d+", key))


FINE_MAX_US = 2000


def percentile_two(coarse, fine, q):
    """정밀 버킷으로 낸 분위수가 2ms 미만이면 그것을, 아니면 일반 버킷 값을 쓴다."""
    v = percentile(fine, q)
    if v != "—" and not v.startswith(">") and int(v) <= FINE_MAX_US and fine_has_total(fine, coarse):
        return v
    return percentile(coarse, q)


def fine_has_total(fine, coarse):
    # 정밀 lhist의 overflow 버킷(>2ms)까지 합쳐야 전체 건수와 같다 — 같지 않으면 쓰지 않는다
    return sum(b.get("count", 0) for b in fine) == sum(b.get("count", 0) for b in coarse)


def percentile(buckets, q):
    """lhist 버킷 목록에서 q 분위수의 버킷 상한(μs, 문자열). overflow 버킷이면 ">50000"."""
    total = sum(b.get("count", 0) for b in buckets)
    if total == 0:
        return "—"
    need = total * q
    acc = 0
    for b in sorted(buckets, key=lambda b: b.get("min", -1)):
        acc += b.get("count", 0)
        if acc >= need:
            if "max" not in b:
                return f">{b.get('min', 50000)}"
            return str(b["max"] + 1)
    return ">50000"


def load_schedstat(path):
    """sample-schedstat.sh 첫 표에서 그룹별 (전환, 비자발, 런큐 대기 ms)를 읽는다."""
    rows = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                cells = [c.strip() for c in line.strip().strip("|").split("|")]
                # 첫 표는 10열이다: 그룹 실행 대기 비율 점유 스레드수 전환 자발 비자발 전환/요청
                if len(cells) == 10 and cells[0] in GROUPS.values() and re.fullmatch(r"\d+", cells[6]):
                    rows.setdefault(cells[0], (int(cells[6]), int(cells[8]), float(cells[2])))
    except FileNotFoundError:
        pass
    return rows


def gate(a, b, tol, min_n=0, abs_ok=0.0):
    if not b:
        return "—"
    if abs_ok and abs(a - b) > abs(b) * tol and abs(a - b) <= abs_ok:
        return f"{(a - b) / b:+.1%} PASS(절대)"
    if b < min_n:
        return f"표본 부족(<{min_n})"
    diff = (a - b) / b
    return f"{diff:+.1%} {'PASS' if abs(diff) <= tol else 'FAIL'}"


def summarize(prefix):
    maps = load_bpftrace(prefix + ".schedlat.json")
    sched = load_schedstat(prefix + ".schedstat.txt")
    print(f"## {prefix}\n")
    print("| 그룹 | 구분 | 건수 | 평균(μs) | p50 | p90 | p99 |")
    print("|---|---|---|---|---|---|---|")
    for key, g in GROUPS.items():
        for kind in LHIST_MAPS:
            st = maps.get(kind.replace("_us", "_stat"), {}).get(key) or {}
            hist = maps.get(kind, {}).get(key) or []
            fine = maps.get(kind.replace("_us", "_fine_us"), {}).get(key) or []
            cnt = st.get("count", sum(b.get("count", 0) for b in hist))
            avg = st.get("average", st.get("avg"))
            pq = [percentile_two(hist, fine, q) for q in (.5, .9, .99)]
            print(f"| {g} | {kind[:-3]} | {cnt} | {avg if avg is not None else '—'} | "
                  f"{pq[0]} | {pq[1]} | {pq[2]} |")
    print()
    print("| 그룹 | 배정(bt) | 전환(schedstat) | G1 | 총 대기 ms(bt) | 런큐 대기 ms(schedstat) | G1b | 선점 내려옴(bt) | 비자발(schedstat) | G2 | 수면 내려옴(bt) | 시작점 모름(bt) |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for key, g in GROUPS.items():
        disp = maps.get("dispatch", {}).get(key, 0)
        pre = maps.get("out_preempt", {}).get(key, 0)
        slp = maps.get("out_sleep", {}).get(key, 0)
        unk = maps.get("dispatch_unknown", {}).get(key, 0)
        sw, nvol, wait_ms = sched.get(g, (0, 0, 0.0))
        bt_wait_ms = sum((maps.get(m, {}).get(key) or {}).get("total", 0) for m in ("wake_stat", "preempt_stat")) / 1000
        print(f"| {g} | {disp} | {sw} | {gate(disp, sw, .05)} | {bt_wait_ms:.0f} | {wait_ms:.0f} | {gate(bt_wait_ms, wait_ms, .05, abs_ok=sw * 3 / 1000)} | "
              f"{pre} | {nvol} | {gate(pre, nvol, .10, 100)} | {slp} | {unk} |")
    print()
    summarize_preemptors(maps)


def exact(a, b):
    return "PASS" if a == b else f"FAIL ({a} ≠ {b})"


def near(a, b):
    """맵끼리의 근사 일치 — 3건 또는 0.1% 이내.

    같은 bpftrace 분기에서 센 값이라 원리상 같아야 하지만, 창 끝의 print()는 비동기라 사용자 공간이
    맵을 하나씩 읽는 몇 ms 사이에 사건이 한두 건 더 세진다(게이트 실측: 16,934 대 16,933).
    파싱·분기 오류라면 차이가 이보다 훨씬 크다(가짜 입력 검증에서 한 칸 누락 = 11건).
    """
    if abs(a - b) <= max(3, abs(b) * 0.001):
        return "PASS" if a == b else f"PASS ({a - b:+d}, 출력 시차)"
    return f"FAIL ({a} ≠ {b})"


def summarize_preemptors(maps):
    """lettuce를 선점한 스레드 — 그룹, 그 스레드를 마지막으로 깨운 쪽, 선점 유발 여부(#207)."""
    if "preemptor" not in maps:
        print("(선점자 맵 없음 — #207 이전 trace 스크립트의 결과다)\n")
        return
    who = {k: v for k, v in maps["preemptor"].items()}
    if not who:
        # 맵은 출력됐지만 비었다 — 창 안에서 lettuce가 한 번도 선점되지 않았다(T8처럼 lettuce가 거의 잠드는 조건).
        # 그 자체는 오류가 아니므로 선점 내려옴도 0인지로 판정한다.
        out_pre = maps.get("out_preempt", {}).get("1", 0)
        print("### lettuce 선점자 (선점 0건)\n")
        print("| 정합성 | 기준 | 판정 |")
        print("|---|---|---|")
        print(f"| G6a 선점자 합 = 선점 내려옴(lettuce) | 3건 또는 0.1% 이내 | {near(0, out_pre)} |")
        print()
        return
    total = sum(who.values())
    waker = {}
    for k, v in maps.get("preempt_waker", {}).items():
        ints = key_ints(k)
        if len(ints) == 2:
            waker[ints] = v
    lastwk = maps.get("preempt_lastwk", {})
    not_lastwk = maps.get("preempt_not_lastwk", {})

    print(f"### lettuce 선점자 (선점 {total}건)\n")
    print("| 선점자 | 건수 | 비율 | ← lettuce가 깨움 | ← tomcat | ← 인터럽트 | ← 그 밖 | ← 모름 | 마지막 wakee 일치 |")
    print("|---|---|---|---|---|---|---|---|---|")
    for key in sorted(who, key=lambda k: -who[k]):
        n = who[key]
        g = int(key)
        cells = [waker.get((g, wg), 0) for wg in WAKER_COLS]
        hit = lastwk.get(key, 0)
        print(f"| {PREEMPTOR_GROUPS.get(key, key)} | {n} | {n / total:.1%} | "
              + " | ".join(f"{c} ({c / n:.0%})" for c in cells)
              + f" | {hit} ({hit / n:.0%}) |")
    print()

    tl = waker.get((2, 1), 0)
    print(f"- **lettuce가 깨운 tomcat 워커에게 밀려난 비율: {tl / total:.1%}** ({tl} / {total})")
    gap = maps.get("preempt_lastwk_gap_us") or []
    gst = maps.get("preempt_lastwk_gap_stat") or {}
    if gap:
        pq = [percentile(gap, q) for q in (.5, .9, .99)]
        avg = gst.get("average", gst.get("avg", "—"))
        print(f"- 마지막 wakee가 선점한 경우 깨우기 → 전환 간격: 평균 {avg}μs, p50 {pq[0]} / p90 {pq[1]} / p99 {pq[2]}μs "
              "(10μs 미만은 프로브 오프셋 범위 — 해석하지 않는다)")
    other = maps.get("preemptor_other_comm", {})
    if other:
        top = sorted(other.items(), key=lambda kv: -kv[1])[:10]
        print("- 분류 밖 상위: " + ", ".join(f"`{c}` {n}" for c, n in top))
    print()

    waker_sum = sum(waker.values())
    lw_sum = sum(lastwk.values()) + sum(not_lastwk.values())
    out_pre = maps.get("out_preempt", {}).get("1", 0)
    print("| 정합성 | 기준 | 판정 |")
    print("|---|---|---|")
    print(f"| G6a 선점자 합 = 선점 내려옴(lettuce) | 3건 또는 0.1% 이내 | {near(total, out_pre)} |")
    print(f"| G6b 깨운 쪽 합 = 선점자 합 | 3건 또는 0.1% 이내 | {near(waker_sum, total)} |")
    print(f"| G6b 마지막 wakee 일치+불일치 = 선점자 합 | 3건 또는 0.1% 이내 | {near(lw_sum, total)} |")
    print(f"| G6c idle이 선점자 | 0건 | {exact(who.get('9', 0), 0)} |")
    print()


if __name__ == "__main__":
    # Windows 콘솔 기본 인코딩(cp949)은 '—'·'μ'를 못 찍는다 — 결과는 개발 머신에서 요약하므로 고정한다
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for p in sys.argv[1:]:
        summarize(p)
