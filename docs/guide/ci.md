# CI 가이드

`dev` 대상 PR과 `dev` push에서 테스트·빌드를 자동 검증하는 GitHub Actions 워크플로
([.github/workflows/ci.yml](../../.github/workflows/ci.yml))에 대한 문서다.

## 1. 무엇을 검증하는가

잡은 하나(`build`)이고 `./gradlew build` 한 줄이 전부다. `build`가 아래를 모두 포함한다.

| 단계 | 검증하는 것 |
|---|---|
| `compileJava` | Java 21로 소스가 빌드되는가 |
| `test` | 단위 테스트(Mockito) + Spring 컨텍스트 기동 4건 + JPA 슬라이스 테스트(H2) |
| `bootJar` | 배포 가능한 아티팩트가 실제로 나오는가 |

`test`만 부르지 않고 `build`를 쓰는 이유는 **패키징까지 확인해야 하기 때문**이다. 테스트가
전부 통과해도 `bootJar` 단계에서 깨지면 배포할 물건이 없다.

이 중 **"Spring 컨텍스트가 뜨는가"의 가치가 특히 크다.** 빈 설정 오류, 순환 참조, 누락된
프로퍼티는 컴파일로 잡히지 않고 서버에 올려야 터지는데, 그걸 PR 단계로 당겨온다. 실제로 CI를
도입하면서 발견한 `.env` 의존 문제(§5)가 정확히 이 범주였다.

**`@Tag("benchmark")` 테스트는 돌지 않는다.** [build.gradle](../../build.gradle)의
`excludeTags 'benchmark'`가 이미 제외하고 있다 — `SigningBenchmarkTest`는 openssl 프로세스
호출과 12,000회 반복 서명으로 느리고, `AiHallucinationBaselineTest`는 실제 `GEMINI_API_KEY`를
요구한다. "빠르고 결정적"이라는 CI의 두 조건을 모두 깨므로 `./gradlew benchmarkTest`로만
명시적으로 돌린다.

## 2. CI가 잡지 못하는 것

CI는 검증의 전부가 아니라 **가장 앞단의 빠른 그물**이다. 나머지는 다른 계층이 책임진다.

| 계층 | 잡는 것 | 시점 |
|---|---|---|
| **CI** | 컴파일 오류, 로직 회귀, 컨텍스트 기동 실패 | PR마다, 수 분 |
| **배포 후 확인** ([profile.md](profile.md) §4-2, [deploy/prod](../../deploy/prod/README.md)) | 프로필 누락, JVM 옵션 미적용, 외부 연동 실패 | 배포 직후, 수동 |
| **부하테스트** ([load-testing.md](load-testing.md), `docs/tasks/`) | 성능 회귀, 자원 한계 | 필요 시, 수십 분 |

특히 **PostgreSQL 전용 네이티브 쿼리는 CI가 검증하지 못한다.** 테스트는 H2를 쓰는데
`UploadCourseRepository`의 조회수 증분 쿼리는 `unnest()`와 `UPDATE ... FROM`을 쓴다
([profile.md](profile.md) §3의 "한계" 참고). 이 쿼리를 고칠 때는 실제 PostgreSQL에서 직접
확인해야 한다.

## 3. CI는 시크릿을 쓰지 않는다

워크플로에 `secrets`도 `env`도 하나 없다. 의도적이다.

| 단계 | 하는 일 | 필요한 시크릿 |
|---|---|---|
| **CI** | 테스트 + JAR 빌드 | **없음** — `test` 프로필이 더미로 자급 |
| CD | JAR → S3 → 배포 | AWS 인증 수단만 |
| 런타임 | EC2 부팅 시 설정 주입 | AWS에만 존재, GitHub을 거치지 않음 |

CI에 실제 값을 넣으면 세 가지가 한꺼번에 무너진다. 테스트가 운영 리소스를 건드리고(예: Gemini
호출 과금), 외부 상태에 의존해 비결정적이 되며, 워크플로를 수정할 수 있는 사람 모두에게 값이
노출된다. **CI가 시크릿을 요구하게 되는 순간이 곧 설계가 틀어진 신호다.**

