#!/usr/bin/env python3
"""AI 코스 생성 장애 격리 주실험(scripts/k6/ai-bulkhead.js) 회차 하나를 판정한다 (#197).

설계·판정 기준의 정본은 docs/tasks/ai-bulkhead-loadtest/README.md 다. 이 스크립트의 상수는 그 문서의
6-5·8·9절을 옮긴 것이고, 측정을 시작한 뒤에는 바꾸지 않는다.

읽는 것(뒤의 넷은 없어도 돈다 — 없으면 해당 판정을 '자료 없음'으로 둔다):
  --k6-log      k6 stderr. BHRUN·AIREQ·BGREQ 줄
  --k6-summary  k6 --summary-export. dropped_iterations 확인용
  --poll        앱 인스턴스에서 localhost:8081 을 1초마다 긁은 스냅샷(poll-metrics.sh 형식)
  --dumps-dir   10초마다 뜬 스레드 덤프(<epoch초>.txt)
  --journal     회차 구간의 앱 로그(journalctl -o cat)

시각 기준: 구간 경계는 k6 테스트 시작(t0) 기준 ms 다. t0 는 AI 요청의 (송신 시각 − 예정 오프셋) 최솟값으로
추정한다 — setup 과 시나리오 시작 사이의 간격에 기대지 않기 위해서다. 서버 쪽 자료(스냅샷·덤프)는 epoch 초라
t0 로 맞춘다. 두 EC2 의 시계 차이는 NTP 로 ms 단위라 10초 구간 판정에는 영향이 없다.

종료 코드: 0 유효 · 4 무효(다시 잰다)
사용:
  aggregate-bulkhead.py --k6-log r40-A1.log --k6-summary r40-A1.json --poll metrics.prom \\
      --dumps-dir dumps --journal app.log --label r40-A1 --out-json r40-A1.result.json
"""
import argparse
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from aggregate import labels_of, parse_snapshots  # noqa: E402

# ── 판정 기준 (설계 문서 6-5, 8-1) ──────────────────────────────────────────
WINDOW_MS = 10_000
JUDGE_TAIL_MS = 180_000          # 판정 창: AI 도착 구간의 마지막 3분
CONSECUTIVE = 3                  # 10초 구간 3개 = 30초 연속
DEGRADED_FACTOR = 3.0
DEGRADED_FLOOR_MS = 100.0
OUTAGE_P99_MS = 1000.0
OUTAGE_ERROR_RATE = 0.01         # 1분 창
RECOVERY_FACTOR = 1.5
RECOVERY_FLOOR_MS = 100.0

# ── 무효·원인 분리 기준 (설계 문서 9절) ─────────────────────────────────────
MAX_LAG_MS = 1000
SCRAPE_GAP_FLAG_SEC = 10.0
CPU_FLAG = 0.90                  # 1분 평균

# AI 요청이 낼 수 있는 정상 결과. 그 밖(401·403 토큰 만료, 0 연결 실패, 코드 없는 4xx)은 측정 도구 문제다.
# A 는 입장 제한이 없어 429 가 없고, 504 는 ALB idle timeout(코드 없음)도 포함한다 — 과부하의 실제 결과다.
AI_EXPECTED = {201, 429, 502, 503, 504}
# k6 AI 타임아웃(scripts/k6/ai-bulkhead.js AI_TIMEOUT_SEC)에 걸린 요청은 상태 0 으로 남는다. ALB idle timeout 과
# 같은 60초라 어느 쪽이 먼저 끊느냐에 따라 504(코드 없음)나 0 이 된다 — 둘 다 '서버가 60초 안에 답하지 못했다'는
# 같은 과부하 결과다(r40-A1 실측: 0 이 77~78건, 소요 59,991~60,001ms). 그래서 타임아웃 근처에서 끝난 0 만
# 정상 결과로 보고, 그보다 일찍 끝난 0(연결 실패 등)은 그대로 도구 문제로 본다.
AI_CLIENT_TIMEOUT_MS = 60000
AI_CLIENT_TIMEOUT_SLACK_MS = 1000


def is_ai_client_timeout(r):
    return r['status'] == 0 and r['durationMs'] >= AI_CLIENT_TIMEOUT_MS - AI_CLIENT_TIMEOUT_SLACK_MS
