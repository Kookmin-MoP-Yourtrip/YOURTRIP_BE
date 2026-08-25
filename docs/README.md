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
| [profile.md](guide/profile.md) | Spring 프로필(`local`/`prod`/`test`) 구성 원칙, 배포 서버 적용·확인 절차 |
| [worktree.md](guide/worktree.md) | `git worktree`에서 gitignore된 파일을 다루는 법, `.worktreeinclude` 메커니즘 |
| [monitoring.md](guide/monitoring.md) | Prometheus·Grafana 구축과 사용 |
| [load-testing.md](guide/load-testing.md) | k6 + Actuator + Hibernate Statistics 부하 테스트, JFR 프로파일링 |
| [ec2-rds-loadtest.md](guide/ec2-rds-loadtest.md) | EC2 + RDS + ElastiCache 분리 환경에서의 부하테스트 실행 절차 |

## `tasks/` — 작업별 설계와 실측 기록

### Redis 캐싱

| 문서 | 내용 |
|---|---|
| [tasks/redis-caching/](tasks/redis-caching/README.md) | **진입점** — 캐싱 전략(설계 원칙·계획)과 개별 작업 기록 5건 |
| [tasks/popular-tx-separation/](tasks/popular-tx-separation/README.md) | 후속 작업 — 캐시 경로를 트랜잭션 밖으로 분리하고 EC2에서 실측 |

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
