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
| Phase 1 — EC2 인기 코스 (P1·P3·P5) | `ec2-measurement.md` | 대기 |
| Phase 2 — EC2 상세 조회 (D2·D3) | `ec2-measurement.md` | 대기 |
| Phase 3 — 규모 곡선 (L1) | `scale-curve.md` | 대기 |

### Phase 0 결과 요약

| arm | endpoint | SQL/req | 커넥션 대여/req |
|---|---|---|---|
| A0 | popular / detail | 8.000 / 4.000 | 1.000 |
| **A1** | popular / detail | **0.000** | **1.000** |
| A2 | popular / detail | 0.000 | 0.000 |

**A1에서 "SQL 0건 + 커넥션 대여 1.000회"가 재현됐다** — H2가 말한 "일은 하나도 안 하면서 커넥션만 점유"다. 세 arm의 응답 본문도 동일해 비교가 성립한다.

측정 중 **미스 경로의 N+1**을 발견했다(`travelCourse`·`user` LAZY 접근). 기존 코드에 원래 있던 것이고 `generate_statistics`가 꺼져 있어 보이지 않던 것이다. 자세한 내용은 Phase 0 문서 참고.

## 도구

| 스크립트 | 용도 |
|---|---|
| [phase0-gate.sh](../../../scripts/loadtest/phase0-gate.sh) | 로컬에서 세 arm의 SQL/커넥션 카운터와 응답 동일성을 검증 |
| [switch-arm.sh](../../../scripts/loadtest/switch-arm.sh) | EC2에서 arm 전환(프로필 확인 → 프로퍼티 교체 → 재기동 → 재시딩 → FLUSHALL → 워밍) |
| [seed-popular-large.sql](../../../scripts/sql/seed-popular-large.sql) | 규모 곡선용 `upload_course` 증량 |

k6 스크립트는 기존 것을 **수정 없이 재사용**한다(`popular-ramping.js`, `popular-cold.js`, `popular-mixed.js`, `detail-ramping.js`, `detail-arrival-rate.js`). 고치면 과거 실측과의 비교 가능성이 깨진다.

## 전제 — `prod` 프로필 고정

세 arm 모두 `SPRING_PROFILES_ACTIVE=prod`로 띄운다. 지정하지 않으면 `local`로 떠서 SQL과 바인딩 파라미터를 전량 로깅하는데, **로깅 비용은 실행된 쿼리 수에 비례하므로 A0에만 얹혀 캐싱 효과가 과대평가된다.** 과거 실측 12건은 프로필 분리 이전이라 SQL 로깅이 꺼진 상태였고, `prod`가 그와 동등하다.

## 참고 문서

- [redis-caching/README.md](../redis-caching/README.md) — 캐싱 설계와 TTL 정책
- [popular-tx-separation/ec2-measurement.md](../popular-tx-separation/ec2-measurement.md) — A1→A2 구간의 선행 실측(인기 코스)
- [connection-pool-bottleneck/stage0/production/ec2-rds.md](../connection-pool-bottleneck/stage0/production/ec2-rds.md) — A1→A2 구간의 선행 실측(상세 조회)
- [guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) — EC2 분리 환경 실행 절차