# 배경 요청: 200 과 서버 쪽 실패(5xx, 0 = 타임아웃·연결 실패)만 실험 결과다. 404 는 코스 ID 범위가 틀렸다는 뜻이다.
BG_OK = 200

KAKAO_QUOTA_RE = re.compile(r'Kakao search API error\(QUOTA_EXCEEDED\)')
OPENAI_429_RE = re.compile(r'(?i)(openai|llm).*\b429\b|\b429\b.*(openai|llm)')


def read_tagged(path, tag):
    """k6 stderr 의 "<tag> {json}" 줄. k6 가 console.error 를 level=error msg="..." 로 감싸므로 벗긴다."""
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


def p99(values):
    """nearest-rank p99. 표본이 없으면 None."""
    if not values:
        return None
    s = sorted(values)
    return s[max(0, math.ceil(0.99 * len(s)) - 1)]


def is_error(status):
    return status == 0 or status >= 500


# ── 배경 API 구간 지표 ───────────────────────────────────────────────────────

def windows(bg, start_ms, end_ms):
    """[start, end) 를 10초 구간으로 나눠 (구간 시작, p99, 표본 수, 에러 수) 목록을 만든다.
    배경 요청은 '보낸 시각'으로 구간에 넣는다 — 타임아웃 요청도 보낸 순간의 상태를 나타낸다."""
    out = []
    t = start_ms
    while t < end_ms:
        rows = [r for r in bg if t <= r['rel'] < t + WINDOW_MS]
        out.append({
            'startMs': t,
            'p99': p99([r['ms'] for r in rows]),
            'n': len(rows),
            'errors': sum(1 for r in rows if is_error(r['s'])),
        })
        t += WINDOW_MS
    return out


def first_run(flags, length):
    """flags 에서 True 가 length 개 이상 연속하는 첫 위치. 없으면 None."""
    streak = 0
    for i, f in enumerate(flags):
        streak = streak + 1 if f else 0
        if streak >= length:
            return i - length + 1
    return None


def judge(win, base):
    """판정 창(10초 구간 목록)에 대해 저하·장애를 판정한다."""
    degraded_flags = [w['p99'] is not None and w['p99'] > base * DEGRADED_FACTOR
                      and w['p99'] > base + DEGRADED_FLOOR_MS for w in win]
    outage_p99_flags = [w['p99'] is not None and w['p99'] > OUTAGE_P99_MS for w in win]
    # 1분 창 = 10초 구간 6개를 한 칸씩 밀며 본다. 판정 창이 1분보다 짧으면 창 전체를 한 번 본다 —
    # 구간이 모자란다고 검사를 건너뛰면 에러율 기준이 조용히 빠진다(모의 서버 검증에서 발견).
    worst_err, err_hit = 0.0, False
    size = min(6, len(win))
    for i in range(0, len(win) - size + 1):
        chunk = win[i:i + size]
        n = sum(w['n'] for w in chunk)
        if n:
            rate = sum(w['errors'] for w in chunk) / n
            worst_err = max(worst_err, rate)
            err_hit = err_hit or rate > OUTAGE_ERROR_RATE
    deg_at = first_run(degraded_flags, CONSECUTIVE)
    out_at = first_run(outage_p99_flags, CONSECUTIVE)
    return {
        'degraded': deg_at is not None or out_at is not None or err_hit,
        'outage': out_at is not None or err_hit,
        'outageByP99': out_at is not None,
        'outageByErrorRate': err_hit,
        'worstWindowP99Ms': max((w['p99'] for w in win if w['p99'] is not None), default=None),
        'worstMinuteErrorRate': round(worst_err, 4),
    }


def recovery_time(win, base, recovery_start):
    """회복 관측 구간에서 p99 ≤ max(1.5×기준선, 기준선+100ms) 가 30초 연속 시작된 시점(초)."""
    limit = max(base * RECOVERY_FACTOR, base + RECOVERY_FLOOR_MS)
    flags = [w['p99'] is not None and w['p99'] <= limit and w['errors'] == 0 for w in win]
    at = first_run(flags, CONSECUTIVE)
    if at is None:
        return None, limit
    return (win[at]['startMs'] - recovery_start) / 1000, limit