그래서 테스트에 필요한 설정은 [application-test.yml](../../src/test/resources/application-test.yml)이
직접 공급한다. 공통 `application.yml`에 `${VAR:기본값}` 형태로 기본값을 박는 방식은 쓰지
않는다 — 그러면 배포에서 환경변수가 빠져도 앱이 조용히 더미로 떠서, 누락을 반드시 드러내는
[profile.md](profile.md) §4의 fail-fast 원칙과 충돌한다.

**테스트에 새 환경변수가 필요해지면 `application-test.yml`에 더미값을 함께 넣는다.** 이걸
빠뜨리면 로컬(`.env`가 있어서)에서는 통과하고 CI에서만 깨진다.

## 4. CI는 배포 환경과 독립적이다

이 프로젝트의 운영 환경은 데모·측정 시에만 올리는 **온디맨드 모델**이라 대부분의 기간
내려가 있다. CI는 러너 안에서 소스만 다루므로 **서버 가동 여부와 무관하게 항상 돈다.**

이 경계가 무너지면(CI가 실제 서버나 DB에 붙으면) 서버가 꺼진 기간 내내 CI가 빨간불이 되고,
사람들이 빨간불을 무시하기 시작하면서 진짜 버그도 함께 묻힌다. §3의 원칙이 보안뿐 아니라
**운영 모델 때문에도 필요한** 이유다.

## 5. 실패했을 때

### 로컬에서 CI 환경을 그대로 재현하는 법

`.env`를 잠시 치우고 돌리면 된다. CI 러너에는 `.env`가 없다(git 미추적).

```bash
mv .env .env.tmpbak && ./gradlew clean test; mv .env.tmpbak .env
```

> `&&`가 아니라 `;`로 복원을 연결한 것은 의도적이다. 테스트가 실패해도 `.env`는 반드시
> 되돌아와야 한다.

### 흔한 원인

| 증상 | 원인 |
|---|---|
| `Could not resolve placeholder 'XXX'` | 공통 `application.yml`에 새 `${VAR}`가 늘었는데 `application-test.yml`에 더미값을 안 넣었다 (§3) |
| 로컬은 통과, CI만 실패 | 거의 항상 위와 같은 원인이다. 위 재현 명령으로 로컬에서 확인된다 |
| `permission denied: ./gradlew` | `gradlew`의 git 파일 모드가 `100644`로 되돌아갔다. `git update-index --chmod=+x gradlew` |

## 6. 브랜치 보호

`dev`에 걸린 규칙은 세 가지다.

| 규칙 | 목적 |
|---|---|
| Required status checks (`build`) | CI가 통과해야 머지 가능 |
| Block force push | `dev`에 쌓인 실측 기록·문서를 히스토리 덮어쓰기로부터 보호 |
| Block deletion | 기본 브랜치가 바뀔 때를 대비 (현재는 GitHub이 이미 차단) |

리뷰 승인 필수와 include administrators는 **켜지 않았다.** 전자는 자기 PR을 자기가 승인할 수
없어 1인 작업 시 머지가 영구 차단되고, 후자는 히스토리에 섞인 시크릿 제거 같은 긴급 상황의
우회 경로를 막는다. 이 규칙들은 악의적 행위 방어가 아니라 **실수 방지 장치**다.

보호는 `dev`에만 걸리므로 feature 브랜치의 rebase·force push는 그대로 자유롭다.

## 7. 알아둘 한계

**CloudFront 테스트 키는 Gradle이 만든다.** `CloudFrontService`는 `@PostConstruct`에서 개인키
PEM을 즉시 읽고 실패하면 컨텍스트가 뜨지 않는다. 그 키를 저장소에 커밋하지 않고
[build.gradle](../../build.gradle)의 `generateTestCloudFrontKey` 태스크가 빌드마다 생성한다
(ECDSA P-256, PKCS8) — public 저장소에 개인키 형태의 파일을 두면 secret scanning 경보가 뜨고,
어떤 CloudFront 키 그룹과도 연결되지 않은 일회용 더미인데도 오해를 사기 때문이다.

부작용으로, **IDE가 Gradle에 위임하지 않고 테스트를 직접 실행하면** `build/test-keys/`에 키가
없어 컨텍스트 로딩이 실패할 수 있다. `./gradlew test`를 한 번 돌리면 파일이 남아 이후에는
문제되지 않는다. CI는 항상 Gradle을 거치므로 영향이 없다.
