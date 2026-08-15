# CLAUDE.md

이 파일은 이 저장소에서 Claude Code(claude.ai/code)가 작업할 때 참고하는 가이드다.

## 프로젝트 개요

"너의 여행은 (YOURTRIP)"은 AI 추천과 코스 공유 기능으로 여행 계획의 번거로움을 줄여주는 여행 코스 생성 & 공유 안드로이드 앱이다. 사용자는 AI가 생성한 추천 코스를 받거나 직접 코스를 만들고, 다른 사용자의 코스를 fork해서 커스터마이징하며, 여행 사진과 후기를 피드로 공유할 수 있다.

이 레포지토리는 그중 **Spring Boot 백엔드**를 담당한다.

- 전체 프로젝트 소개(팀 구성, 화면 구성, FE 포함 전체 아키텍처): `C:\YOURTRIP\README.md`
- Android FE 레포: https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_FE
- Swagger 문서(배포): https://yourtrip.site/swagger-ui/index.html

## 레포/모듈 구조

Gradle 모듈이 레포 루트에 바로 있다(`build.gradle`, `settings.gradle`, `gradlew`, `src/` 모두 루트 기준).

```
YOURTRIP_BE/
├── README.md                    # placeholder (실질적 내용 없음)
├── .github/                     # 이슈/PR 템플릿만 존재, CI workflow 없음
├── build.gradle
├── settings.gradle               # rootProject.name = 'yourtrip'
├── gradlew / gradlew.bat
├── docker-compose.yml            # 로컬 Redis + Prometheus + Grafana
├── prometheus.yml                # 앱 /actuator/prometheus 스크레이프 설정
├── docs/                         # guide/(운영·측정 가이드), tasks/(작업별 설계·실측 기록)
├── scripts/                      # k6 부하테스트 스크립트, Grafana provisioning, 시드 SQL
├── terraform/                    # loadtest/ 부하테스트 인프라(EC2·RDS·ElastiCache)
├── src/main/resources/           # application.yml + application-{local,prod}.yml (아래 "애플리케이션 프로필")
├── src/test/resources/           # application-test.yml (H2 인메모리 DB)
└── src/main/java/backend/yourtrip/
    ├── domain/
    │   ├── feed/             # 피드(여행 사진/후기) CRUD, 댓글, 좋아요, 해시태그
    │   ├── mycourse/         # 내가 만든 코스(day별 일정, 장소) CRUD
    │   ├── mypage/           # 마이페이지 — 자체 entity/repository 없이 user 도메인 것을 재사용
    │   ├── uploadcourse/     # 업로드(공개)된 코스, fork/좋아요/조회수 등
    │   └── user/             # 이메일 회원가입/로그인/JWT 발급 (카카오 로그인은 제거됨)
    └── global/
        ├── cloudfront/       # CloudFront Signed URL 발급·무효화 (CloudFrontService)
        ├── common/           # BaseEntity (공통 응답 래퍼는 없음, 아래 "예외 처리" 참고)
        ├── config/           # SecurityConfig, SwaggerConfig, WebConfig, AsyncConfig, RedisConfig, RedisCacheErrorHandler
        ├── converter/        # OctetStreamReadMsgConverter
        ├── exception/        # BusinessException, GlobalExceptionHandler, errorCode/*
        ├── gemini/           # Gemini AI 코스 추천 연동 (config/dto/service)
        ├── jwt/              # JwtAuthenticationFilter, JwtTokenProvider
        ├── kakao/            # 카카오 지도/장소 검색 클라이언트 (KakaoLocalClient, AI 코스 생성 시 장소 보정용)
        ├── mail/             # 이메일 인증 발송 (MailService, MailLog)
        ├── s3/               # AWS S3 업로드/다운로드 (S3Config, S3Service)
        └── security/         # CustomUserDetails
```

**표준 레이어링 패턴**: `controller` → `service`(인터페이스 + `*ServiceImpl`) → `repository`(Spring Data JPA) → `entity`, 그 옆에 `dto/request`·`dto/response`, `mapper`(entity↔DTO 변환).

예시(`domain/feed`):
- `controller/FeedController.java` + `FeedControllerSpec.java` (Swagger 어노테이션 전용 인터페이스 — `mycourse`, `uploadcourse`에도 동일 패턴 존재, `mypage`/`user`에는 없음)
- `service/FeedService.java` + `FeedServiceImpl.java`
- `repository/FeedRepository.java`, `CommentRepository.java`, `FeedLikeRepository.java`, `HashtagRepository.java`
- `entity/Feed.java`, `Comment.java`, `FeedLike.java`, `FeedMedia.java`, `Hashtag.java`
- `mapper/FeedMapper.java`

## 기술 스택

