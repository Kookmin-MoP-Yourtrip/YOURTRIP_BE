# 운영 인프라 검증 — 사전 등록한 12개 기준을 전부 통과했다

> [#119](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/119)의 실측·판정 기록이다. 설계와 판정 기준은 [README.md](README.md)에 있다.
>
> **결론만 먼저**: [README.md](README.md)에 구축 **전에** 못 박은 판정 기준 12개를 모두 통과했다. 스케일아웃(P11)은 초당 2,972 요청을 7분간 걸어 실제로 발동시켰고, 그 원인이 우리가 건 정책임을 AWS 활동 기록으로 확인했다.
>
> **확정하지 못한 것**: 임계값의 안전계수 두 개는 여전히 추정이다. 이번 측정은 "임계값을 넘기면 스케일아웃이 발동하는가"를 확인한 것이지, **임계값 자체가 적정한가를 검증한 것이 아니다.** 측정되지 않은 경로(AI 코스 생성·미디어 업로드)는 이번에도 재지 않았다.

## 측정 환경

| 항목 | 값 |
|---|---|
| 앱 | ASG t3.small (vCPU 2, 2GB), AL2023 `ami-0729121845edb4108`, Corretto 21 |
| 프로필 | `prod` (threads.max 32, `-Xmx768m -Xss512k`) |
| RDS | db.t3.micro, PostgreSQL 16, `ap-northeast-2a`, 저장 암호화 |
| ElastiCache | cache.t3.micro, Redis 7.1, **`ap-northeast-2a`** (App과 동일) |
| ALB | internet-facing, 2 AZ, ACM `CN=yourtrip.cloud` |
| DB 상태 | **빈 DB** — `DB_DDL_AUTO=update`가 스키마를 생성 |
| 부하 생성기 | 로컬 Windows에서 k6 v2.1.0, 인터넷 경유 HTTPS |
| 시나리오 | `scripts/k6/popular-cold.js`, VUS 100 |
| 측정 시각 | 2026-08-26 00:29~00:46 UTC |

> **한계**: 부하 생성기가 EC2가 아니라 로컬이고 인터넷을 경유했다. 부하테스트 문서들([tomcat-thread-sizing](../tomcat-thread-sizing/ec2-measurement.md) 등)이 같은 VPC 안에서 사설 IP로 측정한 것과 조건이 다르므로, **여기서 나온 처리량 수치를 그 문서들의 값과 직접 비교하면 안 된다.** 이번 목적은 처리량 측정이 아니라 인프라 동작 검증이다.

## 판정 결과

| # | 기준 | 결과 | 실측값 |
|---|---|---|---|
| P1 | TLS 종단 | ✅ | `subject=CN=yourtrip.cloud`, 2026-08-25 ~ 2027-03-10 |
| P2 | `/actuator/*` 차단 | ✅ | `/actuator/health` **403**, `/actuator` **403**, `/actuator/prometheus` **403** |
| P3 | Swagger 생존 | ✅ | `/swagger-ui/index.html` **200**, `/v3/api-docs` **200** |
| P4 | 헬스체크 공존 | ✅ | P2가 403인 상태에서 타깃 `healthy` |
| P5 | 프로필 주입 | ✅ | `The following 1 profile is active: "prod"` |
| P6 | JVM 옵션 전달 | ✅ | `jvm_memory_max_bytes{area="heap"}` 합 **805306368**, cmdline `-Xmx768m -Xss512k` |
| P7 | **user-data에 시크릿 없음** | ✅ | `SECRET=`/`PASSWORD=`/`API_KEY=` grep **0건** |
| P8 | IMDSv2 강제 | ✅ | 토큰 없이 메타데이터 조회 **401** |
| P9 | Redis AZ 정렬 | ✅ | 인스턴스 `ap-northeast-2a` == Redis `ap-northeast-2a` == RDS `ap-northeast-2a` |
| P10 | ALB 502 없음 | ✅ | 초당 2,972 부하에서 `HTTPCode_ELB_502_Count` **데이터포인트 없음(=0)**, `HTTPCode_ELB_5XX_Count`도 0 |
| P11 | 스케일아웃 발동 | ✅ | 아래 상세 |
| P12 | 철거 완결성 | ✅ | destroy 후 ALB·RDS·ElastiCache·EC2·ASG·VPC 조회 결과 전부 빈 배열 |

부수 확인:

- `/api/upload-courses/popular`이 **200**을 반환했다 — DB 연결·스키마 생성·Redis 캐시 경로가 모두 살아 있다는 뜻이다. `DB_DDL_AUTO=update`가 빈 DB에 테이블을 만들었다.
- `.env`의 시크릿 8개가 전부 주입됐다(SSM에서 부팅 시 조회).
- HTTP → HTTPS **301** 리다이렉트 동작.

### P11 상세 — 스케일아웃 실증

k6 VUS 100, `popular-cold.js`, HTTPS 경유.

| 지표 | 값 |
|---|---|
| 처리량 | **2,972 req/s** (3분 측정 시 535,048 요청) |
| 실패율 | **0.00%** (0 / 535,048) |
| p95 | 55.25ms (avg 33.51ms, max 552.53ms) |
| `RequestCountPerTarget` 최대 | **분당 207,155** (임계값 30,000의 6.9배) |

발동 기록:

```
At 2026-08-26T00:40:52Z a monitor alarm TargetTracking-yourtrip-prod-asg-AlarmHigh-...
in state ALARM triggered policy yourtrip-prod-scaleout-request-count
changing the desired capacity from 1 to 2.
```

**임계값은 사전 등록한 30000(분당) 그대로 두고 측정했다.** 데모를 위해 낮추지 않았다.

#### 3분 부하로는 발동하지 않았다 — 원인과 교훈

첫 시도(3분 부하, 초당 2,972)에서는 **임계값을 6.9배 초과했는데도 스케일아웃이 일어나지 않았다.** 알람 상태를 보고 원인을 알았다.

```
State: OK
EvaluationPeriods: 3, Period: 60
StateReason: Threshold Crossed: 1 datapoint [0.0 (00:30:00)] was not greater than the threshold (30000.0)
```

두 가지가 겹쳤다.

1. **Target Tracking 알람은 3분 연속 초과를 요구한다**(`EvaluationPeriods=3` × `Period=60`). 부하도 정확히 3분이라 경계선이었다.
2. **ALB 지표는 CloudWatch에 1~3분 지연되어 도착한다.** 부하가 끝난 시점에도 알람의 판단 근거는 부하 시작 전인 `00:30:00`의 `0.0`이었다.

7분으로 늘려 재측정하니 **90초 안에** 알람이 `ALARM`으로 전환되고 desired capacity가 1 → 2로 올라갔다.

**교훈**: 스케일 정책을 검증할 때 "임계값을 넘겼는가"만 보면 안 된다. `EvaluationPeriods × Period + 지표 지연`보다 부하가 길어야 한다. 이 구성에서는 최소 5~6분이다.

## 마주친 문제

구축 과정에서 네 건이 있었고, 그중 둘은 코드로 고쳤고 둘은 재발 방지를 코드·문서에 넣었다.

### 1. Terraform `$$` 이스케이프 — 시크릿이 한 줄도 주입되지 않았다

가장 컸다. user-data 템플릿에 이렇게 썼다.

```bash
| while IFS=$$'\t' read -r name value; do
    printf '%s=%s\n' "$${name##*/}" "$$value"
```

**Terraform의 `$$` 이스케이프는 `$${` 형태에만 적용된다.** 단독 `$$`는 변환되지 않고 그대로 남는데, bash에서 `$$`는 **현재 셸의 PID**다. 그래서 `IFS`가 깨지고 값이 `<PID>value`가 되어, SSM에서 받은 시크릿이 `.env`에 한 줄도 들어가지 않았다.

증상은 "타깃이 계속 unhealthy → 인스턴스 교체 반복"이었다. `${name##*/}`는 `${`를 포함해 정상 렌더링됐고 CloudFront 개인키도 제대로 떨어졌기 때문에, 겉으로는 SSM 접근이 되는 것처럼 보여 진단이 한 단계 늦어졌다.

**고친 뒤**: `IFS=$'\t'`, `"$value"`. 중괄호 없는 bash 변수는 이스케이프가 필요 없다.

부수적으로, 이 함정을 주석에 적으면서 `${...}` 표기를 그대로 썼다가 **templatefile이 그것을 보간으로 파싱해 apply가 깨졌다.** 주석이라도 파서는 읽는다. 지금은 말로 풀어 적어 뒀다.

### 2. 네트워크 순단 → 정상 리소스가 tainted

apply 중 로컬 네트워크가 끊겨 RDS·ElastiCache의 생성 완료를 확인하지 못했다.

```
Error: waiting for RDS DB Instance (...) create: ... dial tcp: lookup rds.ap-northeast-2.amazonaws.com: no such host
```

**리소스는 정상 생성돼 `available` 상태였다.** terraform이 확인에 실패했을 뿐인데 둘을 `tainted`로 표시했고, 그대로 apply했다면 `2 to destroy`로 **멀쩡한 DB를 지우고 재생성**했을 것이다. 실제 상태를 확인하고 `terraform untaint`로 해결했다.

코드로 막을 수 있는 문제가 아니라 [terraform/prod/README.md](../../../terraform/prod/README.md)에 트러블슈팅으로 남겼다.

### 3. ASG 서비스 연결 역할 부재

계정에서 ASG를 처음 만들 때 발생했다.

```
Access denied when attempting to assume role .../AWSServiceRoleForAutoScaling.
Validating load balancer configuration failed.
```

AWS가 자동 생성하지만 **비동기라 첫 apply가 먼저 실패한다.** 재시도하면 통과해서 "한 번 실패하는 문제"로 넘길 수 있지만, 이 저장소를 clone해 자기 계정에 apply하는 사람은 그대로 같은 실패를 겪는다. IaC가 "받아서 그대로 돌리면 뜬다"를 만족해야 하므로 코드로 보장했다 — 역할 존재 여부를 조회해 없을 때만 생성하고, ASG가 그것에 `depends_on`한다.

### 4. SSM 경로 불일치

PEM을 파일로 분리하면서 시크릿 경로를 `/yourtrip/prod/env/` 하위로 옮겼는데, `rds.tf`의 `data "aws_ssm_parameter"`만 따라가지 않아 파라미터를 찾지 못했다. 경로를 맞췄다.

### 재구축으로 검증

네 건을 고친 뒤 **destroy → apply를 다시 돌렸다.** taint도, ASG 실패도, 재시도도 없이 한 번에 통과했고 타깃이 2분 만에 healthy가 됐다. 수정이 실제로 동작한다는 확인이다.

## 한계

- **임계값의 적정성은 검증하지 않았다.** 이번 측정은 "넘기면 발동한다"를 확인한 것이다. 안전계수 ×0.5 두 개는 여전히 추정이며, 근거가 되는 실측이 없다.
- **측정되지 않은 경로가 그대로 남아 있다.** AI 코스 생성(Gemini 동기 호출)과 미디어 업로드는 이번에도 재지 않았다. 임계값 유도에서 이 경로들을 보정한 계수가 맞는지 확인하려면 그것들을 재야 한다.
- **부하 생성기가 로컬이고 인터넷을 경유했다.** 처리량 2,972 req/s는 네트워크 왕복이 포함된 값이라 기존 실측 문서(같은 VPC, 사설 IP)의 수치와 비교할 수 없다.
- **scale-in은 검증하지 않았다.** 부하가 끝난 뒤 인스턴스가 1대로 돌아오는지 확인하지 않았다(AlarmLow는 15분 연속 미달을 요구한다).
- **DB는 빈 상태였다.** 데이터가 있을 때의 쿼리 부하는 재지 않았다.
- **단일 AZ 구성이다.** 앱·RDS·Redis가 모두 `ap-northeast-2a`에 있어 AZ 장애에 그대로 노출된다. 지연과 실측 재현성을 택한 결과다.

## 참고 문서

| 문서 | 이 검증에서 쓴 내용 |
|---|---|
| [README.md](README.md) | 사전 등록한 판정 기준 12개, 임계값 유도 산술 |
| [terraform/prod/README.md](../../../terraform/prod/README.md) | 실행·철거 절차, 이번에 추가한 트러블슈팅 |
| [tomcat-thread-sizing/ec2-measurement.md](../tomcat-thread-sizing/ec2-measurement.md) | `-Xmx768m`·threads.max 32의 근거 (P6가 확인한 값) |
| [cache-effect-measurement/redis-io-bottleneck.md](../cache-effect-measurement/redis-io-bottleneck.md) | AZ 정렬이 필요한 이유 (P9) |
