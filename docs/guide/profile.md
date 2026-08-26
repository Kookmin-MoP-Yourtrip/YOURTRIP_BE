# Spring 프로필 가이드

애플리케이션 설정은 **공통 파일 + 프로필별 파일**로 나뉜다. 이 문서는 프로필 구성 원칙과
환경별(로컬 / 테스트 / 배포) 사용법을 다룬다.

## 1. 프로필 구성

| 파일 | 프로필 | 역할 |
|---|---|---|
| [application.yml](../../src/main/resources/application.yml) | (공통) | DB, Redis, S3, JWT 등 환경 무관 설정 |
| [application-local.yml](../../src/main/resources/application-local.yml) | `local` | 앱 패키지 `DEBUG` + SQL/바인딩 파라미터 로그 |
| [application-prod.yml](../../src/main/resources/application-prod.yml) | `prod` | root `INFO` 유지, 시끄러운 서드파티만 `WARN` |
| [application-test.yml](../../src/test/resources/application-test.yml) | `test` | H2 인메모리 DB + `prod`와 동일한 로깅 |

Spring Boot는 `application.yml`을 **먼저 읽고 그 위에 프로필 파일을 덮어쓴다**(대체가 아니라 override).

### 작성 원칙

- **환경에 따라 달라지는 설정은 공통 파일이 아니라 프로필 파일에 넣는다.** 공통 파일에 넣으면
  그대로 배포로 샌다.
- **프로필 파일에는 차이나는 것만 둔다.** DB/Redis/S3 같은 공통 설정을 복붙하면 두 프로필이
  갈라지는 순간 원인 추적이 어려워진다.

### 프로필을 지정하지 않으면 `local`로 뜬다

`application.yml`의 `spring.profiles.default: local` 때문이다. 로컬 개발자가 아무 설정 없이
`./gradlew bootRun`만 쳐도 되게 하려는 의도적인 선택이다.

`spring.profiles.active`가 아니라 `default`를 쓴 이유는, `active`에 기본값을 박으면 배포에서
`SPRING_PROFILES_ACTIVE=prod`를 줬을 때 덮어쓰기 동작이 헷갈리기 때문이다. `default`는
"활성 프로필이 하나도 없을 때만" 쓰인다는 의미가 명확하다.

### 설정에 얽힌 결정 두 가지

- **`hibernate.dialect`는 일부러 명시하지 않는다.** Hibernate가 JDBC 연결 메타데이터로
  `PostgreSQLDialect`를 자동 선택하며, 명시하면 기동할 때마다 `HHH90000025` 경고가 뜬다.
  제거 후에도 PostgreSQL 전용 구문(`set client_min_messages`, `fetch first ? rows only`)이
  그대로 생성됨을 확인했다.
- **SQL 로그는 `spring.jpa.show-sql`이 아니라 `logging.level.org.hibernate.SQL`로 켠다.**
  `show-sql`은 SQL을 `System.out`으로 직접 출력해 logback을 아예 거치지 않아, 레벨·포맷·출력
  대상 제어가 전부 안 먹는다.

---

## 2. 로컬 개발

별도 조치가 필요 없다. 프로필 미지정이 곧 `local`이다.

```bash
./gradlew bootRun
```

기동 로그에 `No active profile set, falling back to 1 default profile: "local"`이 찍힌다.
앱 패키지 `DEBUG`와 SQL·바인딩 파라미터 로그가 켜져 있어 어떤 쿼리가 어떤 값으로 나가는지 보인다.

로컬에서 `prod` 설정을 흉내내려면 셸 환경변수로 준다(`.env`에 쓰는 방식은 §4-4-③ 참고).

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

---

## 3. 테스트

### `@SpringBootTest`에는 반드시 `@ActiveProfiles("test")`를 붙인다

빠뜨리면 그 테스트만 `local`로 떠서 **`.env`의 개발용 PostgreSQL에 접속하고, `DB_DDL_AUTO=create`
탓에 개발 DB의 테이블이 drop 후 재생성된다.** 즉 테스트를 돌릴 때마다 로컬 시드 데이터가 사라진다
(실제로 그런 상태였고, `test` 프로필을 만들면서 끊었다). 부수적으로 테스트 출력도 SQL 로그로 뒤덮인다.

`@ExtendWith(MockitoExtension.class)` 단위 테스트는 Spring 컨텍스트를 띄우지 않으므로 대상이 아니다.

