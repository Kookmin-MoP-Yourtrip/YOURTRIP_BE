# TASK-PRESIGN-BOTTLENECK-FIX. 커넥션 풀 병목 해소 계획

> [TASK-PRESIGN-BOTTLENECK.md](TASK-PRESIGN-BOTTLENECK.md)가 원인을 규명한 문제("서명이 `@Transactional` 안에서 실행돼 HikariCP 커넥션을 초 단위로 점유하고, 동시 유저 20명 근처에서 이미 구조적으로 포화된다")에 대한 해결 계획이다. 단계별 우선순위와 각 단계의 근거·트레이드오프를 정리한 문서다. **0단계(트랜잭션 분리)와 그 과정에서 발견한 FK 인덱스 추가는 로컬 + EC2/RDS 분리 환경 양쪽에서 구현·재검증까지 완료했다** — 실측 기록이 길어져 별도 문서 4개로 분리했다(아래 0단계 절의 "실측 결과 요약" 참고). 핵심 결론만 먼저 밝히면: mycourse는 로컬·EC2+RDS 어느 환경에서도 0단계로 풀 포화가 개선되지 않았고(`pending` 최대값이 적용 전후로 완전히 동일), 오히려 평균 커넥션 점유시간이 악화됐다(47.3ms→53.3ms) — 그 원인은 `cloudFrontSigningExecutor`가 HikariCP 풀이 우연히 제공하던 유량제한을 잃고 `CallerRunsPolicy`로 CPU를 대량 잠식했기 때문임을 이후 검증으로 확정했다. uploadcourse는 두 환경 모두에서 `pending`이 0으로 사라지는 뚜렷한 개선을 보였다. 인덱스는 로컬에서만 검증됐고 EC2+RDS 환경에서는 아직 재검증하지 않았다 — 1단계 이후도 아직 미착수.

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

#### 실측 결과 요약

0단계를 구현·재검증하는 과정에서 실측 라운드가 세 차례로 늘어나, 상세 기록은 각각 별도 문서로 뺐다. 요약만 아래에 남긴다.

**1. [0단계 로컬 실측](stage0/local/transaction-separation.md)** — uploadcourse는 `pending` 181→0으로 뚜렷이 개선됐다(캐시 히트 경로가 DB 커넥션을 아예 안 씀). mycourse는 `spring.jpa.open-in-view` 기본값(true)이 트랜잭션 분리 효과를 무력화하고 있던 걸 발견해 껐지만, 그래도 개선되지 않았다(TPS -7.5%, `acquire_seconds` +86%). 원인을 추적한 결과 `place`/`place_image`/`day_schedule`의 FK 컬럼에 인덱스가 없어 매 요청이 3만~6만 행 전체 스캔을 돌고 있었다는 게 진짜 병목이었다(인덱스는 이 시점엔 아직 미적용, 원인 규명까지만).

**2. [인덱스 추가 로컬 실측](stage0/local/index.md)** — 위에서 찾은 FK 컬럼 3개에 인덱스를 추가하자 개별 쿼리는 49~236배(`EXPLAIN ANALYZE` 기준) 빨라졌다. 하지만 mycourse의 풀 포화 지표(`pending`, `acquire_seconds`, 포화 시작 VU)는 거의 그대로였다 — JFR 프로파일링(Phase A/B/C)으로 추적한 결과, 앱·DB·k6를 전부 한 개발 머신에서 같이 돌리는 비격리 환경 자체가 잔여 병목의 상당 부분(부하 중 머신 CPU 15~22%→50~73%, JDBC 소켓 응답이 10~40배 느려짐)을 설명한다는 결론에 도달했다.

**3. [EC2+RDS 분리 환경 실측](stage0/production/ec2-rds.md)** — 위 결론을 검증하기 위해 앱(EC2)·RDS·ElastiCache·k6(별도 EC2)를 분리한 환경에서 0단계 적용 전/후를 재측정했다(인덱스는 이번엔 전/후 모두 제거한 상태로 통제). 환경 노이즈를 제거해도 mycourse는 `pending` 186→186으로 **완전히 동일**했고 TPS는 오히려 17.2% 떨어졌다 — 로컬에서 관찰된 병목이 환경 노이즈가 아니라 **진짜 구조적 문제(20:1 HikariCP 풀 크기)**임이 재확인됐다. uploadcourse는 로컬과 동일하게 `pending`이 187→0으로 완전히 사라지는 개선을 보였다. 다만 mycourse의 평균 커넥션 점유시간이 47.3ms→53.3ms(+12.7%)로 오히려 악화된 건 이 문서만으로는 설명되지 않았다(트랜잭션이 하는 일은 줄었는데 점유시간은 늘었다는 역설). 인덱스 자체는 이 EC2+RDS 환경에서 아직 재검증되지 않았다.

