# 인기 코스 트랜잭션 분리 — 성능 측정

> 커밋 `6637534`(`perf: 인기 코스 조회 캐시 경로를 트랜잭션 밖으로 분리`)의 효과를 실측으로 입증하기 위한 측정 기록 모음이다. `getPopularCourses`에서 `@Transactional(readOnly = true)`을 제거하고 DB 조회만 `UploadCoursePopularReader`의 짧은 readOnly 트랜잭션으로 좁힌 변경이며, PR #70이 상세조회에 적용한 것과 같은 패턴이다.
>
> **핵심 결론만 먼저**: 로컬 게이트 측정에서 before는 **DB 쿼리가 0건인데도 요청마다 정확히 1회씩 커넥션을 대여**했고(70,468요청 : 70,468대여), after는 **73,932요청 동안 0회**였다. 다만 같은 측정에서 처리량 개선은 **+4.9%**에 그쳤는데, 그 원인 분석이 이후 시나리오 구성을 바꿨다.

## 배경

- 대상 변경: `UploadCourseServiceImpl.getPopularCourses`의 `@Transactional` 제거 + `UploadCoursePopularReader` 신설
- 선례: [stage0/production/ec2-rds.md](../connection-pool-bottleneck/stage0/production/ec2-rds.md) — 상세조회에 같은 처리를 했을 때 `pending` 187→0, TPS +45.4%
- 공백: `/popular`을 대상으로 한 k6 부하테스트는 이 저장소에 존재한 적이 없다. 인기 코스의 유일한 선행 측정([redis-caching/popular-list-cache.md](../redis-caching/popular-list-cache.md))은 로컬 + 커밋되지 않은 Node.js 스크립트 기반이라 이번 비교에 쓸 수 없다

## 측정 단계

| 단계 | 문서 | 상태 |
|---|---|---|
| Phase 0 — 로컬 게이트(H1 검증) | [phase0-local-gate.md](phase0-local-gate.md) | 완료 |
| S1/S3/S5 — EC2 분리 환경 실측 | [ec2-measurement.md](ec2-measurement.md) | **완료** |

### 실측 요약 (EC2, before `98ef39b` → after `6637534`)

| 시나리오 | TPS | p95 | 커넥션 대여 | `pending` 최대 |
|---|---|---|---|---|
| S1 워밍 캐시 | 2,048 → 2,684 (**+31.0%**) | 106.0 → 62.5ms (**-41.1%**) | 918,962 → **0** | 187 → **0** |
| S3 콜드 캐시 | 1,944 → 2,720 (**+39.9%**) | 148.9 → 57.1ms (**-61.7%**) | 139,237 → **121** | 90 → **0** |
| S5 혼합 부하 | 2,192 → 2,347 (+7.1%) | popular 640.7 → 100.2ms (**-84.4%**) | 95,263 → **2,835** | 145 → **0** |

`pending` 187 → 0은 PR #70이 상세조회에서 기록한 수치와 정확히 일치한다. 상세 분석은 [ec2-measurement.md](ec2-measurement.md) 참고.

**S3·S5를 핵심으로 삼은 이유**는 Phase 0의 판정 3에 있다 — 워밍 캐시 경로는 커넥션을 빌렸다 즉시 반납하므로 점유 시간이 마이크로초 수준이고, 그래서 풀(10개)이 애초에 병목이 아니다. 점유 시간이 실제로 긴 구간은 (1) 락 대기 `sleep` 중이고, 실질적 이득이 나타나는 곳은 (2) 같은 풀을 공유하는 다른 API다.

## 부하 스크립트

| 스크립트 | executor | 용도 |
|---|---|---|
| [popular-ramping.js](../../../scripts/k6/popular-ramping.js) | ramping-vus (VU 5→200, 450초) | S1/S2. `detail-ramping.js`와 동일한 VU 단계라 기존 실측과 포화 시작 VU를 나란히 비교할 수 있다 |
| [popular-cold.js](../../../scripts/k6/popular-cold.js) | constant-vus | S3. FLUSHALL 반복과 함께 사용 |
| [popular-mixed.js](../../../scripts/k6/popular-mixed.js) | constant-arrival-rate + ramping-vus | S5. `/popular`은 고정 도착률 배경 부하, 상세조회는 VU를 올려 포화점 탐색 |

공통 헬퍼는 [lib/scenarios.mjs](../../../scripts/k6/lib/scenarios.mjs)의 `buildPopularRequest(baseUrl, themeMode)` — `themeMode`는 `all`(랭킹 키 1개) 또는 `mixed`(ALL + mood 7종 = 8키)다.

## 시드

`seed-benchmark.sql`만으로는 이 경로를 측정할 수 없다 — `upload_course.view_count`가 전부 0이라 랭킹이 비결정적이고, `course_keyword`가 비어 있어 테마 조회가 항상 빈 배열을 반환한다. [seed-popular.sql](../../../scripts/sql/seed-popular.sql)을 이어서 적용한다(기존 파일은 상세조회 측정과의 비교 가능성 때문에 건드리지 않았다).

```bash
psql -h localhost -p 15432 -U postgres -d yourtrip -f scripts/sql/seed-benchmark.sql
psql -h localhost -p 15432 -U postgres -d yourtrip -f scripts/sql/seed-popular.sql
```

