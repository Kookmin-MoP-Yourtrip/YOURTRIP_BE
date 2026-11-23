"""인증 메일 발송 측정(#221) — 회차 하나를 집계하고 설계 4절의 기준으로 판정한다.

설계: docs/tasks/mail-async/measurement-design.md

입력
  <회차>.k6.log        mail-bulkhead.js 의 MAILRUN / REQ / TOXIC 줄 (필수)
  --metrics <파일>     poll-metrics.sh 1초 스냅샷 (선택 — 워커·커넥션·메일 지표)
  --summary <파일>     k6 --summary-export (선택 — dropped_iterations)

시각 기준: t0 = MAILRUN.runStartMs. 요청은 보낸 시각으로 10초 창에 넣는다(타임아웃도 보낸 창의 에러다).
백분위수는 최근접 순위 방식이다(벌크헤드 집계기와 같다).

사용:  python aggregate-mail.py A-L3.k6.log --metrics A-L3.metrics.txt [--summary A-L3.summary.json] [--windows]
"""
import argparse
import json
import math
import re
from collections import defaultdict

LINE_RE = re.compile(r'msg="(MAILRUN|REQ|TOXIC) (.*)" source=')
WINDOW_MS = 10_000
PROBES = ('popular', 'mine')
OUTAGE_P99_MS = 1000
OUTAGE_ERROR_RATE = 0.01
RUN_LEN = 3            # 30초 연속 = 10초 창 3개
ERROR_RATE_WINDOWS = 6  # 1분 창 = 10초 창 6개
JUDGE_SEC = 180         # 판정 창 = 메일 구간 마지막 180초


def percentile(values, p):
    if not values:
        return None
    s = sorted(values)
    return s[max(0, math.ceil(p / 100 * len(s)) - 1)]


def load_k6(path):
    run, reqs, toxic = None, [], None
    with open(path, encoding='utf-8') as f:
        for line in f:
            m = LINE_RE.search(line)
            if not m:
                continue
            d = json.loads(m.group(2).replace('\\"', '"'))
            if m.group(1) == 'MAILRUN':
                run = d
            elif m.group(1) == 'REQ':
                reqs.append(d)
            else:
                toxic = d
    if run is None:
        raise SystemExit(f'{path}: MAILRUN 줄이 없다 — --log-output=file 로 받았는지 확인')
    return run, reqs, toxic


def is_error(r):
    return r['s'] == 0 or r['s'] >= 500


def windows_of(reqs, t0, start_ms, end_ms):
    """[start, end) 를 10초 창으로 나눠 (창 시작 초, [요청...]) 목록을 돌려준다."""
    buckets = defaultdict(list)
    for r in reqs:
        rel = r['t'] - t0
        if start_ms <= rel < end_ms:
            buckets[(rel - start_ms) // WINDOW_MS].append(r)
    n = math.ceil((end_ms - start_ms) / WINDOW_MS)
    return [((start_ms + i * WINDOW_MS) / 1000, buckets.get(i, [])) for i in range(n)]


def window_stats(rs):
    ms = [r['ms'] for r in rs]
    return {
        'n': len(rs), 'err': sum(1 for r in rs if is_error(r)),
        'p50': percentile(ms, 50), 'p95': percentile(ms, 95), 'p99': percentile(ms, 99),
    }


def has_run(flags, length=RUN_LEN):
    run = 0
    for ok in flags:
        run = run + 1 if ok else 0
        if run >= length:
            return True
    return False


def judge_probe(base_p99, judge_windows):
    stats = [window_stats(rs) for _, rs in judge_windows]
    p99s = [s['p99'] or 0 for s in stats]
    degraded = has_run([p > base_p99 * 3 and p > base_p99 + 100 for p in p99s])
    slow = has_run([p > OUTAGE_P99_MS for p in p99s])
    worst_rate = 0.0
    for i in range(len(stats) - ERROR_RATE_WINDOWS + 1):
        chunk = stats[i:i + ERROR_RATE_WINDOWS]
        n = sum(s['n'] for s in chunk)
        if n:
            worst_rate = max(worst_rate, sum(s['err'] for s in chunk) / n)
    outage = slow or worst_rate > OUTAGE_ERROR_RATE
    verdict = '장애' if outage else ('저하' if degraded else '정상')
    all_ms = [r['ms'] for _, rs in judge_windows for r in rs]
    all_err = sum(1 for _, rs in judge_windows for r in rs if is_error(r))
    return {
        'verdict': verdict, 'p99_all': percentile(all_ms, 99), 'p99_worst10s': max(p99s) if p99s else None,
        'err_rate_1m_max': worst_rate, 'errors': all_err, 'n': len(all_ms),
    }


def recovery_sec(base_p99, windows_after, from_sec):
    """from_sec 이후 창에서 p99 ≤ max(1.5×기준선, 기준선+100) 이 30초 연속 시작하는 시점까지의 초."""
    limit = max(base_p99 * 1.5, base_p99 + 100)
    flags = [(window_stats(rs)['p99'] or 0) <= limit and rs for _, rs in windows_after]
    for i in range(len(flags) - RUN_LEN + 1):
        if all(flags[i:i + RUN_LEN]):
            return windows_after[i][0] - from_sec
    return None


# ── 서버 지표 ────────────────────────────────────────────────────────────────
SAMPLE_RE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)')
WANTED = (
    'tomcat_threads_busy_threads', 'hikaricp_connections_active', 'hikaricp_connections_pending',
    'hikaricp_connections_timeout_total', 'hikaricp_connections_usage_seconds_count',
    'mail_send_total', 'mail_send_attempt_seconds_count', 'mail_send_attempt_seconds_bucket',
    'system_cpu_usage', 'process_cpu_usage',
)