**4. [CallerRunsPolicy 가설 검증](stage0/production/callerruns-verification.md)** — 위 역설의 원인을 규명했다. `cloudFrontSigningExecutor`(§3단계 참고)가 0단계 이후 HikariCP 풀이 우연히 수행하던 "서명 유량 제한"을 잃고 VU200 근처에서 큐+풀 용량을 압도적으로 초과해, `CallerRunsPolicy`가 초당 약 1,450회(구간 누적 약 145,000회, 전체 실행 413,366회) 발동했다. 그 결과 Tomcat 요청 스레드의 80.69%가 자기 요청 대신 ECDSA 서명 연산을 동기 실행하며 vCPU를 98.28%까지 점유했다(JFR·Micrometer 카운터·CloudWatch 3중 계측으로 확정). 이 CPU 경합이 같은 시점의 DB 트랜잭션 처리 스레드를 굶겨 벽시계 실행시간을 늘렸다는 게 가장 유력한 설명이다.

**5. [AbortPolicy + 게이트 효과 검증](stage0/production/abortpolicy-gate-verification.md)** — §3단계에서 구현한 해결책(`CallerRunsPolicy`→`AbortPolicy`, 요청 단위 세마포어 게이트 `CloudFrontSigningGate` 신설)을 같은 EC2 환경에서 재측정했다. **원래 목표(HikariCP 점유시간 회복)는 `AbortPolicy` 전환만으로 이미 달성됐다** — 53.3ms→5.79ms(게이트 비활성)/6.95ms(게이트 활성), 목표(47.3ms 이하) 대비 압도적으로 낮다. JFR도 CloudFront 서명 프레임(`presign_or_signing`)이 Tomcat 스레드에서 완전히 사라졌음을 확인했다(69.28%→0.00%). 다만 게이트가 없으면 태스크 단위 거부로 인해 응답의 49.4%가 `status: 200`인 채 이미지 일부만 누락되는 "브라운아웃"이 있었고(k6 기본 체크로는 안 잡힘), 게이트는 이를 21.0%까지 줄였지만 완전히 없애지는 못했다(큐 사이징이 실전 경합 부하에서는 부족했던 것으로 규명) — 재조정·재측정은 후속 과제로 남겼다.

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

**무엇을**: [CACHING-ROADMAP.md 설계 원칙 1](../../CACHING-ROADMAP.md)의 "presigned URL은 캐싱하지 않는다"를 재검토한다. 서명 URL 자체(S3 key가 아니라 완성된 URL)를 Redis에 만료시간보다 충분히 짧은 TTL로 캐싱한다(예: 60분 유효 → 10분 캐시).