### H2 인메모리 DB

`test` 프로필은 H2를 **PostgreSQL 호환 모드**로 띄운다. 테스트는 `.env`나 로컬 DB와 무관하게 돈다.

```
jdbc:h2:mem:yourtrip;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1
```

| 옵션 | 이유 |
|---|---|
| `MODE=PostgreSQL` | 엔티티의 `columnDefinition`(`TEXT`, `int default 0`, `boolean default false`)은 Hibernate가 가공 없이 DDL에 넣으므로 PG 호환 모드가 필수다 |
| `DATABASE_TO_LOWER=TRUE` | PG는 따옴표 없는 식별자를 소문자로, H2는 대문자로 접는다 |
| `DB_CLOSE_DELAY=-1` | 마지막 커넥션이 닫히는 순간 인메모리 DB가 사라진다. HikariCP가 유휴 커넥션을 정리할 때 스키마가 날아가는 것을 막는다 |

`spring.sql.init.mode: never`도 함께 둔다. 기본값 `embedded`는 **H2를 대상으로 삼아**
`src/main/resources/data.sql`(465줄, PostgreSQL용 시드)을 자동 실행하는데,
`defer-datasource-initialization`이 꺼져 있어 Hibernate가 테이블을 만들기 전에 INSERT를 때려
확정적으로 실패한다. 지금까지는 PostgreSQL이 embedded가 아니라 실행되지 않았을 뿐이다.

전환 후 생성된 테이블 13개가 PostgreSQL과 완전히 일치함을 확인했다.

### 한계: PostgreSQL 전용 네이티브 쿼리는 검증할 수 없다

[UploadCourseRepository](../../src/main/java/backend/yourtrip/domain/uploadcourse/repository/UploadCourseRepository.java)의
조회수 증분 쿼리는 `unnest()`(PG 배열 함수)와 `UPDATE ... FROM`(PG 확장 문법)을 쓴다.
H2 PostgreSQL 모드가 이를 받아준다는 보장이 없다.

현재 이 쿼리를 실행하는 테스트가 없어 전환 자체는 막히지 않았지만, **앞으로도 통합 테스트로
검증할 수 없는 상태다.** 청크당 DB 왕복 1회를 보장하는 성능 핵심 로직이므로, 이 쿼리를 고칠 때는
실제 PostgreSQL에서 직접 확인해야 한다. Testcontainers 대신 H2를 택한 대가다.

---

## 4. 배포