# ── 서버 지표 (8081 스냅샷) ─────────────────────────────────────────────────

def metric_value(snap, name, pred=lambda l: True):
    vals = [v for (n, l), v in snap.items() if n == name and pred(labels_of(l))]
    return sum(vals) if vals else None


def server_metrics(poll_path, t0_epoch, phases):
    snaps = parse_snapshots(poll_path)
    # scrape-failed 스냅샷은 지표가 비어 있다. 실패 연속 시간은 '지표가 담긴 스냅샷 사이 간격'으로 잰다.
    good = [(ts, s) for ts, s in snaps if s]
    rel = [((ts - t0_epoch) * 1000, s) for ts, s in good]

    def in_phase(a, b):
        return [(t, s) for t, s in rel if phases[a] <= t < phases[b]]

    ai = in_phase('aiStartMs', 'recoveryStartMs')
    is_ai_exec = lambda l: l.get('name') == 'aiAgentExecutor'  # noqa: E731

    def series(rows, name, pred=lambda l: True):
        return [(t, v) for t, v in ((t, metric_value(s, name, pred)) for t, s in rows) if v is not None]

    busy = series(ai, 'tomcat_threads_busy_threads')
    max_threads = next((v for _, v in series(rel, 'tomcat_threads_config_max_threads')), None)
    queued = series(ai, 'executor_queued_tasks', is_ai_exec)
    active = series(ai, 'executor_active_threads', is_ai_exec)
    cpu = series(ai, 'system_cpu_usage')

    # 1분 평균 CPU 최댓값(1초 샘플 60개 이동 평균)
    cpu_vals = [v for _, v in cpu]
    cpu_1m = max((sum(cpu_vals[i:i + 60]) / 60 for i in range(0, max(1, len(cpu_vals) - 59))),
                 default=None) if len(cpu_vals) >= 60 else (max(cpu_vals) if cpu_vals else None)

    # 수집 공백: AI 도착 ~ 회복 관측 끝 사이에서 연속 스냅샷 간격의 최댓값
    span = [t for t, _ in rel if phases['aiStartMs'] <= t < phases['endMs']]
    gaps = [(b - a) / 1000 for a, b in zip(span, span[1:])]
    longest_gap = max(gaps, default=None)

    saturated = [t for t, v in busy if max_threads is not None and v >= max_threads]

    # 내부 잔여 작업 소진: 회복 관측 시작 이후 aiAgentExecutor active + queued 가 3샘플 연속 0 이 된 시점
    rec = in_phase('recoveryStartMs', 'endMs')
    drained_at = None
    streak = 0
    for t, s in rec:
        a = metric_value(s, 'executor_active_threads', is_ai_exec) or 0
        q = metric_value(s, 'executor_queued_tasks', is_ai_exec) or 0
        streak = streak + 1 if a + q == 0 else 0
        if streak >= 3:
            drained_at = (t - phases['recoveryStartMs']) / 1000 - 2
            break

    def delta(name, pred):
        """회차 전체(웜업 포함) 카운터 증가분."""
        vals = [metric_value(s, name, pred) for _, s in rel]
        vals = [v for v in vals if v is not None]
        return (vals[-1] - vals[0]) if len(vals) >= 2 else None

    tour_success = delta('ai_candidate_retrieval_total',
                         lambda l: l.get('source') == 'tour_api' and l.get('result') in ('hit', 'empty'))

    return {
        'snapshots': len(good),
        'tomcatMaxThreads': max_threads,
        'tomcatBusyMaxInAi': max((v for _, v in busy), default=None),
        'tomcatSaturatedSecondsInAi': len(saturated),
        'tomcatFirstSaturatedSec': round((saturated[0] - phases['aiStartMs']) / 1000, 1) if saturated else None,
        'aiExecutorQueuedMaxInAi': max((v for _, v in queued), default=None),
        'aiExecutorActiveMaxInAi': max((v for _, v in active), default=None),
        'internalDrainSec': drained_at,
        'cpu1mMaxInAi': round(cpu_1m, 3) if cpu_1m is not None else None,
        'longestScrapeGapSec': round(longest_gap, 1) if longest_gap is not None else None,
        'tourApiSuccessDelta': tour_success,
    }


