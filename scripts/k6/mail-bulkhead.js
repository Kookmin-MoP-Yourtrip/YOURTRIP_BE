// 인증 메일 발송의 장애 전파 측정 (#221) — SMTP가 느리거나 응답하지 않을 때, 메일과 무관한 API가 함께 무너지는가.
//
// 설계·예측·판정 기준: docs/tasks/mail-async/measurement-design.md
//
// 배경 탐침 두 개를 따로 본다(설계 2-3절).
//   popular  GET /api/upload-courses/popular — 캐시 히트라 커넥션 0. 워커 고갈만 드러낸다
//   mine     GET /api/my-courses             — 요청마다 커넥션. 커넥션 고갈을 드러낸다
// 메일은 요청마다 새 주소로 POST /api/users/email/send 를 일정 간격으로 보낸다(설계 2-3절).
//
// 시간표(설계 2-4절):  웜업 WARMUP_SEC → 기준선 BASELINE_SEC → 메일 MAIL_SEC → 회복 RECOVERY_SEC
//                      → (CLEAR_AFTER=1 일 때) 독성 해제 후 POST_CLEAR_SEC
// 독성은 회차 전에 mail-toxic.sh 로 넣는다. 무응답 회차는 CLEAR_AFTER=1 로 회복 구간 끝에 k6 가 직접 뺀다 —
// 사람이 손으로 빼면 해제 시각이 회차마다 달라진다.
//
// 요청마다 한 줄씩 남긴다(집계는 aggregate-mail.py). --log-output=file=<회차>.k6.log 로 받는다.
//   MAILRUN {설정}                 회차 시작 시 한 번
//   REQ {"k":종류,"t":보낸시각ms,"ms":지연,"s":상태,"e":에러코드[,"ra":Retry-After]}   k = popular | mine | mail
//       ra 는 503 응답에만 붙는다(개선 후 판정 M5 — 거절에 재시도 간격 힌트가 실리는지).
//   TOXIC {"t":시각ms,"s":상태}    독성 해제 시점
//
// 사용:
//   k6 run -e JWT="$TOKEN" -e LABEL=A-L3 -e MAIL_PER_MIN=144 \
//     --log-output=file=A-L3.k6.log scripts/k6/mail-bulkhead.js
import http from 'k6/http';
import exec from 'k6/execution';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOXIPROXY_URL = __ENV.TOXIPROXY_URL || 'http://localhost:8474';
const JWT = __ENV.JWT || '';
const LABEL = (__ENV.LABEL || 'run').toLowerCase().replace(/[^a-z0-9-]/g, '-');

// 메일 도착률은 분당 건수(정수)로 받는다. constant-arrival-rate 의 rate 는 정수라 초당 1.6건 같은 값을 못 쓴다.
const MAIL_PER_MIN = parseInt(__ENV.MAIL_PER_MIN || '60', 10);
const BG_RPS = parseInt(__ENV.BG_RPS || '10', 10); // 탐침마다

const WARMUP_SEC = parseInt(__ENV.WARMUP_SEC || '30', 10);
const BASELINE_SEC = parseInt(__ENV.BASELINE_SEC || '60', 10);
const MAIL_SEC = parseInt(__ENV.MAIL_SEC || '300', 10);
const RECOVERY_SEC = parseInt(__ENV.RECOVERY_SEC || '120', 10);
const CLEAR_AFTER = __ENV.CLEAR_AFTER === '1';
const POST_CLEAR_SEC = parseInt(__ENV.POST_CLEAR_SEC || '90', 10);

// 배경은 벌크헤드 실험과 같은 10초. 메일은 FE OkHttp readTimeout 과 같은 60초다.
const BG_TIMEOUT_SEC = parseInt(__ENV.BG_TIMEOUT_SEC || '10', 10);
const MAIL_TIMEOUT_SEC = parseInt(__ENV.MAIL_TIMEOUT_SEC || '60', 10);

const MOOD_THEMES = ['HEALING', 'ACTIVITY', 'FOOD', 'SENSIBILITY', 'CULTURE', 'NATURE', 'SHOPPING'];

if (!JWT) {
  throw new Error('GET /api/my-courses 는 인증이 필요하다 — -e JWT=... 를 넘겨라');
}
if (!(MAIL_PER_MIN > 0)) {
  throw new Error(`MAIL_PER_MIN 은 양의 정수여야 한다: ${__ENV.MAIL_PER_MIN}`);
}

const MAIL_START_SEC = WARMUP_SEC + BASELINE_SEC;
const BG_SEC = MAIL_START_SEC + MAIL_SEC + RECOVERY_SEC + (CLEAR_AFTER ? POST_CLEAR_SEC : 0);

// 열린 모델의 VU 는 "도착률 × 최악 응답 시간"만큼 필요하다. 모자라면 dropped_iterations 가 생겨 회차가 무효다(설계 4-3절).
function vusFor(perSec, timeoutSec) {
  return Math.ceil(perSec * timeoutSec) + 20;
}

