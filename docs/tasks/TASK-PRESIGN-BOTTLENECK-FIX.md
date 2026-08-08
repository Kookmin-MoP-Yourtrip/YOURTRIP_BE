# TASK-PRESIGN-BOTTLENECK-FIX. 커넥션 풀 병목 해소 계획

> [TASK-PRESIGN-BOTTLENECK.md](TASK-PRESIGN-BOTTLENECK.md)가 원인을 규명한 문제("서명이 `@Transactional` 안에서 실행돼 HikariCP 커넥션을 초 단위로 점유하고, 동시 유저 20명 근처에서 이미 구조적으로 포화된다")에 대한 해결 계획이다. **아직 구현하지 않았다** — 단계별 우선순위와 각 단계의 근거·트레이드오프를 정리한 문서다.

## 배경 요약

- 상세 조회 API(`getPlaceListByDay`, `getDetail`)가 캐시 조회 + CloudFront/S3 이미지 URL 서명을 하나의 `@Transactional(readOnly = true)` 메서드 안에서 처리한다.
- 서명이 끝나야 메서드가 리턴되고, 그래야 HikariCP 커넥션(기본 풀 10개)이 반납된다 — DB 쿼리가 0건(캐시 히트)이어도 마찬가지다.
- 실측 결과 이 시스템은 **동시 유저 20명 근처에서 이미 포화**되고(`hikaricp_connections_active`가 풀 크기 10에 도달, `pending`이 쌓이기 시작), 그 이후 VU를 200까지 늘려도 처리량과 CPU 사용률은 거의 그대로다 — 병목은 CPU가 아니라 커넥션 풀 경합이다.
- 관련 이슈: [#67 perf: 캐시 조회와 CloudFront 서명 로직을 트랜잭션 경계 밖으로 분리](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/67) — 아래 0단계에 해당한다.

## 단계별 계획

### 0단계 — 서명 호출을 트랜잭션 경계 밖으로 분리 (선행 필수, 이슈 #67)

**무엇을**: 캐시 조회는 트랜잭션 시작 전으로, DB 읽기(캐시 미스 시)는 DTO 변환까지만 담당하는 짧은 트랜잭션으로, 서명은 트랜잭션 밖으로 각각 분리한다.

**왜 먼저**: 이걸 안 하면 아래 어떤 단계를 적용해도 "서명 1건이라도 남아있으면 그 1건이 여전히 커넥션을 오래 쥔다"는 구조적 결함이 남는다. 가장 저비용으로 가장 먼저 처리해야 하는 전제조건이다.

**구현 시 주의점**:
- Spring self-invocation 문제 — 같은 클래스 안에서 `this.트랜잭션메서드()` 호출은 `@Transactional`이 무시된다. 별도 협력 빈으로 분리하거나 `TransactionTemplate`으로 블록 단위 트랜잭션을 쓴다.
- mycourse의 `cloudFrontSigningExecutor` 병렬 서명 코드는 그대로 유지하되, 호출 위치만 트랜잭션 밖으로 옮긴다.

**검증**: `scripts/k6/detail-ramping.js` + Prometheus range query로 knee 시계열을 재측정한다. 목표는 "VU 20 근처에서 `pending`이 나타나던 지점"이 훨씬 뒤로 밀리는 것(이상적으로는 HikariCP 기본 풀 10개로도 VU 수백 단위까지 버텨야 한다 — 순수 DB 읽기는 밀리초 단위라 커넥션 회전이 훨씬 빨라지기 때문).

---

### 1단계 — mycourse 이미지 접근을 CloudFront Signed URL에서 Signed Cookie로 전환

**무엇을**: 지금은 상세 조회 응답에 담긴 이미지 URL 하나하나(코스당 최대 수십 장)를 개별 서명한다. 대신 코스 열람 시점에 그 코스(또는 사용자) 범위에 대한 **Signed Cookie를 1회만 발급**하고, 이후 이미지 URL은 서명 없는 일반 CloudFront URL로 응답한다. 브라우저가 쿠키를 자동으로 실어 보내면 CloudFront 엣지에서 인가를 검증한다.

**왜**: [AWS 공식 문서](https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/private-content-choosing-signed-urls-cookies.html)가 정확히 이 상황을 위한 기준을 제시한다 — "여러 개의 제한된 파일에 접근을 제공하려면 Signed Cookie를 쓰라." 이 전환은 서명 연산량 자체를 "요청당 이미지 수"에서 "요청(또는 세션)당 1회"로 줄인다 — 0단계가 "커넥션을 덜 오래 쥔다"는 개선이라면, 이건 "애초에 서명할 일 자체를 줄인다"는 더 근본적인 개선이다.

**트레이드오프 및 검토 필요 사항**:
- CloudFront Signed Cookie는 발급 후 즉시 취소가 어렵다(TTL 만료를 기다려야 함) — 코스 소유자가 코스를 비공개로 전환하거나 이미지를 삭제했을 때의 접근 제어를 어떻게 가져갈지 별도 설계가 필요하다.
- 쿠키 범위(`Path`)를 어떻게 잡을지: 코스 단위(`private/{courseId}/*`)로 좁게 잡을지, 사용자 단위(`private/{userId}/*`)로 넓게 잡아 갱신 빈도를 줄일지 트레이드오프가 있다.
- 모바일 클라이언트(Android 앱, README 기준 이 프로젝트의 실제 FE)가 쿠키 기반 인증을 자연스럽게 다루는지 확인 필요 — 웹 브라우저와 달리 앱은 쿠키 저장소를 직접 관리해야 할 수 있다.

**검증**: 서명 마이크로벤치마크(`./gradlew benchmarkTest`)와 JFR CPU 프로파일(crypto 카테고리 샘플 비율)을 mycourse 상세 조회 부하 전후로 비교한다. 이미지 수와 무관하게 요청당 서명 비용이 상수에 가까워지는지 확인한다.

---

### 2단계(조건부) — Signed URL을 만료시간보다 짧은 TTL로 캐싱

**적용 조건**: 1단계(Signed Cookie 전환)가 당장 부담스럽거나(클라이언트 마이그레이션 비용, 접근 제어 재설계 범위) 보류될 경우의 대안, 또는 1단계 전까지의 과도기적 완화책.

**무엇을**: [CACHING-ROADMAP.md 설계 원칙 1](../CACHING-ROADMAP.md)의 "presigned URL은 캐싱하지 않는다"를 재검토한다. 서명 URL 자체(S3 key가 아니라 완성된 URL)를 Redis에 만료시간보다 충분히 짧은 TTL로 캐싱한다(예: 60분 유효 → 10분 캐시).

**근거**: [Ben Nadel의 케이스 스터디](https://www.bennadel.com/blog/3685-performance-case-study-caching-cryptographically-signed-urls-in-redis-in-lucee-5-2-9-40.htm)가 동일한 패턴으로 p95 URL 생성 시간을 1/3로 줄인 실측 사례를 보고한다.

**트레이드오프**: 1단계보다 효과가 작다(캐시 미스마다 여전히 이미지 수만큼 서명해야 한다). "만료된 URL이 나갈 위험"은 TTL을 만료시간보다 충분히 짧게 잡아 관리해야 한다.

---

### 3단계 — 서명/DB 작업을 Bulkhead 패턴으로 정식 격리

**무엇을**: 0단계가 "트랜잭션 밖으로 뺀다"는 임기응변이라면, 이를 Michael Nygard(*Release It!*)가 정식화한 **Bulkhead 패턴**(서로 다른 성격의 작업을 별도 리소스 풀로 파티셔닝해 한쪽 지연이 다른 쪽으로 전염되지 않게 하는 것)으로 구조화한다. Spring 생태계에서는 Resilience4j `@Bulkhead(type = Bulkhead.Type.THREADPOOL)`가 표준 구현체다.

**현재 상태와의 연결**: `cloudFrontSigningExecutor`(12스레드 전용 풀)가 사실 이 패턴의 절반(서명 스레드풀 격리)은 이미 구현돼 있었다. 다만 그 앞단(HikariCP 풀)이 격리 안 돼 있어서 실제로는 자기 용량(12스레드, 동시성 200에서도 최대 10요청분만 도달)을 다 써본 적이 없었다([TASK-PRESIGN-BOTTLENECK.md의 "PR #61 재해석"](TASK-PRESIGN-BOTTLENECK.md) 참고). 0단계가 적용되면 이 실행자가 비로소 제 역할을 하게 된다.

**검증**: 0단계 적용 후 동시성 200 부하에서 `cloudFrontSigningExecutor`의 큐/활성 스레드 수가 실제로 12에 가깝게 올라가는지 확인한다(현재는 앞단 병목에 가려 도달한 적이 없다).

---

### 4단계(후순위, 신중하게) — 커넥션 풀/DB 계층 튜닝

**적용 시점**: 0~3단계를 적용하고 재측정한 뒤에도 여전히 트래픽 규모상 부족할 때만 검토한다. **지금 문제를 풀 크기로 덮는 용도로 먼저 쓰지 않는다.**

**근거**: [HikariCP 공식 위키](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)는 "작은 풀을 대기 스레드로 포화시켜라"는 원칙과 함께, 스레드 수가 코어 수를 넘으면 컨텍스트 스위칭 오버헤드로 오히려 느려질 수 있다고 경고한다. PostgreSQL은 커넥션 하나당 OS 프로세스 하나라서, [PostgreSQL 위키](https://wiki.postgresql.org/wiki/Number_Of_Database_Connections)에 따르면 커넥션 수를 무작정 늘리면 메모리·컨텍스트 스위칭 비용이 DB 서버를 직접 압박한다.

**대안**: 정말 대량 트래픽 규모로 간다면 PgBouncer(transaction mode) 도입을 검토할 수 있으나, [실무 경고](https://jpcamara.com/2023/04/12/pgbouncer-is-useful.html)가 뚜렷하다 — session-level advisory lock, named prepared statement, `LISTEN`, `CREATE INDEX CONCURRENTLY` 등이 조용히 깨질 수 있다. 도입 시 이 프로젝트가 해당 기능을 쓰는지 먼저 점검해야 한다.

**이번 범위에서는 코드를 변경하지 않는다** — 후속 검토 항목으로만 기록.

---

### 5단계 — 모니터링을 알람으로 승격

**무엇을**: `test/presigned-url-bottleneck`에서 만든 Grafana 대시보드(`Bottleneck Test` 폴더 › `Presign CPU Bottleneck`)의 `hikaricp_connections_pending`을 사후 분석용이 아니라 사전 경보용으로 전환한다 — 예: `hikaricp_connections_pending > 0`이 N초 이상 지속되면 알림.

**왜**: 이번 실험으로 이 지표가 "병목이 이미 시작됐다"는 가장 빠르고 명확한 신호라는 게 실측으로 확인됐다(직접 증거 4 — TPS/CPU가 눈에 띄게 나빠지기 전에 이미 `pending`이 먼저 반응한다).

## 실행 순서와 의존관계

```
0단계 (트랜잭션 분리) ─┬─→ 3단계 (Bulkhead 정식화, 0단계의 효과를 완성시킴)
                        │
                        └─→ 1단계 (Signed Cookie 전환) ─→ 5단계 (알람화)
                              │
                              └─(보류 시 대안)→ 2단계 (TTL 캐싱)

4단계는 0~3단계 재측정 후 필요성 재평가
```

**필수 경로**: 0 → 1 → 3 → 5, 2와 4는 조건부(각 단계 설명 참고).

## 공통 검증 방법

각 단계 적용 후 다음을 반복한다 — [TASK-PRESIGN-BOTTLENECK.md의 "재현 방법"](TASK-PRESIGN-BOTTLENECK.md)과 동일한 도구를 재사용한다.

1. `scripts/sql/seed-benchmark.sql`로 동일 규격 시드
2. `scripts/k6/detail-ramping.js`(VU 1→200)로 부하
3. Prometheus range query로 `hikaricp_connections_active`/`pending`/`process_cpu_usage`를 시간축으로 뽑아 knee 위치 비교
4. `SigningBenchmarkTest`(`./gradlew benchmarkTest`)로 요청당 서명 연산 횟수/비용 변화 확인

**목표 지표**: "VU 20 근처에서 `pending`이 나타나기 시작한다"는 현재 상태가, 각 단계 적용 후 어느 VU까지 밀려나는지를 정량적으로 비교해 단계별 효과를 분리 측정한다.

## 참고 문서

- [TASK-PRESIGN-BOTTLENECK.md](TASK-PRESIGN-BOTTLENECK.md) — 원인 규명(이 계획의 근거)
- [CACHING-ROADMAP.md](../CACHING-ROADMAP.md) — 2단계와 관련된 기존 캐싱 설계 원칙
- GitHub 이슈 [#67](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/67) — 0단계에 대응. 1/3/5단계는 착수 시점에 별도 이슈로 분리하는 것을 검토한다.