# ── 스레드 덤프 ─────────────────────────────────────────────────────────────

THREAD_HEAD_RE = re.compile(r'^"([^"]+)"')
# Tomcat 워커 이름은 커넥터 이름을 따른다. server.address 를 지정한 운영은 'http-nio-0.0.0.0-8080-exec-N',
# 지정하지 않은 관리 포트는 'http-nio-8081-exec-N' 이다(운영 덤프로 확인). 주소 부분은 있어도 없어도 맞춘다.
APP_WORKER_RE = re.compile(r'^http-nio-(?:[0-9a-fA-F.:\[\]]+-)?8080-exec-')
MGMT_WORKER_RE = re.compile(r'^http-nio-(?:[0-9a-fA-F.:\[\]]+-)?8081-exec-')
STATE_RE = re.compile(r'java\.lang\.Thread\.State: (\w+)')


def parse_dump(path):
    """스레드 이름 → (상태, 스택 문자열). jcmd Thread.print 와 kill -3 출력 모두 같은 형식이다."""
    threads, name, state, stack = {}, None, None, []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            m = THREAD_HEAD_RE.match(line)
            if m:
                if name is not None:
                    threads[name] = (state, '\n'.join(stack))
                name, state, stack = m.group(1), None, []
                continue
            if name is None:
                continue
            sm = STATE_RE.search(line)
            if sm:
                state = sm.group(1)
            elif line.strip().startswith('at '):
                stack.append(line.strip())
    if name is not None:
        threads[name] = (state, '\n'.join(stack))
    return threads


def dump_series(dumps_dir, t0_epoch, phases):
    rows = []
    for fn in sorted(os.listdir(dumps_dir)):
        if not fn.endswith('.txt'):
            continue
        try:
            ts = float(fn[:-4])
        except ValueError:
            continue
        threads = parse_dump(os.path.join(dumps_dir, fn))
        app_workers = {n: v for n, v in threads.items() if APP_WORKER_RE.match(n)}
        mgmt_workers = [n for n in threads if MGMT_WORKER_RE.match(n)]
        # CallerRuns: 요청 스레드의 스택에 거부 정책 실행과 LLM 호출이 함께 있다.
        caller_runs = [n for n, (_, st) in app_workers.items()
                       if 'CallerRunsPolicy.rejectedExecution' in st and 'OpenAiLlmClient.generate' in st]
        llm_anywhere = [n for n, (_, st) in threads.items() if 'OpenAiLlmClient.generate' in st]
        states = {}
        for _, (st, _) in app_workers.items():
            states[st or 'UNKNOWN'] = states.get(st or 'UNKNOWN', 0) + 1
        rows.append({
            'relMs': (ts - t0_epoch) * 1000,
            'appWorkers': len(app_workers),
            'mgmtWorkers': len(mgmt_workers),
            'callerRuns': len(caller_runs),
            'llmInFlight': len(llm_anywhere),
            'appWorkerStates': states,
        })
    ai = [r for r in rows if phases['aiStartMs'] <= r['relMs'] < phases['recoveryStartMs']]
    rec = [r for r in rows if phases['recoveryStartMs'] <= r['relMs'] < phases['endMs']]
    first_cr = next((r for r in ai if r['callerRuns'] > 0), None)
    llm_drained = next((r for r in rec if r['llmInFlight'] == 0), None)
    runnable_share = None
    if ai:
        total = sum(sum(r['appWorkerStates'].values()) for r in ai)
        runnable = sum(r['appWorkerStates'].get('RUNNABLE', 0) for r in ai)
        runnable_share = round(runnable / total, 3) if total else None
    return {
        'dumps': len(rows),
        'mgmtWorkersMax': max((r['mgmtWorkers'] for r in rows), default=None),
        'callerRunsMaxInAi': max((r['callerRuns'] for r in ai), default=None),
        'callerRunsFirstSec': round((first_cr['relMs'] - phases['aiStartMs']) / 1000, 1) if first_cr else None,
        'appWorkerRunnableShareInAi': runnable_share,
        'llmDrainedSec': round((llm_drained['relMs'] - phases['recoveryStartMs']) / 1000, 1) if llm_drained else None,
    }


