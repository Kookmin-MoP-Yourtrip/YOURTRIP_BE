# 업로드 코스 캐싱 효과 측정

> 인기 코스(`GET /api/upload-courses/popular`)와 상세 조회(`GET /api/upload-courses/{id}`)의 Redis 캐싱이 **실제로 무엇을 얼마나 해결했는지**를 하나의 표에 놓는 측정이다.
>
> 기존 실측은 전부 "캐싱이 이미 있다"는 전제 위에서 그 뒤에 붙은 개선(트랜잭션 분리, 인덱스, CloudFront)만 다뤘다. **"캐싱이 없던 상태"는 한 번도 측정된 적이 없다.**

## 왜 세 상태를 재는가

개선 폭을 두 단계로 분해하기 위해서다 — 캐싱 도입이 준 몫과, 그 캐싱이 제대로 동작하게 만든 트랜잭션 분리가 준 몫.

| arm | 캐시 | 트랜잭션 경계 | 대응하는 실제 코드 상태 |
|---|---|---|---|
| **A0** | 없음 | 메서드 전체 | 캐싱 도입 이전 |
| **A1** | 있음 | 메서드 전체 | `7e74d0d`/`604d3a4` ~ 트랜잭션 분리 직전 |
| **A2** | 있음 | Reader만 | **현재 운영 (기본값)** |

세 arm은 **같은 커밋·같은 JAR**을 쓰고 프로퍼티 두 개(`yourtrip.benchmark.upload-course-cache`, `.upload-course-tx`)만 바꾼다. 커밋 checkout 방식은 그 사이에 낀 CloudFront 전환·presign 개선이 함께 딸려와 "바뀐 변수가 하나"를 지킬 수 없다.

**핵심 가설(H2)**: A0→A1에서 SQL은 100% 사라지지만 커넥션 대여와 `pending`은 거의 그대로이고, A1→A2에서 비로소 풀린다. 커넥션을 잡는 조건이 "쿼리를 실행하는가"가 아니라 "트랜잭션을 여는가"이기 때문이다.

## 진행 상황

| 단계 | 문서 | 상태 |
|---|---|---|
| Phase 0 — 로컬 게이트 (토글 검증) | [phase0-local-gate.md](phase0-local-gate.md) | **완료 — 통과** |
| Phase 1 — EC2 인기 코스 (P1·P3·P5) | [ec2-measurement.md](ec2-measurement.md) | **완료 (각 arm 1회)** |
| Phase 2 — EC2 상세 조회 (D2·D3) | [ec2-measurement.md](ec2-measurement.md) | **완료 (D2 각 arm 3회 / D3 1회)** |
| Phase 3 — 규모 곡선 (L1) | [scale-curve.md](scale-curve.md) | **완료 (쿼리 플랜 분석으로 대체)** |

### Phase 0 결과 요약

| arm | endpoint | SQL/req | 커넥션 대여/req |
|---|---|---|---|
| A0 | popular / detail | 8.000 / 4.000 | 1.000 |
| **A1** | popular / detail | **0.000** | **1.000** |
| A2 | popular / detail | 0.000 | 0.000 |

**A1에서 "SQL 0건 + 커넥션 대여 1.000회"가 재현됐다** — H2가 말한 "일은 하나도 안 하면서 커넥션만 점유"다. 세 arm의 응답 본문도 동일해 비교가 성립한다.

측정 중 **미스 경로의 N+1**을 발견했다(`travelCourse`·`user` LAZY 접근). 기존 코드에 원래 있던 것이고 `generate_statistics`가 꺼져 있어 보이지 않던 것이다. 자세한 내용은 Phase 0 문서 참고.

### Phase 1 결과 요약 (P1 — 인기 코스 워밍, VU 1→200)

| | A0 캐싱없음 | A1 캐싱/분리전 | A2 캐싱/분리후 |
|---|---|---|---|
| 요청당 SQL | 7.9926 | **0.0000** | 0.0000 |
| 요청당 커넥션 대여 | 0.9991 | **0.9996** | **0.0000** |
| `pending` 최대 | 187 | **189** | **0** |
| TPS | 870.6 | 1883.0 | 2621.4 |
| p95 | 210.4ms | 113.3ms | 61.4ms |
| **포화 시작 VU** | **20** | **20** | **50** |
| **포화 주체** | **커넥션풀** | **커넥션풀** | **CPU** |

**A0→A1에서 SQL을 100% 없앴는데 커넥션 대여와 `pending`은 미동도 하지 않았다.** 풀이 풀린 것은 A1→A2였고, 그 지점에서 **포화 주체가 커넥션 풀에서 CPU로 옮겨갔다.** 캐싱은 병목을 없앤 게 아니라 옮겼으며, 그 이동에는 트랜잭션 분리가 함께 필요했다.

같은 패턴이 P3(고정 VU 100)와 P5(혼합 부하)에서도 재현됐다. 상세 표와 판정은 [ec2-measurement.md](ec2-measurement.md) 참고.

### Phase 2 결과 요약 (D2 — 상세 조회, VU 1→200, **각 arm 3회**)

| | A0 캐싱없음 | A1 캐싱/분리전 | A2 캐싱/분리후 |
|---|---|---|---|
| 요청당 SQL | 3.9963 | **0.0187** | 0.0148 |
| 요청당 커넥션 대여 | 0.9991 | **0.9994** | **0.0037** |
| `pending` 최대 | 186 | **187** | **0** |
| 평균 커넥션 점유 | 8.44ms | **4.92ms** | 5.33ms |
| TPS (변동폭) | 871.0 (841~873) | 1426.0 (1393~1433) | 1801.8 (1760~1846) |
| p95 | 221.1ms | 142.7ms | 82.2ms |

