# 문서 인덱스

문서는 **갱신 의무**를 기준으로 나눈다.

| 디렉터리 | 성격 | 갱신 의무 |
|---|---|---|
| [`guide/`](#guide--절차와-방법) | 반복해서 다시 보는 절차·방법 | **있다.** 항상 현재 사실이어야 한다 — 틀리면 사람이 잘못된 절차를 밟는다 |
| [`tasks/`](#tasks--작업별-설계와-실측-기록) | 특정 작업의 설계·측정·트러블슈팅 기록 | 없다. 그 시점의 기록이므로 나중에 사실과 달라져도 정상이다 |
| [`api-changes/`](#api-changes--fe-대상-api-변경-고지) | FE에 알려야 하는 API 변경 | 없다. 변경 시점의 고지문이다 |

## `guide/` — 절차와 방법

| 문서 | 내용 |
|---|---|
| [ci.md](guide/ci.md) | GitHub Actions CI — 검증 범위와 한계, 시크릿을 쓰지 않는 이유, 실패 시 재현·대응 |
| [profile.md](guide/profile.md) | Spring 프로필(`local`/`prod`/`test`) 구성 원칙, 배포 서버 적용·확인 절차 |
| [worktree.md](guide/worktree.md) | `git worktree`에서 gitignore된 파일을 다루는 법, `.worktreeinclude` 메커니즘 |
| [monitoring.md](guide/monitoring.md) | Prometheus·Grafana 구축과 사용 |
| [load-testing.md](guide/load-testing.md) | k6 + Actuator + Hibernate Statistics 부하 테스트, JFR 프로파일링 |
| [ec2-rds-loadtest.md](guide/ec2-rds-loadtest.md) | EC2 + RDS + ElastiCache 분리 환경에서의 부하테스트 실행 절차 |

## `tasks/` — 작업별 설계와 실측 기록

### 인기 코스 조회 성능 개선

한 갈래로 이어지는 작업들이라 **착수 순서대로** 나열한다. 사슬의 시작점은 아래 "커넥션 풀 / presigned URL 병목"의 병목 발견이고, 각 문서 머리말의 "이 작업의 위치" 블록이 같은 사슬을 가리킨다.

| 문서 | 내용 |
|---|---|
| [tasks/redis-caching/](tasks/redis-caching/README.md) | **진입점** — 캐싱 전략(설계 원칙·계획)과 개별 작업 기록 5건 |
| [tasks/popular-tx-separation/](tasks/popular-tx-separation/README.md) | 캐시 경로를 트랜잭션 밖으로 분리하고 EC2에서 실측 |
| [tasks/cache-effect-measurement/](tasks/cache-effect-measurement/README.md) | 캐싱 효과를 A0·A1·A2 세 arm으로 분리 측정(41 run) — 요청당 SQL 8 → 0문장, TPS +172%. 규모 곡선·Redis I/O 병목 규명 포함 |
| [tasks/popular-n-plus-one/](tasks/popular-n-plus-one/README.md) | 인기 코스 아이템 조회의 N+1 제거 — to-one 연관 LAZY 전환으로 요청당 SQL 8 → 2문장 |
| [tasks/popular-theme-index/](tasks/popular-theme-index/README.md) | 테마 지정 조회의 선형 증가 제거 — 랭킹 쿼리 분리 + `course_keyword` 복합 인덱스로 50,000건 5.4ms → 0.05ms |

### 런타임 사이징 — Tomcat 스레드와 JVM 힙

배포 타겟(t3.small, vCPU 2 / 2GB) 위에서 워커 수와 힙 상한을 실측으로 정하는 작업들이다. 앞의 인기 코스 사슬에서 "Redis 대기가 아니라 CPU 낭비였다"가 규명되면서 갈라져 나왔다.

| 문서 | 내용 |
|---|---|
| [tasks/tomcat-thread-sizing/](tasks/tomcat-thread-sizing/README.md) | `server.tomcat.threads.max` 200 → 32 — VU 200에서 TPS +16%, p95 -33%. 요청당 CPU 26% 감소가 전환 횟수인지 캐시 지역성인지의 분해까지 |
| [tasks/jvm-heap-sizing/](tasks/jvm-heap-sizing/README.md) | `-Xmx448m`이 t3.micro(1GB)·maxThreads 200 전제로 잡힌 값이라 t3.small(2GB) 기준으로 768m으로 재산정 — 힙 밖 165MB의 NMT 분해와, 천장을 올려도 G1이 대개 커밋을 늘리지 않는다는 실측 포함 |

### 운영 인프라

| 문서 | 내용 |
|---|---|
| [tasks/prod-infra-iac/](tasks/prod-infra-iac/README.md) | 운영 인프라를 ALB + ASG + RDS + ElastiCache로 IaC화 — 수명 기준 state 분리, 시크릿의 SSM 이관, 요청 수 기반 스케일 임계값 산정. 사전 등록한 판정 기준 12개를 실측으로 전부 통과([verification.md](tasks/prod-infra-iac/verification.md))하고 스케일아웃까지 실증 |

### 커넥션 풀 / presigned URL 병목

| 문서 | 내용 |
|---|---|
| [tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK.md](tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK.md) | presigned URL CPU 병목 가설의 실측 검증 |
| [tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK-FIX.md](tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK-FIX.md) | 병목 해소 계획 |
| [tasks/connection-pool-bottleneck/stage0/](tasks/connection-pool-bottleneck/stage0/) | 0단계 — 인덱스 추가·트랜잭션 분리의 로컬/EC2 실측, CallerRuns·AbortPolicy 검증 |
| [tasks/connection-pool-bottleneck/stage1/](tasks/connection-pool-bottleneck/stage1/) | 1단계 — Signed Cookie 기각과 Custom Policy 채택, Run D~I 측정 |
| [tasks/TASK-CLOUDFRONT.md](tasks/TASK-CLOUDFRONT.md) | presigned URL → CloudFront 전환과 도입 전/후 성능 측정 |

### AI 코스 생성

| 문서 | 내용 |
|---|---|
| [tasks/ai-course-create/멀티-에이전트-파이프라인.md](tasks/ai-course-create/멀티-에이전트-파이프라인.md) | **멀티 에이전트 파이프라인 설계 (허브)** — 배경·설계 원칙·전체 구조·도입 순서. 상세는 `design/`·`decisions/`로 나뉜다 |
| [tasks/ai-course-create/ROADMAP.md](tasks/ai-course-create/ROADMAP.md) | 위 설계의 실행 로드맵 — 단계별 체크리스트와 완료 판정 기준 |
| [tasks/ai-course-create/steps/](tasks/ai-course-create/steps/) | 단계별 실행 기록과 그 과정에서 뒤집힌 판단 |
| [tasks/ai-course-create/hallucination/](tasks/ai-course-create/hallucination/) | 코스 생성 환각률 측정 — Gemini baseline(지어냄률 9.6%)과 산출물 재분석·지표 재정립 경위 |

## `api-changes/` — FE 대상 API 변경 고지

| 문서 | 내용 |
|---|---|
| [popular-courses.md](api-changes/popular-courses.md) | 인기 코스 상위 5개 조회 |
| [upload-course-update.md](api-changes/upload-course-update.md) | 업로드 코스 통합 수정 |
| [mycourse-remove-participant-fields.md](api-changes/mycourse-remove-participant-fields.md) | 나의 코스 API에서 `memberCount`·`role` 제거 |

## 저장소 다른 곳의 문서

- [../CLAUDE.md](../CLAUDE.md) — 프로젝트 개요, 기술 스택, 작업 규칙(에이전트용 진입점)
- [../.claude/rules/](../.claude/rules/) — 커밋·이슈·PR 작성 규칙
- [../terraform/README.md](../terraform/README.md), [../terraform/loadtest/README.md](../terraform/loadtest/README.md) — 인프라 구성과 운영 절차
- [../deploy/prod/README.md](../deploy/prod/README.md) — 운영 서버의 JVM 기동 옵션(값·산정 근거·적용/확인 절차)
