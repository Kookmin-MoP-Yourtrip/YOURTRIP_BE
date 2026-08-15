# 배포 서버 Spring 프로필 적용 가이드

> ⚠️ **이 문서는 아직 미완성이다.** 운영 서버(`yourtrip.site`)의 실제 구성(환경변수 파일 경로,
> 서비스명, 재기동 방식)이 이 저장소에 기록돼 있지 않아, 해당 부분을 `<TODO: 확인 필요>`로
> 남겨뒀다. 각 단계에는 **실측으로 검증된 부하테스트 환경([EC2-RDS-LOADTEST-GUIDE.md](EC2-RDS-LOADTEST-GUIDE.md))의
> 값을 참고 예시로 함께 적어뒀다** — 운영 서버가 같은 레이아웃이면 그대로 쓰고, 다르면
> `<TODO>` 자리만 실제 값으로 채운 뒤 이 경고 문구를 지운다.

## 배경

`application.yml`이 `local` / `prod` 두 프로필로 분리됐다.

| 파일 | 역할 |
|---|---|
| [application.yml](../../src/main/resources/application.yml) | 환경 공통 설정 (DB, Redis, S3, JWT 등) |
| [application-local.yml](../../src/main/resources/application-local.yml) | 로컬 전용 — 앱 패키지 `DEBUG` + SQL/바인딩 파라미터 로그 |
| [application-prod.yml](../../src/main/resources/application-prod.yml) | 배포 전용 — 앱 패키지 `INFO`, 서드파티 `WARN` |
| [application-test.yml](../../src/test/resources/application-test.yml) | 테스트 전용 — `@ActiveProfiles("test")`를 붙인 `@SpringBootTest`에만 적용 |

새로 `@SpringBootTest`를 추가할 때는 **`@ActiveProfiles("test")`를 함께 붙인다.** 빠뜨리면 그
테스트만 `local`로 떠서 출력이 SQL 로그로 뒤덮인다.

**프로필을 지정하지 않으면 `local`로 뜬다.** `application.yml`의 `spring.profiles.default: local` 때문이다.
로컬 개발자가 아무 설정 없이 `./gradlew bootRun`만 쳐도 되게 하려는 의도적인 선택이다.

### 그래서 배포에서 반드시 해야 하는 일

배포 서버에는 **`SPRING_PROFILES_ACTIVE=prod`를 명시해야 한다.**

이 한 줄이 빠지면 배포 서버가 조용히 `local`로 뜬다. 앱은 정상 동작하는 것처럼 보이므로
**로그를 직접 열어보기 전까지 아무도 눈치채지 못한다.** 그 상태에서 실제로 벌어지는 일:

- 모든 SQL과 바인딩 파라미터가 로그로 남는다 → 디스크 사용량 급증
- 로그 문자열 조립 비용이 요청마다 발생 → 응답 지연
- 바인딩 파라미터에 사용자 이메일 등이 그대로 찍힐 수 있다

---

## 1. 프로필 활성화

앱 프로세스에 `SPRING_PROFILES_ACTIVE=prod` 환경변수를 주입하고 재기동한다.

```
<TODO: 확인 필요> — 운영 서버의 환경변수 파일 경로
```

**참고 (부하테스트 환경 기준)**: systemd 유닛이 `EnvironmentFile=/opt/app/.env`를 읽는 구조라,
그 파일에 한 줄 추가하면 된다.

```bash
echo 'SPRING_PROFILES_ACTIVE=prod' | sudo tee -a /opt/app/.env
```

```bash
sudo systemctl restart yourtrip-app
```

> 부하테스트 환경은 [app-user-data.sh.tpl](../../terraform/loadtest/templates/app-user-data.sh.tpl)에
> 이미 반영돼 있어 **새로 프로비저닝하면 자동으로 들어간다.** 위 수동 절차는 운영 서버처럼
> Terraform 관리 밖에 있는 서버에만 필요하다.

---

## 2. 적용 확인 (필수)