> **운영 서버는 이제 Terraform이 관리하고, 새 코드는 CD가 내보낸다**([terraform/prod/](../../terraform/prod/README.md) #119,
> [cd.md](cd.md) #120). 아래 수동 절차는 **이미 떠 있는 서버를 손으로 고쳐야 할 때만** 쓴다 —
> 인프라의 정상 경로는 `terraform apply`, 코드의 정상 경로는 `dev` 머지이고, 프로필은
> user-data가 `/opt/app/.env`에 넣으므로 어느 쪽에서도 별도 조치가 필요 없다.

배포 서버에는 **`SPRING_PROFILES_ACTIVE=prod`를 명시해야 한다.**

이 한 줄이 빠지면 배포 서버가 조용히 `local`로 뜬다. 앱은 정상 동작하는 것처럼 보이므로
**로그를 직접 열어보기 전까지 아무도 눈치채지 못한다.** 그 상태에서 실제로 벌어지는 일:

- 모든 SQL과 바인딩 파라미터가 로그로 남는다 → 디스크 사용량 급증
- 로그 문자열 조립 비용이 요청마다 발생 → 응답 지연
- 바인딩 파라미터에 사용자 이메일 등이 그대로 찍힐 수 있다

### 4-1. 프로필 활성화

앱 프로세스에 `SPRING_PROFILES_ACTIVE=prod` 환경변수를 주입하고 재기동한다.

운영·부하테스트 환경 모두 systemd 유닛이 `EnvironmentFile=/opt/app/.env`를 읽는 구조라,
그 파일에 한 줄 추가하고 재기동하면 된다.

```bash
echo 'SPRING_PROFILES_ACTIVE=prod' | sudo tee -a /opt/app/.env
```

```bash
sudo systemctl restart yourtrip-app
```

> **정상 경로에서는 이 절차가 필요 없다.** 운영([app-user-data.sh.tpl](../../terraform/prod/templates/app-user-data.sh.tpl))과
> 부하테스트([app-user-data.sh.tpl](../../terraform/loadtest/templates/app-user-data.sh.tpl)) 모두
> user-data가 `.env`를 만들 때 이 값을 넣으므로, `terraform apply`로 뜬 인스턴스는 별도 조치가
> 필요 없다. 위 절차는 **이미 떠 있는 인스턴스를 손으로 고칠 때만** 쓴다 — 다음 인스턴스 교체
> 때 사라지는 변경이므로, 항구적으로 바꾸려면 템플릿을 고쳐 apply해야 한다.

### 4-2. 적용 확인 (필수)

**재기동 후 이 확인을 반드시 거친다.** 4-1이 실패해도 앱은 정상적으로 뜨기 때문에,
이 단계를 건너뛰면 잘못된 상태를 알아챌 방법이 없다.

기동 로그 앞부분(`Started YourtripApplication`보다 위)에서 아래 문구를 찾는다.

```
The following 1 profile is active: "prod"
```

운영·부하테스트 환경 모두 systemd로 돌므로 `journalctl`로 확인한다. SSH 대신
SSM Session Manager로 붙어도 된다(`aws ssm start-session --target <instance-id>`).

```bash
sudo journalctl -u yourtrip-app -n 200 --no-pager | grep -i profile
```

**두 문구는 의미가 다르다.** Spring은 활성 프로필이 있을 때와 기본값으로 떨어질 때 아예 다른
메시지를 쓴다 — 로컬 실측으로 확인한 결과다.

| 보이는 것 | 의미 |
|---|---|
| `The following 1 profile is active: "prod"` | **정상.** 4-3으로 넘어간다. |
| `No active profile set, falling back to 1 default profile: "local"` | 환경변수가 주입되지 않아 기본값으로 떨어졌다. 4-4-①로. |
| 아무것도 안 나옴 | 로그를 덜 가져왔거나 앱이 아직 안 떴다. 범위를 넓혀 다시 확인한다. |

> `grep -i profile` 대신 `grep "is active"`로 찾으면 **정상일 때만 걸리고 사고일 때는 아무것도
> 안 나온다** — 문제를 놓치기 딱 좋으므로 위 두 문구를 모두 잡는 패턴을 쓴다.

### 4-3. 정상 동작 확인

`prod` 프로필에서 기대하는 로그 상태는 아래와 같다. **"조용해지는 것"과 "아무것도 안 나오는 것"은
다르다** — 기동 로그는 그대로 남아 있어야 정상이다.

| 항목 | 기대 상태 |
|---|---|
| `Tomcat started on port 8080` | **보여야 함** (root를 `INFO`로 유지한 이유) |
| `HikariPool-1 - Start completed` | **보여야 함** |
| SQL 로그 (`select ... from ...`) | 안 보여야 함 |
| `비로그인 사용자의 피드 목록 조회` | 안 보여야 함 (`DEBUG`로 내림) |
| 4xx `BusinessException 발생: ...` | 안 보여야 함 (`DEBUG`로 내림) |

애플리케이션 자체가 살아 있는지도 함께 확인한다.

```bash
curl -sf https://yourtrip.cloud/actuator/health
```

`{"status":"UP", ...}`가 나와야 한다.

#### 참고: 프로필별 기동 로그 실측값

로컬에서 동일한 코드를 두 프로필로 각각 한 번씩 띄워 비교한 결과다(부팅 완료 시점까지).

| | `local` | `prod` |
|---|---|---|
| TRACE | 24 | **0** |
| DEBUG | 70 | **0** |
| INFO | 41 | 36 |
| WARN | 19 | 18 |
| 총 라인 | 2015 | 1478 |

DEBUG/TRACE가 0인 것이 프로필이 제대로 걸렸다는 가장 확실한 신호다. INFO/WARN이 거의 같은 것은
정상이다 — `prod`도 root를 `INFO`로 유지하기 때문이다.

> 위 측정에서 두 프로필 모두 스택트레이스가 1296줄씩 찍혔는데, 이는 측정 환경에 Redis가 떠
> 있지 않아 캐시 관련 `WARN`이 예외 객체를 통째로 실어 남긴 것이다. **프로필과 무관하며**,
> Redis가 정상이면 나오지 않는다.

### 4-4. 트러블슈팅

#### ① 프로필이 `local`로 잡힌다

환경변수가 **앱 프로세스에** 실제로 들어갔는지 확인한다. 파일에 썼다고 프로세스가 읽는 것은 아니다 —
systemd라면 `EnvironmentFile` 경로가 실제로 수정한 파일과 같은지, 재기동을 했는지 확인한다.

**참고 (부하테스트 환경 기준)**: 실행 중인 프로세스의 환경변수를 직접 들여다본다.

```bash
sudo tr '\0' '\n' < /proc/$(pgrep -f 'app.jar')/environ | grep SPRING
```

#### ② SQL 로그가 계속 나온다

`prod`에는 SQL 로그 설정이 아예 없으므로, 이게 보인다면 **거의 확실히 `local`로 뜬 것**이다.
①로 돌아간다.

#### ③ `.env` 파일에 넣었는데 프로필이 안 잡힌다

이 프로젝트는 `spring-dotenv`로 저장소 루트의 `.env`를 읽는데, **이 로딩 시점이 Spring의 프로필
결정보다 늦을 수 있다.** 즉 `.env`에 `SPRING_PROFILES_ACTIVE`를 써도 프로필 활성화에는 반영되지
않을 수 있다.

- **배포 서버**: systemd `EnvironmentFile`은 프로세스를 띄우기 전에 **OS 환경변수로** 주입하므로
  이 문제를 타지 않는다. 4-1 절차가 이 방식이면 그대로 두면 된다.
- **로컬에서 prod를 흉내낼 때**: 셸 환경변수로 직접 준다.

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

### 4-5. JVM 기동 옵션

`-Xmx`·`-Xss`는 **`java` 프로세스의 명령줄 인자**라 `application-prod.yml`에 담을 수 없다. JVM이 이미 그 값으로 뜬 뒤에야 Spring이 yml을 읽기 때문이다. 그래서 별도의 자리를 둔다 — [deploy/prod/](../../deploy/prod/README.md)가 그곳이고, 값과 산정 근거가 함께 버전관리된다.

```
JVM_OPTS=-Xmx768m -Xss512k
```

systemd 유닛이 `EnvironmentFile=/opt/app/jvm-opts.env`로 읽고 `ExecStart`에서 `$JVM_OPTS`로 전개한다. 비밀값이 든 `/opt/app/.env`와 파일을 나눈 이유는 그쪽이 git 밖에 있어야 하고, JVM 옵션은 반대로 근거와 함께 저장소가 들고 있어야 하기 때문이다.

> ⚠️ **`-Xmx768m`은 t3.small(2GB) 전용값이다.** 더 작은 스펙에 그대로 넣으면 OOM이 나고, 메모리가 1,792MB 아래로 내려가면 GC까지 SerialGC로 바뀌어 산정 근거 자체가 무효가 된다. 적용 절차·확인 방법·재산정 기준은 [deploy/prod/README.md](../../deploy/prod/README.md)에 있다.

**적용 확인은 4-2와 같은 원칙으로 반드시 한다.** `JVM_OPTS`가 비면 `$JVM_OPTS`가 빈 문자열로 사라져 JVM이 조용히 기본값(t3.small에서 480MB)으로 뜨는데, 768m과 눈으로 구분되지 않는다.

```bash
tr '\0' ' ' < /proc/$(systemctl show -p MainPID --value yourtrip-app)/cmdline; echo
```

### 4-6. 신규 서버를 프로비저닝할 때

부하테스트 환경은 [terraform/loadtest/templates/app-user-data.sh.tpl](../../terraform/loadtest/templates/app-user-data.sh.tpl)의
`.env` 생성 블록에 `SPRING_PROFILES_ACTIVE=prod`가 포함돼 있다. **`terraform apply`로 새로 만든
인스턴스는 별도 조치가 필요 없다** — 4-2의 적용 확인만 하면 된다.

앞으로 다른 배포 환경을 Terraform이나 스크립트로 추가한다면, 같은 방식으로 프로비저닝 단계에
넣어 수동 절차를 없애는 쪽이 낫다. 사람이 매번 기억해야 하는 한 줄은 언젠가 빠진다.

> **이 원칙이 배포에도 적용됐다**(#120). JAR을 올리고 교체하는 일도 사람 손을 떠나
> [CD 워크플로](cd.md)가 한다. 남은 수동 절차는 서버를 처음 올리는 `terraform apply`와,
> SSM 파라미터 최초 등록뿐이다.