const scenarios = {
  popular: {
    executor: 'constant-arrival-rate', exec: 'popular',
    rate: BG_RPS, timeUnit: '1s', duration: `${BG_SEC}s`,
    preAllocatedVUs: vusFor(BG_RPS, BG_TIMEOUT_SEC), maxVUs: vusFor(BG_RPS, BG_TIMEOUT_SEC),
  },
  mine: {
    executor: 'constant-arrival-rate', exec: 'mine',
    rate: BG_RPS, timeUnit: '1s', duration: `${BG_SEC}s`,
    preAllocatedVUs: vusFor(BG_RPS, BG_TIMEOUT_SEC), maxVUs: vusFor(BG_RPS, BG_TIMEOUT_SEC),
  },
  mail: {
    executor: 'constant-arrival-rate', exec: 'mail',
    rate: MAIL_PER_MIN, timeUnit: '1m', duration: `${MAIL_SEC}s`, startTime: `${MAIL_START_SEC}s`,
    preAllocatedVUs: vusFor(MAIL_PER_MIN / 60, MAIL_TIMEOUT_SEC),
    maxVUs: vusFor(MAIL_PER_MIN / 60, MAIL_TIMEOUT_SEC),
    // 도착 마지막 무렵에 보낸 요청도 끝까지 받아 기록한다. 회복 구간(120초)이 이보다 길어 시간표는 밀리지 않는다.
    gracefulStop: `${MAIL_TIMEOUT_SEC + 5}s`,
  },
};

if (CLEAR_AFTER) {
  scenarios.clearToxic = {
    executor: 'shared-iterations', exec: 'clearToxic', vus: 1, iterations: 1,
    startTime: `${MAIL_START_SEC + MAIL_SEC + RECOVERY_SEC}s`,
  };
}

export const options = {
  scenarios,
  // 요청 로그로 집계하므로 k6 요약의 백분위는 참고용이다.
  summaryTrendStats: ['p(50)', 'p(95)', 'p(99)', 'max', 'count'],
};

export function setup() {
  const runStartMs = Date.now();
  console.log('MAILRUN ' + JSON.stringify({
    label: LABEL, baseUrl: BASE_URL, mailPerMin: MAIL_PER_MIN, bgRps: BG_RPS,
    warmupSec: WARMUP_SEC, baselineSec: BASELINE_SEC, mailSec: MAIL_SEC, recoverySec: RECOVERY_SEC,
    clearAfter: CLEAR_AFTER, postClearSec: CLEAR_AFTER ? POST_CLEAR_SEC : 0,
    bgTimeoutSec: BG_TIMEOUT_SEC, mailTimeoutSec: MAIL_TIMEOUT_SEC, runStartMs,
  }));
  return { runStartMs };
}

function record(kind, sentAtMs, res) {
  // 타임아웃·연결 실패는 status 0 으로 오고, error_code 로 구분한다(1050 = 요청 타임아웃).
  const line = { k: kind, t: sentAtMs, ms: Math.round(res.timings.duration), s: res.status, e: res.error_code || 0 };
  if (res.status === 503) {
    line.ra = res.headers['Retry-After'] || '';
  }
  console.log('REQ ' + JSON.stringify(line));
}

export function popular() {
  const i = Math.floor(Math.random() * (MOOD_THEMES.length + 1));
  const url = i === MOOD_THEMES.length
    ? `${BASE_URL}/api/upload-courses/popular`
    : `${BASE_URL}/api/upload-courses/popular?theme=${MOOD_THEMES[i]}`;
  const sentAtMs = Date.now();
  record('popular', sentAtMs, http.get(url, { timeout: `${BG_TIMEOUT_SEC}s`, tags: { name: 'popular' } }));
}

export function mine() {
  const sentAtMs = Date.now();
  record('mine', sentAtMs, http.get(`${BASE_URL}/api/my-courses`, {
    headers: { Authorization: `Bearer ${JWT}` }, timeout: `${BG_TIMEOUT_SEC}s`, tags: { name: 'mine' },
  }));
}

export function mail(data) {
  // 요청마다 새 주소다. 가입된 주소면 SMTP 까지 가지 않고, 같은 주소면 Redis 코드가 덮어써진다(설계 2-3절).
  // 회차 시작 시각을 넣어 같은 라벨로 다시 재도 주소가 겹치지 않게 한다.
  const n = exec.scenario.iterationInTest;
  const email = `mt-${LABEL}-${data.runStartMs}-${n}@example.com`;
  const sentAtMs = Date.now();
  record('mail', sentAtMs, http.post(`${BASE_URL}/api/users/email/send`, JSON.stringify({ email }), {
    headers: { 'Content-Type': 'application/json' }, timeout: `${MAIL_TIMEOUT_SEC}s`, tags: { name: 'mail' },
  }));
}

export function clearToxic() {
  // mail-toxic.sh clear 와 같은 일을 한다 — 목록을 받아 하나씩 지운다.
  const api = `${TOXIPROXY_URL}/proxies/smtp/toxics`;
  const list = http.get(api);
  let status = list.status;
  if (list.status === 200) {
    for (const toxic of list.json()) {
      status = http.del(`${api}/${toxic.name}`).status;
    }
  }
  console.log('TOXIC ' + JSON.stringify({ t: Date.now(), s: status }));
}