**근거**: [Ben Nadel의 케이스 스터디](https://www.bennadel.com/blog/3685-performance-case-study-caching-cryptographically-signed-urls-in-redis-in-lucee-5-2-9-40.htm)가 동일한 패턴으로 p95 URL 생성 시간을 1/3로 줄인 실측 사례를 보고한다.

**트레이드오프**: 1단계보다 효과가 작다(캐시 미스마다 여전히 이미지 수만큼 서명해야 한다). "만료된 URL이 나갈 위험"은 TTL을 만료시간보다 충분히 짧게 잡아 관리해야 한다.

---

### 3단계 — 서명 실행자를 AbortPolicy + 요청 단위 세마포어 게이트로 재설계

**무엇을**: 0단계가 "트랜잭션 밖으로 뺀다"는 임기응변이라면, 이를 Michael Nygard(*Release It!*)가 정식화한 **Bulkhead 패턴**(서로 다른 성격의 작업을 별도 리소스 풀로 파티셔닝해 한쪽 지연이 다른 쪽으로 전염되지 않게 하는 것)으로 구조화한다.

**현재 상태와의 연결**: `cloudFrontSigningExecutor`(코어 수만큼의 전용 풀)가 사실 이 패턴의 절반(서명 스레드풀 격리)은 이미 구현돼 있었다. 다만 그 앞단(HikariCP 풀)이 격리 안 돼 있어서 0단계 적용 전에는 실제로 자기 용량을 다 써본 적이 없었다([TASK-PRESIGN-BOTTLENECK.md의 "PR #61 재해석"](TASK-PRESIGN-BOTTLENECK.md) 참고). **다만 [CallerRunsPolicy 가설 검증](stage0/production/callerruns-verification.md)으로 확인된 실제 결과는 이 서술이 예상한 것보다 훨씬 나빴다** — 0단계 적용 후 이 실행자는 "비로소 제 역할을 하게" 된 게 아니라, VU200 근처에서 큐+풀 용량(102)을 압도적으로 초과해 `CallerRunsPolicy`가 초당 약 1,450회 발동하며 격리 자체가 깨졌다(요청 스레드로 서명 작업이 그대로 역류). Bulkhead는 상대방의 지연이 전염되지 않게 막는 게 목적인데, 지금 구조는 정확히 그 반대로 동작하고 있었다는 뜻이라 이 단계의 필요성이 더 강하게 뒷받침된다. 해결책의 설계·구현·기각한 대안·EC2 재검증은 별도 문서([abortpolicy-gate-verification.md](stage0/production/abortpolicy-gate-verification.md))로 뺐다.

**구현 방식 정정(Resilience4j → 직접 구현)**: 이 절은 원래 "Resilience4j `@Bulkhead(type = Bulkhead.Type.THREADPOOL)`가 표준 구현체"라고 서술했으나, 실제 설계·구현 단계에서 이 fan-out 구조(요청 1건이 이미지 수만큼 태스크를 만듦)에는 THREADPOOL 타입이 부적합하다는 게 드러나 정정한다. 최종 채택안은 다음 두 가지의 조합이다.

1. **`cloudFrontSigningExecutor`의 거부 정책을 `CallerRunsPolicy` → `AbortPolicy`로 전환.** 큐+풀이 가득 차면 제출 스레드가 서명을 대신 실행하는 대신 즉시 예외를 받는다 — CPU 격리는 이것만으로 복원된다.
2. **`CloudFrontSigningGate`(`java.util.concurrent.Semaphore` 직접 구현) 신설.** AbortPolicy 단독으로는 부하 차단이 태스크(이미지) 단위로 일어나 "요청 하나가 이미지 일부만 서명된 채 200 OK로 나가는" 부분 응답이 상시화되는 문제가 있다. 게이트는 부하 차단을 요청 단위로 옮겨, 통과한 요청은 이미지가 전부 붙은 완전한 응답을, 나머지는 명확한 503(+`Retry-After`)을 받게 한다.

Resilience4j `Bulkhead.Type.THREADPOOL`/`Type.SEMAPHORE`를 검토하고 기각한 근거는 [abortpolicy-gate-verification.md의 "구현 방식 검토"](stage0/production/abortpolicy-gate-verification.md)에 정리했다.

**검증**: ~~0단계 적용 후 동시성 200 부하에서 `cloudFrontSigningExecutor`의 큐/활성 스레드 수가 실제로 코어 수에 가깝게 올라가는지 확인한다~~ → [callerruns-verification.md](stage0/production/callerruns-verification.md)에서 완료. ~~위 구현의 효과는 EC2 재측정으로 검증한다~~ → [abortpolicy-gate-verification.md의 "EC2 실측 검증"](stage0/production/abortpolicy-gate-verification.md#ec2-실측-검증)에서 완료. `CLOUDFRONT_SIGNING_PERMITS` 환경변수만 바꿔 게이트 비활성(100000)/활성(54, EC2 실측 서명비용 기준 재계산) 두 arm을 같은 배포로 A/B 측정한 결과, HikariCP 점유시간 목표(47.3ms 이하)는 두 arm 모두 압도적으로 달성했다(5.79ms/6.95ms). 판정 기준으로 세웠던 "JFR crypto 비율 <5%"는 실측으로 부정확했음이 드러나(JWT 검증 비용이 섞여 30~57%로 나옴) `presign_or_signing`(CloudFront 서명 프레임만 특정)으로 대체했고, 이 지표는 두 arm 모두 0.00%로 완전한 격리 복원을 확인했다. 다만 게이트의 큐 사이징(640)은 실전 경합 부하에서 부족한 것으로 드러나 브라운아웃(부분 응답)을 49.4%→21.0%로 줄이는 데 그쳤다 — 재조정은 후속 과제.

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
- [stage0/local/transaction-separation.md](stage0/local/transaction-separation.md) — 0단계 로컬 실측 기록
- [stage0/local/index.md](stage0/local/index.md) — 인덱스 추가 로컬 실측 기록
- [stage0/production/ec2-rds.md](stage0/production/ec2-rds.md) — EC2+RDS 분리 환경 실측 기록
- [stage0/production/callerruns-verification.md](stage0/production/callerruns-verification.md) — mycourse 점유시간 악화 원인(CallerRunsPolicy CPU 경합) 검증 기록
- [stage0/production/abortpolicy-gate-verification.md](stage0/production/abortpolicy-gate-verification.md) — 위 원인에 대한 해결책(AbortPolicy 전환 + `CloudFrontSigningGate`) 설계·구현·EC2 재검증 기록
- [CACHING-ROADMAP.md](../../CACHING-ROADMAP.md) — 2단계와 관련된 기존 캐싱 설계 원칙
- GitHub 이슈 [#67](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/67) — 0단계에 대응. 1/3/5단계는 착수 시점에 별도 이슈로 분리하는 것을 검토한다.
