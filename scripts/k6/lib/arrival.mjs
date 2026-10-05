// 시드 고정 포아송 도착 일정 (#193) — ai-course-arrival.js 와 검증 스크립트(verify-arrival.mjs)가
// 같은 코드를 쓰도록 여기 둔다. k6 와 Node 양쪽에서 import 된다(브라우저·Node 전용 API 를 쓰지 않는다).
//
// 원리: 포아송 과정의 도착 간격은 지수분포다. 균등 난수 U 를 역변환하면
// 간격 = -ln(1 - U) / λ 이고, 이를 누적한 값이 도착 시각이다.
// 근거: docs/tasks/llm-performance/steps/STEP-arrival-rate.md 1-3

// 32비트 상태 하나로 도는 PRNG. k6 의 Math.random() 은 시드를 정할 수 없어서 둔다 — 같은 시드면
// 상한 4·5 에 똑같은 도착을 넣을 수 있다(공통 난수). 암호용이 아니라 부하 생성용이다.
export function mulberry32(seed) {
  let state = seed | 0;
  return function () {
    state = (state + 0x6D2B79F5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296; // [0, 1)
  };
}

// [0, durationSec) 안의 도착 시각(ms) 배열. 오름차순이다.
export function arrivalSchedule(ratePerMin, durationSec, seed) {
  if (!(ratePerMin > 0) || !(durationSec > 0)) {
    throw new Error(`ratePerMin·durationSec 는 양수여야 한다: ${ratePerMin}, ${durationSec}`);
  }
  const rand = mulberry32(seed);
  const meanGapMs = 60000 / ratePerMin;
  const endMs = durationSec * 1000;
  const times = [];
  // 1 - U 는 (0, 1] 이라 ln(0) = -∞ 가 나오지 않는다.
  for (let t = -Math.log(1 - rand()) * meanGapMs; t < endMs; t += -Math.log(1 - rand()) * meanGapMs) {
    times.push(Math.round(t));
  }
  return times;
}