**H2가 상세 조회에서도 재현됐다** — SQL을 99.5% 없앴는데 대여는 +0.0%, `pending`은 +0.5%다. 반복 3회의 변동폭(±2% 내)이 arm 간 격차(+63.7%, +26.4%)보다 한 자릿수 이상 작아, **이 차이는 노이즈가 아니다.**

**A0→A1이 처리량을 올린 메커니즘도 드러났다.** 대기줄 길이는 그대로인데 **평균 커넥션 점유시간이 절반**이 됐다 — 줄을 짧게 만든 게 아니라 줄이 빠지는 속도를 높인 것이다. 그래서 캐싱만으로는 풀 10개라는 상한과 대기 자체가 남는다.

열린 루프(D3)에서 그 잔존 대기의 크기가 보인다. **A0·A1은 Tomcat 워커 200개를 전부 소진했는데 A2는 40개만 썼다** — A0·A1의 워커 대부분이 일하는 게 아니라 커넥션을 기다리며 묶여 있었다는 직접 증거다. p95는 1,258.7ms → 50.5ms → **14.9ms**로 줄었다.

### Phase 3 결과 요약 (L1 — 규모 곡선)

부하 테스트 대신 **`EXPLAIN (ANALYZE, BUFFERS)`로 대체**했다. 보려는 신호가 요청당 8문장 중 1문장이라 부하 테스트로는 희석되기 때문이다.

| `upload_course` | ALL (`theme IS NULL`) | 테마 지정 (`FOOD`) |
|---|---|---|
| 3,000 | 0.043ms | 0.402ms |
| 50,000 | **0.036ms** | **5.781ms** |
| 16.7배 증가 시 | **변화 없음** | **약 14배** |

**두 경로가 정반대로 거동한다.** 기본 조회는 `view_count` 인덱스를 내림차순으로 훑다 5건에서 멈춰 완전히 평탄하다. 테마 조회는 `EXISTS`가 해시 세미조인으로 바뀌며 **`course_keyword` 전체를 매번 Seq Scan**해 선형으로 증가한다(그 테이블에 인덱스가 PK뿐이다).

**따라서 캐싱의 근거는 규모가 아니다.** 테마 경로의 선형 증가는 사실이나 50,000건에서도 5.8ms로 절대값이 작다. 근거는 [ec2-measurement.md](ec2-measurement.md)가 측정한 쪽 — **현재 규모에서 이미** 요청당 SQL 8건 제거(DB 초당 약 7,000쿼리 → 0), 커넥션 점유시간 반감, TPS +116.3% — 이 훨씬 강하다.

### 남은 측정

- **P3 재실행** — 동시 `FLUSHALL` 루프를 빠뜨려 콜드 스탬피드를 측정하지 못했다
- **D3 재측정** — `MAX_RATE=1200`으로는 **A2의 포화점을 찾지 못했다**(제공 1,144 req/s에서 `pending` 0, 워커 11/200)
- **반복 측정** — D2를 제외한 나머지는 각 arm 1회다

## 도구

| 스크립트 | 용도 |
|---|---|
| [phase0-gate.sh](../../../scripts/loadtest/phase0-gate.sh) | 로컬에서 세 arm의 SQL/커넥션 카운터와 응답 동일성을 검증 |
| [switch-arm.sh](../../../scripts/loadtest/switch-arm.sh) | EC2에서 arm 전환(프로필 확인 → 프로퍼티 교체 → 재기동 → 재시딩 → FLUSHALL → 워밍) |
| [seed-popular-large.sql](../../../scripts/sql/seed-popular-large.sql) | 규모 곡선용 `upload_course` 증량 — **3,000건 기준에서만 동작한다.** 규모를 바꿀 때마다 앱을 재기동해(`DB_DDL_AUTO=create`) 스키마를 새로 만든 뒤 목표치로 한 번에 시딩해야 한다([scale-curve.md](scale-curve.md) 참고) |

k6 스크립트는 기존 것을 **수정 없이 재사용**한다(`popular-ramping.js`, `popular-cold.js`, `popular-mixed.js`, `detail-ramping.js`, `detail-arrival-rate.js`). 고치면 과거 실측과의 비교 가능성이 깨진다.

## 전제 — `prod` 프로필 고정

세 arm 모두 `SPRING_PROFILES_ACTIVE=prod`로 띄운다. 지정하지 않으면 `local`로 떠서 SQL과 바인딩 파라미터를 전량 로깅하는데, **로깅 비용은 실행된 쿼리 수에 비례하므로 A0에만 얹혀 캐싱 효과가 과대평가된다.** 과거 실측 12건은 프로필 분리 이전이라 SQL 로깅이 꺼진 상태였고, `prod`가 그와 동등하다.

## 참고 문서

- [redis-caching/README.md](../redis-caching/README.md) — 캐싱 설계와 TTL 정책
- [popular-tx-separation/ec2-measurement.md](../popular-tx-separation/ec2-measurement.md) — A1→A2 구간의 선행 실측(인기 코스)
- [connection-pool-bottleneck/stage0/production/ec2-rds.md](../connection-pool-bottleneck/stage0/production/ec2-rds.md) — A1→A2 구간의 선행 실측(상세 조회)
- [guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) — EC2 분리 환경 실행 절차