- **Language**: Java 21 (IntelliJ 프로젝트 설정 기준)
- **Framework**: Spring Boot 3.5.7, Spring MVC, Spring WebFlux(WebClient 용도 — 리액티브 컨트롤러 아님)
- **Build**: Gradle 8.14.3 (wrapper 포함)
- **Security**: Spring Security + JWT(`io.jsonwebtoken:jjwt` 0.11.5) 기반 인증/인가
- **DB**: PostgreSQL + Spring Data JPA (Hibernate) — 테스트만 H2 인메모리(PostgreSQL 호환 모드)
- **캐시**: Redis (Spring Data Redis + Lettuce) — 인기 코스 목록/상세 캐싱, 조회수 카운터, 랭킹 갱신 분산 락. 캐시 실패는 `RedisCacheErrorHandler`가 잡아 DB 폴백으로 처리한다
- **모니터링**: Spring Boot Actuator + Micrometer Prometheus (`/actuator/prometheus`) — 로컬은 docker-compose의 Prometheus/Grafana로 관측 ([MONITORING-GUIDE.md](docs/guide/MONITORING-GUIDE.md))
- **AI**: Gemini (`com.google.genai:google-genai` 1.28.0) — 코스 추천 생성
- **외부 연동**: Kakao(자체 WebClient 클라이언트, 전용 SDK 없음 — 지도/장소 검색만 사용, 카카오 로그인은 제거됨), AWS SDK v2(S3 + CloudFront Signed URL), Spring Mail
- **문서화**: springdoc-openapi 2.6.0 (Swagger UI: `/swagger-ui.html`)

## 빌드 및 실행

```bash
docker compose up -d redis
./gradlew bootRun
```

**Redis가 떠 있지 않으면** 캐시 경로가 전부 실패해 DB 폴백으로 동작한다(앱은 뜨지만 `WARN`이 대량으로 쌓인다). 모니터링까지 함께 보려면 `docker compose up -d`로 Prometheus/Grafana도 띄운다.

필수 환경변수는 **[.env.example](.env.example)이 정본**이다(DB·JWT·Mail·S3·CloudFront·Kakao·Gemini·Redis 계열). 이 파일을 복사해 레포 루트에 `.env`를 만들면, `spring-dotenv`가 자동으로 읽어 주입하므로 셸에서 `export`할 필요 없이 `./gradlew bootRun`이 바로 동작한다(실제 OS 환경변수가 있으면 그 값이 항상 우선). 기본 포트는 8080.

`src/main/resources/data.sql`은 로컬 개발용 시드 데이터(사용자/코스/일정/장소 샘플)이며 현재 git에 커밋되지 않은 상태다.

### 테스트

```bash
./gradlew test
```

테스트는 H2 인메모리 DB를 쓰므로 `.env`나 로컬 PostgreSQL/Redis 없이도 돈다(아래 "애플리케이션 프로필" 참고). 서명 비용 마이크로벤치마크(`@Tag("benchmark")`)는 느려서 **일반 빌드에서 제외**돼 있고, 별도 태스크로만 실행한다.

```bash
./gradlew benchmarkTest
```

## 애플리케이션 프로필

설정은 **공통(`application.yml`) + 프로필별 파일**로 나뉜다. 프로필은 `local`(기본) / `prod`(배포) / `test`(테스트, H2 인메모리 DB) 셋이며, `local`·`prod`는 `src/main/resources/`, `test`는 `src/test/resources/`에 있다.

- **환경에 따라 달라지는 설정은 공통 파일이 아니라 프로필 파일에 넣는다.** 공통 파일에 넣으면 그대로 배포로 샌다.
- **프로필을 지정하지 않으면 `local`로 뜬다**(`spring.profiles.default`). 로컬은 `./gradlew bootRun`만 실행하면 된다.
- **배포 서버는 `SPRING_PROFILES_ACTIVE=prod`가 필수다.** 누락되면 앱이 정상 동작하는 것처럼 보이면서 조용히 `local`로 떠서 SQL을 전량 로깅한다.
- **`@SpringBootTest`에는 반드시 `@ActiveProfiles("test")`를 붙인다.** 빠뜨리면 그 테스트만 `local`로 떠서 `.env`의 개발용 PostgreSQL에 붙고, `DB_DDL_AUTO=create` 탓에 **개발 DB의 테이블이 drop/create 된다.** `@ExtendWith(MockitoExtension.class)` 단위 테스트는 컨텍스트를 안 띄우므로 대상이 아니다.

각 프로필의 설정 근거, 배포 적용·확인 절차, H2의 한계는 [docs/guide/PROFILE-GUIDE.md](docs/guide/PROFILE-GUIDE.md)에 정리돼 있다. 개별 설정을 왜 그렇게 뒀는지는 해당 yml 파일의 주석에도 적혀 있다.

## 예외 처리 구조

- `global/exception/BusinessException.java` — `ErrorCode`를 감싸는 RuntimeException
- `global/exception/errorCode/ErrorCode.java` — `getMessage()`/`getStatus()`를 정의하는 인터페이스, 도메인별 `*ErrorCode` enum이 구현
- `global/exception/GlobalExceptionHandler.java` — `@RestControllerAdvice`로 `BusinessException`, `MethodArgumentNotValidException`, `HttpMessageNotReadableException` 처리

