#!/usr/bin/env node
// test/presigned-url-bottleneck: JFR ExecutionSample을 파싱해 CPU 샘플의 메서드별 귀속을
// 집계한다. `jfr print --events jdk.ExecutionSample <file>.jfr` 출력을 stdin으로 받는다.
//
// 사용법:
//   jfr print --events jdk.ExecutionSample --stack-depth 64 armP.jfr | node parse-execution-samples.mjs
//
// 출력:
//   1) 최상위(leaf) 프레임 Top 30 — 어떤 메서드가 실제로 CPU 위에 있었는지
//   2) 관심 패키지가 스택 어디에라도 등장하는 샘플의 비율 — presign/로깅/Hibernate/암호연산/HikariCP
//      각각이 "이 요청 처리 도중 CPU를 쓰고 있었다"고 볼 수 있는 근거

import { createInterface } from 'node:readline';

const INTERESTING = {
  presign_or_signing: [/^software\.amazon\.awssdk\./],
  logging: [/^ch\.qos\.logback\./],
  hibernate: [/^org\.hibernate\./],
  crypto: [/^java\.security\./, /^sun\.security\./, /^javax\.crypto\./],
  hikaricp: [/^com\.zaxxer\.hikari\./],
};

function classify(stackFrames) {
  const hit = new Set();
  for (const frame of stackFrames) {
    for (const [label, patterns] of Object.entries(INTERESTING)) {
      if (hit.has(label)) continue;
      if (patterns.some((re) => re.test(frame))) hit.add(label);
    }
  }
  return hit;
}

async function main() {
  const rl = createInterface({ input: process.stdin, crlfDelay: Infinity });

  let totalSamples = 0;
  const leafCounts = new Map();
  const categoryHits = Object.fromEntries(Object.keys(INTERESTING).map((k) => [k, 0]));

  let inSample = false;
  let currentFrames = [];
  let leafCaptured = false;

  const flushSample = () => {
    if (currentFrames.length === 0) return;
    totalSamples += 1;

    const leaf = currentFrames[0];
    leafCounts.set(leaf, (leafCounts.get(leaf) ?? 0) + 1);

    const hits = classify(currentFrames);
    for (const label of hits) categoryHits[label] += 1;

    currentFrames = [];
    leafCaptured = false;
  };

  for await (const rawLine of rl) {
    const line = rawLine.replace(/\r$/, '');

    if (line.startsWith('jdk.ExecutionSample')) {
      flushSample();
      inSample = true;
      continue;
    }

    if (!inSample) continue;

    // jfr print 스택 프레임 라인 예시:
    //   "software.amazon.awssdk.services.s3.presigner.S3Presigner.presignGetObject(GetObjectPresignRequest) line: 142"
    // 람다 프레임(예: "MyCourseServiceImpl$$Lambda$123/0x0000000801234567.get(...)")도 잡아야
    // 하므로 클래스 부분은 공백/괄호가 아닌 모든 문자를 허용한다("/0x..." 오프셋 포함).
    const frameMatch = line.match(/^\s*([^\s(]+\.[\w$<>]+)\(/);
    if (frameMatch) {
      currentFrames.push(frameMatch[1]);
      leafCaptured = true;
      continue;
    }

    // 빈 줄 또는 다음 이벤트 헤더 직전 = 현재 샘플 종료
    if (line.trim() === '' && leafCaptured) {
      flushSample();
      inSample = false;
    }
  }
  flushSample();

  if (totalSamples === 0) {
    console.error('ExecutionSample 이벤트를 찾지 못했습니다. `jfr print --events jdk.ExecutionSample <file>.jfr`의 출력을 stdin으로 넘겼는지 확인하세요.');
    process.exit(1);
  }

  console.log(`총 샘플 수: ${totalSamples}\n`);

  console.log('=== 최상위(leaf) 프레임 Top 30 ===');
  const topLeaves = [...leafCounts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 30);
  for (const [frame, count] of topLeaves) {
    const pct = ((count / totalSamples) * 100).toFixed(2);
    console.log(`${pct.padStart(6)}%  (${String(count).padStart(6)})  ${frame}`);
  }

  console.log('\n=== 관심 패키지 포함 샘플 비율 (스택 어디에라도 등장) ===');
  for (const [label, count] of Object.entries(categoryHits)) {
    const pct = ((count / totalSamples) * 100).toFixed(2);
    console.log(`${pct.padStart(6)}%  (${String(count).padStart(6)})  ${label}`);
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