## 코드 기준점

- **before** = `98ef39b` (dev 머지 지점, 리팩터링 직전)
- **after** = `6637534` (리팩터링 커밋)

두 커밋 사이 런타임 diff는 정확히 이 리팩터링 변경분뿐이다. 후속 커밋인 null 가드(`00c9d35`)와 테스트(`a0518c1`)는 **의도적으로 제외**해 "바뀐 변수가 하나"를 지켰다.

## 실행 절차

기본 절차는 [EC2-RDS-LOADTEST-GUIDE.md](../../guide/EC2-RDS-LOADTEST-GUIDE.md)를 따른다. arm 전환 시마다 빌드→배포→재기동→재시딩→FLUSHALL이 필요하다(`DB_DDL_AUTO=create`).

### 공통 — 캐시 워밍 (S1/S2/S5용)

`PopularCourseCacheWarmer`가 기동 시 8키를 데우지만, FLUSHALL 이후에는 수동으로 다시 데워야 한다:

```bash
for T in "" "?theme=HEALING" "?theme=ACTIVITY" "?theme=FOOD" "?theme=SENSIBILITY" "?theme=CULTURE" "?theme=NATURE" "?theme=SHOPPING"; do curl -s -o /dev/null "http://<app-private-ip>:8080/api/upload-courses/popular$T"; done
```

### S3 — 콜드 캐시

콜드 구간은 수 초 만에 사라지므로(랭킹 TTL 30분), 부하를 거는 동안 **별도 셸에서 주기적으로 FLUSHALL**을 날려 스탬피드를 반복 유발한다. 앱은 재기동하지 않는다 — 재기동하면 Warmer가 전부 데워버린다.

```bash
# 셸 A: 부하
k6 run -e BASE_URL=http://<app-private-ip>:8080 -e VUS=100 -e DURATION=90s \
       --summary-export=results/s3-<arm>.json scripts/k6/popular-cold.js
```

```bash
# 셸 B: 10초마다 캐시를 비워 스탬피드를 반복 발생시킨다 (SSM 터널로 ElastiCache 연결된 상태)
for i in $(seq 1 9); do redis-cli -h localhost -p 16379 FLUSHALL; sleep 10; done
```

### S5 — 혼합 부하

상세조회가 실제로 DB를 타야 풀 경쟁이 생기므로, 시작 전 FLUSHALL로 상세 캐시를 비운다(인기 코스 캐시는 워밍한 상태로 둔다 — 위 워밍 스크립트를 FLUSHALL 직후 실행).

```bash
k6 run -e BASE_URL=http://<app-private-ip>:8080 -e POPULAR_RATE=300 -e MAX_VUS=200 \
       --summary-export=results/s5-<arm>.json scripts/k6/popular-mixed.js
```

## 측정 지표

기존 stage0/stage1과 동일한 집합을 쓴다(비교 가능성 유지).

**Prometheus** — `hikaricp_connections_pending`(최대 + 최초 발생 VU), `hikaricp_connections_active`(최대), `hikaricp_connections_acquire_seconds_max`, `rate(hikaricp_connections_usage_seconds_sum[1m])/rate(..._count[1m])`(평균 점유시간), **`hikaricp_connections_usage_seconds_count` 증분(커넥션 대여 횟수 — 이번 측정의 결정적 지표)**, `tomcat_threads_busy_threads`, `process_cpu_usage`, `system_cpu_usage`, `http_server_requests_seconds_count`/`_sum`, `jvm_gc_pause_seconds_sum`, `logback_events_total`

**k6** — `http_reqs`, `http_req_duration`(min/avg/med/p90/p95/max), `http_req_failed`, 시나리오별 커스텀 Trend(`popular_latency`/`detail_latency`/`cold_latency`), `lock_wait_responses`, `popular_incomplete`/`detail_partial_responses`, `dropped_iterations`

**CloudWatch** — App EC2 `CPUUtilization`/`CPUCreditBalance`/`mem_used_percent`, RDS 동일 + `DatabaseConnections`, **ElastiCache `CacheHits`/`CacheMisses`/`CurrConnections`/`EngineCPUUtilization`**(Lettuce 계측이 없어 Redis 부하를 볼 유일한 창)

`hibernate_statements_total`은 `generate_statistics`가 꺼져 있어 기본적으로 나오지 않는다. 쿼리 횟수 입증이 필요한 run에서만 `application.yml`에 한 줄 추가해 **양쪽 arm 동일하게** 켜고, 이후 되돌린다.

## 알려진 계측 공백

- **Lettuce(Redis) 커넥션 풀·명령 지연 미계측.** Redis 풀은 8개인데 Tomcat 스레드는 200개다. 트랜잭션 분리로 DB 병목이 사라지면 이쪽이 다음 병목이 될 수 있는데 현재 계측으로는 안 보인다. 측정 대상 코드에 계측을 추가하면 변수 통제가 깨지므로 이번에는 넣지 않고, ElastiCache CloudWatch 지표로 간접 추정한다
- **HTTP percentile 히스토그램 미설정.** 서버측 p95/p99가 없어 k6 클라이언트 측정에만 의존한다