**재기동 후 이 확인을 반드시 거친다.** 1번이 실패해도 앱은 정상적으로 뜨기 때문에,
이 단계를 건너뛰면 잘못된 상태를 알아챌 방법이 없다.

기동 로그 앞부분(`Started YourtripApplication`보다 위)에서 아래 문구를 찾는다.

```
The following 1 profile is active: "prod"
```

```
<TODO: 확인 필요> — 운영 서버의 로그 확인 명령
```

**참고 (부하테스트 환경 기준)**:

```bash
sudo journalctl -u yourtrip-app -n 200 --no-pager | grep -i profile
```

**두 문구는 의미가 다르다.** Spring은 활성 프로필이 있을 때와 기본값으로 떨어질 때 아예 다른
메시지를 쓴다 — 로컬 실측으로 확인한 결과다.

| 보이는 것 | 의미 |
|---|---|
| `The following 1 profile is active: "prod"` | **정상.** 3번으로 넘어간다. |
| `No active profile set, falling back to 1 default profile: "local"` | 환경변수가 주입되지 않아 기본값으로 떨어졌다. 4-①로. |
| 아무것도 안 나옴 | 로그를 덜 가져왔거나 앱이 아직 안 떴다. 범위를 넓혀 다시 확인한다. |

> `grep -i profile` 대신 `grep "is active"`로 찾으면 **정상일 때만 걸리고 사고일 때는 아무것도
> 안 나온다** — 문제를 놓치기 딱 좋으므로 위 두 문구를 모두 잡는 패턴을 쓴다.

---

## 3. 정상 동작 확인

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
curl -sf https://yourtrip.site/actuator/health
```

`{"status":"UP", ...}`가 나와야 한다.

### 참고: 프로필별 기동 로그 실측값

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

---

## 4. 트러블슈팅

### ① 프로필이 `local`로 잡힌다

환경변수가 **앱 프로세스에** 실제로 들어갔는지 확인한다. 파일에 썼다고 프로세스가 읽는 것은 아니다 —
systemd라면 `EnvironmentFile` 경로가 실제로 수정한 파일과 같은지, 재기동을 했는지 확인한다.

**참고 (부하테스트 환경 기준)**: 실행 중인 프로세스의 환경변수를 직접 들여다본다.

```bash
sudo tr '\0' '\n' < /proc/$(pgrep -f 'app.jar')/environ | grep SPRING
```

### ② SQL 로그가 계속 나온다

`prod`에는 SQL 로그 설정이 아예 없으므로, 이게 보인다면 **거의 확실히 `local`로 뜬 것**이다.
①로 돌아간다.

### ③ `.env` 파일에 넣었는데 프로필이 안 잡힌다

이 프로젝트는 `spring-dotenv`로 저장소 루트의 `.env`를 읽는데, **이 로딩 시점이 Spring의 프로필
결정보다 늦을 수 있다.** 즉 `.env`에 `SPRING_PROFILES_ACTIVE`를 써도 프로필 활성화에는 반영되지
않을 수 있다.

- **배포 서버**: systemd `EnvironmentFile`은 프로세스를 띄우기 전에 **OS 환경변수로** 주입하므로
  이 문제를 타지 않는다. 1번 절차가 이 방식이면 그대로 두면 된다.
- **로컬에서 prod를 흉내낼 때**: 셸 환경변수로 직접 준다.

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

---

## 5. 신규 서버를 프로비저닝할 때

부하테스트 환경은 [terraform/loadtest/templates/app-user-data.sh.tpl](../../terraform/loadtest/templates/app-user-data.sh.tpl)의
`.env` 생성 블록에 `SPRING_PROFILES_ACTIVE=prod`가 포함돼 있다. **`terraform apply`로 새로 만든
인스턴스는 별도 조치가 필요 없다** — 2번의 적용 확인만 하면 된다.

앞으로 다른 배포 환경을 Terraform이나 스크립트로 추가한다면, 같은 방식으로 프로비저닝 단계에
넣어 수동 절차를 없애는 쪽이 낫다. 사람이 매번 기억해야 하는 한 줄은 언젠가 빠진다.
