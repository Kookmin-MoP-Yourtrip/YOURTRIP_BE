// lib/arrival.mjs 가 만든 일정이 포아송인지 확인한다 (#193). k6 가 아니라 Node 로 돌린다.
//
//   node scripts/k6/verify-arrival.mjs [seed=193] [durationSec=720] [rate ...=3 5 8]
//
// 포아송이면 — 분당 도착 수의 평균 ≈ λ, 분산 ≈ 평균(등간격이면 0), 도착 간격의 변동계수 ≈ 1(등간격이면 0).
// 측정 일정(12분)은 표본이 작아 우연히 어긋날 수 있으므로, 같은 생성기를 긴 구간(1,000분)에도 돌려
// "생성기가 맞는가"와 "이번 일정이 어떤 모양인가"를 나눠 본다.
import { arrivalSchedule } from './lib/arrival.mjs';

const [seedArg, durationArg, ...rateArgs] = process.argv.slice(2);
const seed = parseInt(seedArg || '193', 10);
const durationSec = parseInt(durationArg || '720', 10);
const rates = (rateArgs.length ? rateArgs : ['3', '5', '8']).map(Number);

function stats(times, ratePerMin, durationSec) {
  const minutes = Math.floor(durationSec / 60);
  const perMinute = new Array(minutes).fill(0);
  times.forEach((t) => {
    const m = Math.floor(t / 60000);
    if (m < minutes) perMinute[m] += 1;
  });
  const mean = perMinute.reduce((a, b) => a + b, 0) / minutes;
  const variance = perMinute.reduce((a, b) => a + (b - mean) ** 2, 0) / (minutes - 1);
  const gaps = times.slice(1).map((t, i) => t - times[i]);
  const gapMean = gaps.reduce((a, b) => a + b, 0) / gaps.length;
  const gapSd = Math.sqrt(gaps.reduce((a, b) => a + (b - gapMean) ** 2, 0) / (gaps.length - 1));
  // 12초 안에 3건 이상 몰린 횟수 — 처리 시간(약 22초) 안에 겹칠 만한 몰림의 감각을 주는 보조 지표
  let bursts = 0;
  for (let i = 2; i < times.length; i += 1) {
    if (times[i] - times[i - 2] <= 12000) bursts += 1;
  }
  return {
    rate: ratePerMin, arrivals: times.length, expected: Math.round(ratePerMin * durationSec / 60),
    perMinMean: mean.toFixed(2), perMinVar: variance.toFixed(2),
    gapMeanSec: (gapMean / 1000).toFixed(1), gapCv: (gapSd / gapMean).toFixed(2),
    minGapSec: (Math.min(...gaps) / 1000).toFixed(1), maxGapSec: (Math.max(...gaps) / 1000).toFixed(1),
    burst3in12s: bursts,
  };
}

console.log(`seed=${seed}`);
console.log(`\n[측정 일정] ${durationSec}초`);
console.table(rates.map((r) => stats(arrivalSchedule(r, durationSec, seed), r, durationSec)));
console.log('\n[생성기 확인] 60,000초(1,000분)');
console.table(rates.map((r) => stats(arrivalSchedule(r, 60000, seed), r, 60000)));