현재 **공통 응답 래퍼 클래스(`ApiResponse<T>` 등)는 없다** — 성공 응답은 컨트롤러가 raw DTO를 그대로 반환하고, 실패 응답은 예외 핸들러 내부에서 `Map.of(...)`로 즉석 구성한다.

## 커밋 / 이슈 / PR 규칙

커밋, 브랜치, 이슈, PR을 작성할 때는 아래 규칙 문서를 따른다:

- [.claude/rules/commit.md](.claude/rules/commit.md) — 커밋 메시지 포맷, 커밋/푸시 승인 절차, 브랜치 네이밍
- [.claude/rules/issue.md](.claude/rules/issue.md) — 이슈 제목/본문 작성 규칙 (`.github/ISSUE_TEMPLATE/issue_template.md` 기준)
- [.claude/rules/pull-request.md](.claude/rules/pull-request.md) — PR 제목/본문 작성 규칙 (`.github/PULL_REQUEST_TEMPLATE.md` 기준)

## worktree 작업 규칙

이 저장소는 `git worktree`로 브랜치를 분리해 작업한다. 실행에 필요한 파일 상당수(`.env`, terraform state·키, 이 파일과 `.claude/`)가 `.gitignore` 대상이라 **git이 아니라 [.worktreeinclude](.worktreeinclude) 목록 + `post-checkout` 훅의 복사로만 공유된다.**

훅은 **메인 저장소 → 새 worktree 단방향**이고 **기존 파일을 덮어쓰지 않는다.** 따라서:

- worktree에서 gitignore된 파일(`.env`, `CLAUDE.md`, `.claude/rules/*` 등)을 새로 만들거나 수정했으면 **메인 저장소 사본도 함께 갱신한다.** 그러지 않으면 그 변경은 이 worktree에만 남는다.
- 작업 중 새로 생긴 gitignore 파일이 다른 worktree에도 필요하면 `.worktreeinclude`에 등록한다(이 파일은 git 추적 대상이라 커밋된다).

판단 기준·함정·비교 방법은 [docs/guide/WORKTREE-GUIDE.md](docs/guide/WORKTREE-GUIDE.md)에 정리돼 있다.

## 인프라(terraform) 변경 규칙

부하테스트 인프라(`terraform/loadtest/`)는 원격 backend 없이 **로컬 `terraform.tfstate` 하나가 유일한 진실 공급원**이다. 따라서:

- **리소스의 형상을 콘솔이나 AWS CLI로 직접 바꾸지 않는다.** `terraform.tfvars`/`.tf`를 고치고 `plan`으로 영향(특히 `must be replaced`)을 확인한 뒤 `apply`한다. terraform을 우회한 변경은 state에 남지 않아, 나중에 `destroy`해도 실제 리소스가 지워지지 않고 과금이 계속되는 drift가 된다(실제 발생 사례가 README에 있다).
- **실행 상태만 바꾸는 조작은 CLI로 해도 된다** — 인스턴스 start/stop, 측정용 임시 보안그룹 규칙(끝나면 회수). 형상이 아니라서 drift가 생기지 않는다.
- **이미 어긋났다면 `terraform import`로 정합화한다.** 리소스를 살려둔 채 state만 맞추므로 배포물·시드가 보존된다. 단 `plan` 확인은 인스턴스가 **running일 때** 해야 한다(stopped면 퍼블릭 IP 해제 때문에 불필요한 `replace`가 뜬다).
- `terraform.tfstate`·`terraform.tfvars`는 `.gitignore` 대상이므로, worktree에서 바뀌었으면 위 worktree 규칙에 따라 메인 워킹트리 사본도 갱신한다.

자세한 절차와 실제 사고 사례는 [terraform/loadtest/README.md](terraform/loadtest/README.md)의 "인프라 변경은 반드시 terraform을 거친다" 절에 있다.

## 작업 방식 (포트폴리오 저장소 특성)

이 저장소는 **작업 속도가 우선순위가 아니다**. 다음을 반드시 지킨다.

- 작업을 항상 상세한 단계로 나누어 진행한다. 여러 변경을 한 번에 뭉뚱그려 처리하지 않는다.
- 각 단계에서 무엇을, 왜 했는지에 대한 설명을 생략하지 않는다. "간단히 처리했습니다" 같은 축약된 요약으로 끝내지 말고, 실제 변경 내용과 근거를 구체적으로 설명한다.
- 이는 면접에서 기술적 의사결정 과정을 설명할 수 있어야 하는 포트폴리오 목적과 직결된다.

**[중요]** 작업 중 단순 구현을 넘어 포트폴리오에서 어필 가능한 개선점(테스트/CI 강화, 코드 품질 자동화, 아키텍처 개선, 성능 개선 사항, 트러블슈팅, 기술적 의사결정 포인트 등)을 발견하면 **코드를 임의로 고치지 말고**, 최종 응답에서 제안 형태로 전달한다.

요청받은 작업 범위를 벗어나는 이런 개선은 먼저 구현하지 말고, 무엇을 왜 개선하면 좋을지 응답 말미에 정리해 사용자가 채택 여부를 판단하게 한다.