# ── 본체 ────────────────────────────────────────────────────────────────────

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser()
    ap.add_argument('--k6-log', required=True)
    ap.add_argument('--k6-summary')
    ap.add_argument('--poll')
    ap.add_argument('--dumps-dir')
    ap.add_argument('--journal')
    ap.add_argument('--label', default='')
    ap.add_argument('--out-json')
    # 도구 검증용(압축한 시간표로 모의 서버를 돌릴 때). 실측에서는 쓰지 않는다 — 판정 창은 설계 문서의 3분이다.
    ap.add_argument('--judge-tail-sec', type=int, default=JUDGE_TAIL_MS // 1000, help=argparse.SUPPRESS)
    args = ap.parse_args()

    run = read_tagged(args.k6_log, 'BHRUN')
    if not run:
        print('!!! 무효: BHRUN 줄이 없다(k6 로그가 아니거나 setup 이 돌지 않았다)')
        sys.exit(4)
    run = run[0]
    phases = run['phases']
    ai = read_tagged(args.k6_log, 'AIREQ')
    bg = read_tagged(args.k6_log, 'BGREQ')

    invalid, flags = [], []

    # t0 추정 — 송신 지연은 0 이상이므로 최솟값이 실제 시작에 가장 가깝다.
    if ai:
        t0 = min(r['sentAtMs'] - r['offsetMs'] for r in ai)
    else:
        t0 = run['setupEpochMs']
        flags.append('AI 요청이 없어 t0 를 setup 시각으로 대신했다')
    for r in ai:
        r['lagMs'] = r['sentAtMs'] - t0 - r['offsetMs']
    for r in bg:
        r['rel'] = r['t'] - t0

    # ── 무효 조건 (9-1) ──
    late = [r for r in ai if r['lagMs'] > MAX_LAG_MS]
    if late:
        invalid.append(f'AI 출발 지연 1초 초과 {len(late)}건(최대 {max(r["lagMs"] for r in late)}ms)')
    odd_ai = [r for r in ai if r['status'] not in AI_EXPECTED and not is_ai_client_timeout(r)]
    if odd_ai:
        codes = sorted({r['status'] for r in odd_ai})
        invalid.append(f'AI 요청의 예상 밖 응답 {len(odd_ai)}건 {codes} — 토큰 만료·연결 실패 등 도구 문제')
    odd_bg = [r for r in bg if r['s'] != BG_OK and not is_error(r['s'])]
    if odd_bg:
        codes = sorted({r['s'] for r in odd_bg})
        invalid.append(f'배경 요청의 예상 밖 응답 {len(odd_bg)}건 {codes} — 404 면 코스 ID 범위가 틀렸다')
    if args.k6_summary:
        with open(args.k6_summary, encoding='utf-8') as f:
            summary = json.load(f)
        dropped = sum(v.get('count', 0) for k, v in summary.get('metrics', {}).items()
                      if k.startswith('dropped_iterations'))
        # 태그별 하위 지표와 전체 지표가 함께 있어 중복 합산된다. 0 인지 아닌지만 본다.
        if dropped:
            invalid.append('k6 가 배경 요청을 제때 보내지 못했다(dropped_iterations > 0) — k6 VU 부족')
    journal_lines = []
    if args.journal:
        with open(args.journal, encoding='utf-8', errors='replace') as f:
            journal_lines = f.readlines()
        kakao = sum(1 for l in journal_lines if KAKAO_QUOTA_RE.search(l))
        if kakao:
            invalid.append(f'카카오 한도 소진 로그 {kakao}건')
        openai = [l.strip() for l in journal_lines if OPENAI_429_RE.search(l)]
        if openai:
            flags.append(f'OpenAI 429 의심 로그 {len(openai)}줄 — 수동 확인 필요(첫 줄: {openai[0][:160]})')

    # ── 배경 API ──
    base_rows = [r['ms'] for r in bg if phases['baselineStartMs'] <= r['rel'] < phases['aiStartMs']]
    base = p99(base_rows)
    if base is None:
        print('!!! 무효: 기준선 구간에 배경 요청이 없다')
        sys.exit(4)
    judge_start = phases['recoveryStartMs'] - args.judge_tail_sec * 1000
    judged = judge(windows(bg, judge_start, phases['recoveryStartMs']), base)
    # 시점 기록용: AI 도착 구간 전체에서 저하·장애 조건이 처음 시작된 시각
    ai_win = windows(bg, phases['aiStartMs'], phases['recoveryStartMs'])
    first_deg = first_run([w['p99'] is not None and w['p99'] > base * DEGRADED_FACTOR
                           and w['p99'] > base + DEGRADED_FLOOR_MS for w in ai_win], CONSECUTIVE)
    first_out = first_run([w['p99'] is not None and w['p99'] > OUTAGE_P99_MS for w in ai_win], CONSECUTIVE)
    rec_win = windows(bg, phases['recoveryStartMs'], phases['endMs'])
    rec_sec, rec_limit = recovery_time(rec_win, base, phases['recoveryStartMs'])

    per_api = {}
    for api in ('popular', 'detail'):
        rows = [r for r in bg if r['api'] == api]
        b = [r['ms'] for r in rows if phases['baselineStartMs'] <= r['rel'] < phases['aiStartMs']]
        j = [r['ms'] for r in rows if judge_start <= r['rel'] < phases['recoveryStartMs']]
        per_api[api] = {'baselineP99Ms': p99(b), 'judgeP99Ms': p99(j),
                        'judgeErrors': sum(1 for r in rows
                                           if judge_start <= r['rel'] < phases['recoveryStartMs'] and is_error(r['s']))}

    # 보고용: 판정 창 요청을 모두 모은 p99. 판정에는 쓰지 않는다 — '최악 10초 p99'는 GC 한 번에도 튀어
    # 구간 대표값으로 읽으면 B 가 실제보다 나빠 보인다(r40-B1: 최악 창 126ms, 판정 창 전체 29ms).
    judge_all_p99 = p99([r['ms'] for r in bg if judge_start <= r['rel'] < phases['recoveryStartMs']])

    # ── AI 요청 ──
    main_ai = [r for r in ai if r['phase'] == 'main']
    statuses = {}
    for r in main_ai:
        if is_ai_client_timeout(r):
            key = '0/CLIENT_TIMEOUT_60S'
        else:
            key = f"{r['status']}{'/' + r['errorCode'] if r['errorCode'] else ''}"
        statuses[key] = statuses.get(key, 0) + 1
    ok = sorted(r['durationMs'] for r in main_ai if r['status'] == 201)

    result = {
        'label': args.label or run.get('label', ''),
        'ratePerMin': run['ratePerMin'],
        'seed': run['seed'],
        'background': {
            'baselineP99Ms': base,
            'requests': len(bg),
            'perApi': per_api,
            'judgeP99Ms': judge_all_p99,
            **judged,
            'firstDegradedSecInAi': ai_win[first_deg]['startMs'] / 1000 - phases['aiStartMs'] / 1000
            if first_deg is not None else None,
            'firstOutageSecInAi': ai_win[first_out]['startMs'] / 1000 - phases['aiStartMs'] / 1000
            if first_out is not None else None,
            'recoverySec': rec_sec,
            'recoveryLimitMs': rec_limit,
        },
        'ai': {
            'mainArrivals': len(main_ai),
            'statuses': statuses,
            'okP50Ms': ok[len(ok) // 2] if ok else None,
            'okMaxMs': ok[-1] if ok else None,
            'maxLagMs': max((r['lagMs'] for r in ai), default=None),
        },
    }

    if args.poll:
        sm = server_metrics(args.poll, t0 / 1000, phases)
        result['server'] = sm
        if sm['tourApiSuccessDelta']:
            invalid.append(f'TourAPI 성공 호출 {sm["tourApiSuccessDelta"]:.0f}건 — 생략이 적용되지 않았다')
        if sm['longestScrapeGapSec'] is not None and sm['longestScrapeGapSec'] >= SCRAPE_GAP_FLAG_SEC:
            flags.append(f'원인 분리 필요: 8081 수집 공백 최대 {sm["longestScrapeGapSec"]}초 → J3 보류, 덤프로 확인')
        if sm['cpu1mMaxInAi'] is not None and sm['cpu1mMaxInAi'] > CPU_FLAG:
            flags.append(f'원인 분리 필요: CPU 1분 평균 최대 {sm["cpu1mMaxInAi"]:.0%} → J3 보류, 덤프 상태로 확인')
    if args.dumps_dir:
        result['dumps'] = dump_series(args.dumps_dir, t0 / 1000, phases)

    result['invalid'] = invalid
    result['flags'] = flags
    print_report(result)
    if args.out_json:
        with open(args.out_json, 'w', encoding='utf-8') as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
    sys.exit(4 if invalid else 0)


def fmt(v, unit=''):
    return '-' if v is None else f'{v:.0f}{unit}' if isinstance(v, float) else f'{v}{unit}'


def print_report(r):
    b = r['background']
    print(f"== {r['label']} (분당 {r['ratePerMin']}건, seed {r['seed']})")
    verdict = '장애' if b['outage'] else '저하' if b['degraded'] else '정상'
    print(f"배경 API 판정: {verdict} — 기준선 p99 {fmt(b['baselineP99Ms'], 'ms')}, "
          f"판정 창 전체 p99 {fmt(b.get('judgeP99Ms'), 'ms')}, "
          f"판정 창 최악 10초 p99 {fmt(b['worstWindowP99Ms'], 'ms')}, "
          f"최악 1분 에러율 {b['worstMinuteErrorRate']:.2%}")
    print(f"  처음 저하 {fmt(b['firstDegradedSecInAi'], '초')} · 처음 장애 {fmt(b['firstOutageSecInAi'], '초')}"
          f" (AI 도착 시작 기준) · 서비스 회복 {fmt(b['recoverySec'], '초') if b['recoverySec'] is not None else '회복 안 됨'}"
          f" (한계 {b['recoveryLimitMs']:.0f}ms)")
    for api, v in b['perApi'].items():
        print(f"  {api}: 기준선 p99 {fmt(v['baselineP99Ms'], 'ms')} → 판정 창 p99 {fmt(v['judgeP99Ms'], 'ms')}, "
              f"에러 {v['judgeErrors']}")
    a = r['ai']
    print(f"AI 요청 {a['mainArrivals']}건 {a['statuses']} · 201 p50 {fmt(a['okP50Ms'], 'ms')} "
          f"최대 {fmt(a['okMaxMs'], 'ms')} · 최대 출발 지연 {fmt(a['maxLagMs'], 'ms')}")
    if 'server' in r:
        s = r['server']
        print(f"Tomcat busy 최대 {fmt(s['tomcatBusyMaxInAi'])}/{fmt(s['tomcatMaxThreads'])}, "
              f"포화 {s['tomcatSaturatedSecondsInAi']}초(처음 {fmt(s['tomcatFirstSaturatedSec'], '초')}) · "
              f"aiAgentExecutor 큐 최대 {fmt(s['aiExecutorQueuedMaxInAi'])} 활성 최대 {fmt(s['aiExecutorActiveMaxInAi'])} · "
              f"내부 잔여 소진 {fmt(s['internalDrainSec'], '초')} · CPU 1분 최대 "
              f"{'-' if s['cpu1mMaxInAi'] is None else format(s['cpu1mMaxInAi'], '.0%')} · "
              f"수집 공백 최대 {fmt(s['longestScrapeGapSec'], '초')}")
    if 'dumps' in r:
        d = r['dumps']
        print(f"스레드 덤프 {d['dumps']}개 · CallerRuns 최대 {fmt(d['callerRunsMaxInAi'])}"
              f"(처음 {fmt(d['callerRunsFirstSec'], '초')}) · 앱 워커 RUNNABLE 비율 {fmt(d['appWorkerRunnableShareInAi'])}"
              f" · LLM 호출 소진 {fmt(d['llmDrainedSec'], '초')} · 관리 워커 최대 {fmt(d['mgmtWorkersMax'])}")
    for f in r['flags']:
        print(f'  ※ {f}')
    for i in r['invalid']:
        print(f'!!! 무효: {i}')


if __name__ == '__main__':
    main()