def load_metrics(path):
    snaps = []
    cur = None
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.startswith('# ts='):
                cur = {'ts': float(line[5:].strip()), 'v': {}, 'ok': True}
                snaps.append(cur)
                continue
            if cur is None:
                continue
            if line.startswith('# scrape-failed'):
                cur['ok'] = False
                continue
            if line.startswith('#'):
                continue
            m = SAMPLE_RE.match(line)
            if m and m.group(1) in WANTED:
                cur['v'][m.group(1) + (m.group(2) or '')] = float(m.group(3))
    return [s for s in snaps if s['ok'] and s['v']]


def series(snap, name, contains=''):
    return sum(v for k, v in snap['v'].items() if k.split('{')[0] == name and contains in k)


def metrics_summary(snaps, t0_sec, from_sec, to_sec):
    win = [s for s in snaps if from_sec <= s['ts'] - t0_sec < to_sec]
    # 수집 공백은 구간 양 끝까지 포함해 잰다 — 구간 안 스냅샷 사이만 보면 앞뒤가 통째로 빈 경우를 놓친다.
    edges = [from_sec] + [s['ts'] - t0_sec for s in win] + [to_sec]
    gap = max(b - a for a, b in zip(edges, edges[1:]))
    if len(win) < 2:
        return {'insufficient': True, 'scrape_gap_max_sec': gap}
    first, last = win[0], win[-1]

    def mx(name, contains=''):
        return max(series(s, name, contains) for s in win)

    def delta(name, contains=''):
        return series(last, name, contains) - series(first, name, contains)

    # SMTP 시도 지연 p50 — 누적 버킷의 창 Δ에서 구한다(성공·실패 합산).
    buckets = defaultdict(float)
    for k in last['v']:
        if k.startswith('mail_send_attempt_seconds_bucket'):
            le = re.search(r'le="([^"]+)"', k).group(1)
            buckets[float('inf') if le == '+Inf' else float(le)] += last['v'][k] - first['v'].get(k, 0)
    total = buckets.get(float('inf'), 0)
    attempt_p50 = None
    if total > 0:
        for le in sorted(buckets):
            if buckets[le] >= total / 2:
                attempt_p50 = le
                break
    # 무효 조건은 순간값이 아니라 1분 평균이다(설계 4-3절) — 1초 스냅샷 60개의 이동 평균 최댓값.
    cpu = [series(s, 'system_cpu_usage') for s in win]
    k = min(60, len(cpu))
    cpu_1m = max(sum(cpu[i:i + k]) / k for i in range(len(cpu) - k + 1))
    return {
        'tomcat_busy_max': mx('tomcat_threads_busy_threads'),
        'hikari_active_max': mx('hikaricp_connections_active'),
        'hikari_pending_max': mx('hikaricp_connections_pending'),
        'hikari_timeouts': delta('hikaricp_connections_timeout_total'),
        'hikari_borrows': delta('hikaricp_connections_usage_seconds_count'),
        'attempts': delta('mail_send_attempt_seconds_count'),
        'attempt_p50_le_sec': attempt_p50,
        'mail_sent': delta('mail_send_total', 'sent'),
        'mail_gave_up': delta('mail_send_total', 'gave_up'),
        'cpu_system_1m_max': cpu_1m,
        'scrape_gap_max_sec': gap,
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('k6log')
    ap.add_argument('--metrics')
    ap.add_argument('--summary')
    ap.add_argument('--windows', action='store_true', help='탐침별 10초 창을 모두 출력')
    a = ap.parse_args()

    run, reqs, toxic = load_k6(a.k6log)
    t0 = run['runStartMs']
    warm = run['warmupSec'] * 1000
    base_end = warm + run['baselineSec'] * 1000
    mail_end = base_end + run['mailSec'] * 1000
    judge_start = mail_end - JUDGE_SEC * 1000
    bg_end = mail_end + (run['recoverySec'] + run['postClearSec']) * 1000

    print(f"# {run['label']} — 메일 분당 {run['mailPerMin']}, 무응답 해제 {'있음' if run['clearAfter'] else '없음'}")
    invalid = []
    cpu_note = None

    by_kind = defaultdict(list)
    for r in reqs:
        by_kind[r['k']].append(r)

    # 무효: 예상 밖 응답
    for kind in PROBES:
        odd = [r for r in by_kind[kind] if r['s'] not in (200,) and not is_error(r)]
        if odd:
            invalid.append(f"{kind} 예상 밖 응답 {len(odd)}건 (예: {odd[0]['s']})")
    odd = [r for r in by_kind['mail'] if r['s'] not in (200, 503) and not is_error(r)]
    if odd:
        invalid.append(f"mail 예상 밖 응답 {len(odd)}건 (예: {odd[0]['s']})")

    print('\n## 탐침 판정 (판정 창 = 메일 구간 마지막 180초)\n')
    print('| 탐침 | 기준선 p99 | 판정 | 판정 창 p99 | 최악 10초 p99 | 1분 에러율 최대 | 회복(도착 종료 기준) | 회복(독성 해제 기준) |')
    print('|---|---|---|---|---|---|---|---|')
    # 무응답 회차의 독성 해제는 실제로 성공했을 때만 해제 시각으로 쓴다. 실패·누락은 회차 조건 위반이다.
    clear_ok = toxic is not None and toxic.get('s') in (200, 204)
    if run['clearAfter'] and not clear_ok:
        invalid.append('독성 해제 실패' if toxic else '독성 해제 기록(TOXIC) 없음')
    clear_sec = (toxic['t'] - t0) / 1000 if clear_ok else None
    for kind in PROBES:
        rs = by_kind[kind]
        base = [r['ms'] for r in rs if warm <= r['t'] - t0 < base_end]
        base_p99 = percentile(base, 99) or 0
        # 표본이 없는 창은 p99가 0으로 계산돼 "정상"으로 읽힌다 — k6가 중간에 멈춘 회차를 정상으로 판정하지 않게 막는다.
        if not base:
            invalid.append(f'{kind} 기준선 표본 없음')
        jw = windows_of(rs, t0, judge_start, mail_end)
        empty = sum(1 for _, rs in jw if not rs)
        if empty:
            invalid.append(f'{kind} 판정 창의 빈 10초 창 {empty}개 (판정 불가)')
        j = judge_probe(base_p99, jw)
        after = windows_of(rs, t0, mail_end, bg_end)
        rec = recovery_sec(base_p99, after, mail_end / 1000)
        rec_clear = None
        if clear_sec is not None:
            after_clear = windows_of(rs, t0, int(clear_sec * 1000), bg_end)
            rec_clear = recovery_sec(base_p99, after_clear, clear_sec)
        fmt = lambda v: '회복 없음' if v is None else f'{v:.0f}초'
        print(f"| {kind} | {base_p99}ms | **{j['verdict']}** | {j['p99_all']}ms | {j['p99_worst10s']}ms | "
              f"{j['err_rate_1m_max']:.1%} | {fmt(rec)} | {fmt(rec_clear) if clear_sec is not None else '—'} |")

    mail = [r for r in by_kind['mail']]
    codes = defaultdict(int)
    for r in mail:
        codes['timeout' if r['s'] == 0 else r['s']] += 1
    ok = [r['ms'] for r in mail if r['s'] == 200]
    print('\n## 메일 요청\n')
    print(f"- 보낸 수 {len(mail)}, 응답 {dict(codes)}")
    if ok:
        print(f"- 200 응답 p50 {percentile(ok, 50)}ms · p99 {percentile(ok, 99)}ms")
    busy = [r['ms'] for r in mail if r['s'] == 503]
    if busy:
        no_ra = sum(1 for r in mail if r['s'] == 503 and not r.get('ra'))
        print(f"- 503 응답 p50 {percentile(busy, 50)}ms · p99 {percentile(busy, 99)}ms · Retry-After 없는 503 {no_ra}건")
    jm = [r for r in mail if judge_start <= r['t'] - t0 < mail_end]
    if jm:
        print(f"- 판정 창 503 비율 {sum(1 for r in jm if r['s'] == 503) / len(jm):.1%} ({len(jm)}건 중)")

    if a.metrics:
        snaps = load_metrics(a.metrics)
        t0s = t0 / 1000
        print('\n## 서버 지표\n')
        print('| 구간 | 워커 최대 | 커넥션 active 최대 | pending 최대 | 커넥션 대기 타임아웃 | SMTP 시도 수 | 시도 p50(버킷 상한) | sent | gave_up | CPU 1분 평균 최대 | 수집 공백 최대 |')
        print('|---|---|---|---|---|---|---|---|---|---|---|')
        spans = [('기준선', warm / 1000, base_end / 1000), ('메일 전체', base_end / 1000, mail_end / 1000),
                 ('판정 창', judge_start / 1000, mail_end / 1000), ('회복 관찰', mail_end / 1000, bg_end / 1000)]
        for name, f, t in spans:
            m = metrics_summary(snaps, t0s, f, t)
            if m.get('insufficient'):
                print(f'| {name} | 스냅샷 2개 미만 |||||||||| ')
                if name in ('메일 전체', '판정 창'):
                    invalid.append(f"{name} 지표 스냅샷 2개 미만 (수집 공백 {m['scrape_gap_max_sec']:.1f}초)")
                continue
            p50 = '—' if m['attempt_p50_le_sec'] is None else f"≤{m['attempt_p50_le_sec']:.2f}s"
            print(f"| {name} | {m['tomcat_busy_max']:.0f} | {m['hikari_active_max']:.0f} | {m['hikari_pending_max']:.0f} | "
                  f"{m['hikari_timeouts']:.0f} | {m['attempts']:.0f} | {p50} | {m['mail_sent']:.0f} | {m['mail_gave_up']:.0f} | "
                  f"{m['cpu_system_1m_max']:.2f} | {m['scrape_gap_max_sec']:.1f}s |")
            if name == '판정 창' and m['cpu_system_1m_max'] > 0.8:
                cpu_note = f"CPU 교란 — 판정 창 1분 평균 {m['cpu_system_1m_max']:.2f} > 0.8 (버리지 않고 표시만)"
            if name == '메일 전체' and m['scrape_gap_max_sec'] and m['scrape_gap_max_sec'] > 5:
                invalid.append(f"지표 수집 공백 {m['scrape_gap_max_sec']:.1f}초 > 5초")

    if a.summary:
        with open(a.summary, encoding='utf-8') as f:
            s = json.load(f)
        dropped = s.get('metrics', {}).get('dropped_iterations', {}).get('count', 0)
        if dropped:
            invalid.append(f'dropped_iterations {dropped}')

    print('\n## 무효 조건\n')
    print('- ' + ('\n- '.join(invalid) if invalid else '해당 없음'))

    if a.windows:
        for kind in PROBES:
            print(f'\n## {kind} 10초 창 (초는 메일 도착 시작 기준)\n')
            print('| 초 | n | 에러 | p50 | p95 | p99 |')
            print('|---|---|---|---|---|---|')
            for start, rs in windows_of(by_kind[kind], t0, warm, bg_end):
                s = window_stats(rs)
                print(f"| {start - base_end / 1000:.0f} | {s['n']} | {s['err']} | {s['p50']} | {s['p95']} | {s['p99']} |")


if __name__ == '__main__':
    main()
