# 운영 인프라 IaC화 — 수명이 다른 리소스는 state를 나눈다

> [#119](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/119)의 설계·판정 기준 기록이다. 아래 판정 기준은 **구축 전에 못 박은 것**이고, 실제 검증 결과는 [verification.md](verification.md)에 있다(12개 전부 통과).
>
> **왜 하는가**: 운영 서버가 Terraform 관리 밖에 있어 **저장소가 운영 형상을 알지 못한다.** [profile.md](../../guide/profile.md) §4의 "이 절은 아직 미완성이다" 경고와 `<TODO: 확인 필요>` 2곳, [deploy/prod/README.md](../../../deploy/prod/README.md)의 한계 3개 항목이 전부 같은 원인에서 나왔다. 특히 `deploy/prod/yourtrip-app.service`는 **운영 서버의 실제 구성을 확인하고 쓴 것이 아니라 부하테스트 환경 구조를 옮긴 추정본**이다. [tomcat-thread-sizing](../tomcat-thread-sizing/README.md)·[jvm-heap-sizing](../jvm-heap-sizing/README.md)이 실측으로 정한 값(`threads.max=32`, `-Xmx768m`)이 있는데 그 값이 적용될 환경은 기록돼 있지 않다 — 근거와 적용 대상이 끊겨 있다.
>
> **결론만 먼저**: 리소스를 **수명 기준으로 state 2개**(영구/일회성)에 나눠 담는다. 온디맨드 가동 모델에서 도메인·인증서를 매번 재생성하지 않기 위한 분리이며, 이 결정이 나머지 대부분을 파생시킨다. ASG 스케일 임계값은 열린 루프 실측 2,033 req/s에서 출발해 **분당 30,000**(= 초당 500)으로 잡았다.
>
> **확정하지 못한 것**: 임계값의 안전계수 두 개(×0.5, ×0.5)는 **실측이 아니라 추정**이다. 측정되지 않은 경로(AI 코스 생성·미디어 업로드)의 비용을 모르기 때문이다. 또한 이 임계값은 min 1 / max 2 구성에서 **유기적으로 발동하지 않는다** — 실트래픽이 사실상 0이라 k6를 의도적으로 돌려야 도달한다.

## 이 작업이 만드는 것

| 계층 | 구성 |
|---|---|
| 진입 | ALB (internet-facing, 2 AZ) — 443 TLS 종단(ACM), 80은 301 redirect, `/actuator/*`는 리스너 규칙으로 403 |
| 앱 | Launch Template + ASG (min 1 / max 2, 요청 수 기반 Target Tracking) — AL2023 + Corretto 21, systemd |
| 데이터 | RDS PostgreSQL 16 (db.t3.micro), ElastiCache Redis 7.1 (cache.t3.micro, **App과 같은 AZ**) |
| 네트워크 | 전용 VPC `10.43.0.0/16`, 퍼블릭 서브넷 2개, SG 4종(alb/app/rds/elasticache) |
| 도메인 | 가비아 구매 `.com` → Route53 호스티드존 + ACM(ap-northeast-2) + ALB alias 레코드 |

부하테스트 환경([terraform/loadtest/](../../../terraform/loadtest/README.md))과 **동형**을 목표로 한다. 실측값이 도출된 환경과 운영이 어긋나면 그 값의 근거가 성립하지 않기 때문이다.

## 설계 원칙 1 — 수명으로 state를 가른다

이 저장소의 운영 서버는 **온디맨드 모델**이다(데모·측정 시에만 올린다, [ci.md](../../guide/ci.md) 참고). 그런데 ALB + ASG + RDS + ElastiCache를 매번 destroy/apply하면 도메인과 인증서까지 함께 사라진다. 리소스마다 수명이 다르다는 것이 이 설계의 축이다.

| state | 디렉터리 | 담는 것 | 수명 |
|---|---|---|---|
| 기존 영구 | `terraform/` | S3 미디어 버킷, CloudFront, 앱용 IAM 유저 | 손대지 않는다 |
| **신설 영구** | `terraform/prod-permanent/` | Route53 호스티드존, ACM 인증서 + 검증 레코드, 배포 아티팩트 S3 버킷 | 1회 apply 후 유지 |
| **신설 일회성** | `terraform/prod/` | VPC/SG, ALB/TG/리스너, LT/ASG/스케일링, RDS, ElastiCache, **Route53 alias 레코드** | 데모마다 apply/destroy |

**왜 이 경계인가**

- **ACM 인증서**를 일회성 state에 두면 destroy마다 삭제되고 apply마다 재발급 + DNS 검증 대기가 붙는다. 인증서는 미사용 상태에서도 검증 CNAME이 존에 남아 있으면 자동 갱신되므로 영구 state가 정확하다.
- **호스티드존**은 destroy하면 NS 세트가 바뀐다. 도메인을 가비아에서 샀기 때문에 **네임서버를 가비아 콘솔에 다시 입력하고 전파를 다시 기다려야 한다.** 이 수동 절차를 반복하지 않으려면 존은 영구여야 한다.
- **alias 레코드는 일부러 일회성 쪽에 둔다.** ALB DNS명이 apply마다 바뀌는 문제가 이걸로 자동 해결된다 — 레코드가 `aws_lb.this.dns_name`을 참조하므로 항상 새 ALB를 가리킨다. ALB alias는 AWS가 TTL 60초를 쓰므로 전환 지연도 1분 이내다. destroy 후 도메인이 NXDOMAIN이 되는 것은 "서버가 내려가 있다"는 사실의 정직한 반영이라 허용한다.
- 기존 `terraform/`에 합치지 않는 이유: 그 state에는 앱용 IAM access key가 평문으로 들어 있어 건드리는 횟수를 늘리고 싶지 않고, `.gitignore`·`.worktreeinclude` 블록이 **경로 프리픽스 방식**이라 디렉터리를 나눠야 대칭이 유지된다.

모듈 간 값 전달은 저장소 관례대로 `terraform -chdir=../prod-permanent output -raw ...`를 손으로 tfvars에 옮긴다. `terraform_remote_state`를 쓰지 않는 것은 [terraform/loadtest](../../../terraform/loadtest/README.md)가 이미 그 방식을 택했고, 여기서만 바꾸면 두 관례가 공존하기 때문이다.

## 설계 원칙 2 — 시크릿은 user_data에 넣지 않는다

`terraform/loadtest/templates/app-user-data.sh.tpl`은 DB 비밀번호·JWT 시크릿·API 키를 **user_data에 평문으로 렌더링**한다. 운영에서 그대로 두면 안 되는 이유가 구체적이다.

user_data는 인스턴스 안에서 `http://169.254.169.254/latest/user-data`로 읽힌다. 이 앱은 Kakao·Gemini로 아웃바운드 HTTP를 하므로 **SSRF 표면이 실재**하고, 부하테스트 환경은 개발자 IP만 열린 일회성 환경이었지만 운영은 `0.0.0.0/0`에 노출된다. tfstate에도 평문으로 남는다.

| 부류 | 값 | 전달 경로 |
|---|---|---|
| 비밀 아님 | `SPRING_PROFILES_ACTIVE`, `DB_URL`, `DB_DDL_AUTO`, `REDIS_HOST/PORT`, `S3_BUCKET`, `CLOUDFRONT_*` | user_data에 직접 렌더링 |
| 비밀 8개 | `DB_PASSWORD`, `JWT_SECRET`, `MAIL_EMAIL`, `MAIL_PASSWORD`, `KAKAO_API_KEY`, `AWS_ACCESS_KEY`, `AWS_SECRET_KEY`, `GEMINI_API_KEY` | SSM `/yourtrip/prod/<KEY>` SecureString |
| CloudFront 개인키 | PEM 파일 | SSM SecureString → `/opt/app/cloudfront_private_key.pem` (chmod 600) |

SSM 파라미터는 **terraform 밖에서 `aws ssm put-parameter`로 1회 등록**한다. `aws_ssm_parameter` 리소스로 만들면 값이 tfstate에 평문으로 돌아와 지금 없애려는 문제 그 자체가 된다. terraform 밖이라 `destroy`가 지우지 않으므로, **재apply 때 시크릿을 다시 입력할 필요가 없다** — 온디맨드 모델과 정확히 맞는다.

짝을 이루는 조치로 Launch Template에 **IMDSv2를 강제**한다(`http_tokens = "required"`, `hop_limit = 1`).

`manage_master_user_password`(RDS 마스터 비밀번호를 Secrets Manager에 위임)는 **기각한다.** destroy 시 시크릿이 7일 복구 대기로 들어가 같은 이름 재생성이 7일간 실패하는데, 이는 매일 destroy/apply하는 모델을 정면으로 깬다.

## 설계 원칙 3 — 손으로 복제한 파일을 `file()`로 잇는다

현재 `terraform/loadtest/templates/app-user-data.sh.tpl`과 `deploy/prod/`가 **systemd 유닛과 JVM 옵션을 각자 들고 있다.** 같은 내용을 두 곳에 타이핑한 상태라 한쪽만 고치면 조용히 어긋난다. prod 모듈은 다시 쓰지 않고 읽는다.

```hcl
service_unit = file("${path.module}/../../deploy/prod/yourtrip-app.service")
jvm_opts_env = file("${path.module}/../../deploy/prod/jvm-opts.env")
```

`templatefile()`은 **주입된 값을 재스캔하지 않으므로** 유닛 파일 안의 `$JVM_OPTS`가 terraform 치환 대상이 되지 않는다. loadtest 템플릿 상단이 경고하는 함정(중괄호 유무에 따른 systemd 단어 분리)을 구조적으로 피한다. `.gitattributes`의 `deploy/** text eol=lf`가 CRLF 유입을 막아주는데, 이 규칙이 여기서 실제로 값을 한다 — 없으면 systemd 유닛이 조용히 깨진다.

부수 효과로 `deploy/prod/README.md`의 한계 "이 유닛 파일은 운영 서버의 실제 구성을 확인하고 쓴 것이 아니다"가 **해소된다.** 이제 그 파일이 곧 운영 구성이다.

## ASG 스케일 임계값 — 산술 전체

### 지표를 `ALBRequestCountPerTarget`으로 고른 이유

[tomcat-thread-sizing/cpu-cost-decomposition.md](../tomcat-thread-sizing/cpu-cost-decomposition.md)가 CPU 기반 스케일링의 함정을 실측으로 보여준다. T200의 `process_cpu_usage`는 0.940, T32는 0.810인데 **처리량은 T32가 더 높다**(2,921 vs 2,517 TPS). 사용률 하락의 절반은 "일을 덜 해서"가 아니라 **"워커가 32개뿐이라 I/O 대기 중 CPU를 다 못 채워서"** 다. 즉 `maxThreads=32` 구성에서 CPU 사용률은 실제 여유를 **과대평가**한다. 요청 수는 이 왜곡이 없다.

### 단위 함정

`ALBRequestCountPerTarget`은 ALB가 **60초 period로 내보내는 SUM**이다. `target_value`는 초당이 아니라 **타깃당 분당** 요청 수다. 초당 값을 그대로 넣으면 임계값이 60배 낮아져 상시 스케일아웃이 된다.

### 산술

| 단계 | 값 | 근거 |
|---|---|---|
| 출발점 | 2,000 req/s | [cache-effect-measurement/scenarios.md](../cache-effect-measurement/scenarios.md)의 D3 열린 루프 실측 — 현재 운영 구성(A2)이 **10초 버킷에서 도착률의 90%를 충족하는 상한 2,033 req/s**. 닫힌 루프 2,921 TPS보다 낮고, **도착률 고정 측정**이라 ASG가 사는 세계와 종류가 같다 |
| ×0.5 | 1,000 | **측정되지 않은 경로 보정.** 워밍 캐시 히트 `/popular`만 쟀다. AI 코스 생성(Gemini 동기 호출, 수 초)과 10MB 미디어 업로드는 **한 번도 측정되지 않았고**, `maxThreads=32`에서 이 경로 32건이 워커를 붙들면 2ms짜리 요청도 뒤에서 대기한다 |
| ×0.5 | 500 req/s | **스케일아웃 리드타임 보정.** 새 인스턴스가 트래픽을 받기까지 부팅 + `dnf install` + JAR 다운로드 + Spring 기동 + JIT 예열로 3~5분이 걸린다. 그 사이 기존 인스턴스가 버텨야 하므로 사용 가능 상한의 절반에서 발동시킨다 |
| ×60 | **`target_value = 30000`** | 분당 환산 |

**두 계수는 추정이다.** 측정되지 않은 경로의 비용을 모르는 상태에서 고른 값이며, 실측으로 뒷받침되지 않는다는 사실을 여기 남긴다.

**플래핑 검산**: run 간 변동은 ±5%(#88) ~ ±16.3%(#101)다. 0.5 × 0.5 = 4배 여유이므로 변동폭보다 한참 넉넉하다.

### 이 정책은 유기적으로 발동하지 않는다

min 1 / max 2에 실트래픽이 사실상 0이므로 500 req/s는 **k6를 의도적으로 돌려야 도달한다.** 데모를 위해 임계값을 낮추는 것은 이 저장소의 사전 등록 원칙(판정 기준을 측정 전에 못 박고 사후에 완화하지 않는다) 위반이므로, 대신 `var.scaleout_request_count_per_target_per_minute`(기본 30000)로 노출한다. **데모 시 임시로 낮췄다면 그 값과 이유를 [verification.md](verification.md)에 함께 기록한다.**

> 실제 검증에서는 임계값을 그대로 두고 k6로 초당 2,972를 걸어 발동시켰다. 다만 **부하가 3분이면 발동하지 않는다** — 알람이 3분 연속 초과를 요구하는 데다 ALB 지표가 CloudWatch에 1~3분 지연되어 도착하기 때문이다. 최소 5~6분은 걸어야 한다([verification.md](verification.md)의 P11 상세).

### 부수 설정과 근거

| 설정 | 값 | 근거 |
|---|---|---|
| `default_instance_warmup` | 300s | [jvm-heap-sizing/ab-measurement.md](../jvm-heap-sizing/ab-measurement.md)와 [tomcat-thread-sizing/ec2-measurement.md](../tomcat-thread-sizing/ec2-measurement.md)가 기록한 JIT 예열 — 재기동 직후 첫 고부하는 2,253 TPS(정상 2,921 대비 **−23%**). 예열 중 인스턴스의 낮은 처리량이 지표를 오염시켜 추가 스케일아웃을 부르는 것을 막는다 |
| `health_check_grace_period` | 300s | 부팅 + 패키지 설치 + JAR 다운로드 + Spring 기동까지의 유예 |
| `health_check_type` | `ELB` | 프로세스 생존이 아니라 요청 처리 가능 여부로 판정해야 한다 |
| `credit_specification` | `unlimited` **명시** | t3 기본값이지만 계정 기본값이 뒤집힐 수 있다. [ab-measurement.md](../jvm-heap-sizing/ab-measurement.md)에 "정지 후 재기동 시 `CPUCreditBalance`가 2분 만에 0"이 기록돼 있고, 스케일아웃 인스턴스는 launch credit만 갖고 뜬다. 잉여 크레딧 과금이 발생한다 |
| TG `deregistration_delay` | 30s | 기본 300s면 scale-in·destroy가 매번 5분씩 걸린다. 진행 중 장기 요청이 잘릴 수 있다 |
| instance refresh | `min_healthy 100 / max_healthy 200` | **먼저 띄우고 나중에 죽이는** 순서라 desired=1에서도 무중단 교체가 된다(max 2가 이걸 가능하게 한다) |

## RDS — loadtest가 "운영이면 이러면 안 된다"고 적어둔 값들

`terraform/loadtest/rds.tf`의 주석이 이미 표시해 둔 항목들이다. 온디맨드 모델과 충돌하는 것이 있어 그대로 뒤집을 수는 없다.

| 항목 | loadtest | prod | 판단 |
|---|---|---|---|
| `storage_encrypted` | false | **true** | 무료, 단점 없음. **사후 변경 불가**라 최초 apply 전에 켠다 |
| `backup_retention_period` | 0 | **1** | 0이면 PITR 자체가 불가능하다. 매일 destroy되는 DB에 7일 보존은 연극이므로 최솟값. **진짜 DR 태세가 아니다** |
| `deletion_protection` | false | **false** (변수 노출) | true면 destroy가 막혀 apply 두 번이 필요하다 — 온디맨드와 정면 충돌. 변수로 노출해 "검토 후 끈 값"임을 코드로 드러낸다 |
| `skip_final_snapshot` | true | **조건부** (아래) | |
| `apply_immediately` | true | **true** | 인스턴스가 유지보수 창까지 살아 있지 않다. false면 변경이 조용히 영원히 반영되지 않는다 |
| `multi_az` | false | **false** | 비용. **앱 계층만 이중화되고 DB는 페일오버가 없다** |
| `performance_insights_enabled` | — | **true**, 7일 | 무료 구간이고 이 저장소의 측정 문화와 맞는다 |

### `skip_final_snapshot`의 충돌 해소

`true`면 destroy마다 데이터가 사라지고, `false`면 매일 스냅샷이 쌓인다. destroy 시점에 config가 읽히는 성질을 이용한다.

```hcl
skip_final_snapshot       = var.rds_final_snapshot_suffix == ""
final_snapshot_identifier = var.rds_final_snapshot_suffix == "" ? null : "${var.name_prefix}-final-${var.rds_final_snapshot_suffix}"
```

기본은 스냅샷 없이 빠른 destroy. 보존이 필요한 날만 `terraform destroy -var 'rds_final_snapshot_suffix=2026-08-25-demo'`.

`timestamp()`를 쓰지 않는 이유: 리소스 인자에 넣으면 **매 plan마다 diff가 뜨고**, `ignore_changes`로 막으면 값이 최초 apply 시점에 고정돼 두 번째 destroy에서 이름이 충돌한다.

### ElastiCache — AZ 고정이 가장 중요하다

`availability_zone`을 App EC2와 같은 AZ로 **반드시 고정한다.** 이걸 빠뜨려 Redis가 다른 AZ에 떨어지면서 명령 지연 바닥값이 0.2~0.4ms → 1.2ms로 굳은 사고가 있었다(커밋 `7cbef86`, `terraform/loadtest/elasticache.tf`에 45줄 주석으로 남아 있다). 인자명도 주의해야 한다 — `preferred_availability_zones`/`az_mode`는 Memcached 전용이고 Redis는 `availability_zone`이다.

`transit_encryption_enabled`는 **기각한다.** 앱이 `spring.data.redis.ssl`을 설정하지 않아 앱 변경이 선행돼야 한다. 다만 **퍼블릭 서브넷을 쓰므로 실질 경계가 SG 하나뿐**이라는 사실은 남긴다.

## 스키마 관리 — `DB_DDL_AUTO=update`

이 저장소는 포트폴리오 목적이고 **실서비스를 운영하지 않는다.** 그래서 Flyway 같은 정식 마이그레이션 도구를 이번 범위에 넣지 않고 Hibernate가 스키마를 만들게 한다. 빈 DB에서 별도 부트스트랩 없이 바로 기동한다.

**`create`가 아니라 `update`인 이유**: `create`는 SessionFactory가 뜰 때마다 스키마를 DROP + CREATE한다. ASG는 **사람 개입 없이 인스턴스를 띄우는 장치**이므로, 스케일아웃으로 두 번째 인스턴스가 뜨는 순간 첫 번째가 쓰던 테이블이 통째로 재생성된다 — 진행 중이던 요청이 깨지고, 이 이슈의 핵심 데모인 **스케일아웃 실증 도중에 500이 난다.** `update`는 "빈 DB에서 바로 뜬다"는 편의를 그대로 주면서 이 사고만 없앤다.

**기각한 대안**: `validate` + `pg_dump`로 뽑은 스키마 SQL을 SSM 터널로 1회 적용. 운영 정석에 가깝지만, 실서비스를 하지 않는 환경에서 매 apply마다 터널을 열고 SQL을 적용하는 절차 비용이 얻는 것보다 크다.

## 사전 등록한 판정 기준

측정·검증 **전에** 못 박는다. 사후에 완화하지 않는다.

| # | 항목 | 통과 기준 |
|---|---|---|
| P1 | TLS 종단 | `openssl s_client`의 인증서 주체가 `CN=<domain>`, 체인 검증 통과 |
| P2 | `/actuator/*` 차단 | `/actuator/health`와 `/actuator`(슬래시 없음) 둘 다 **403** |
| P3 | Swagger 생존 | `/swagger-ui/index.html` **200** — Android FE 팀의 API 문서이므로 차단 대상이 아니다 |
| P4 | 헬스체크 공존 | P2가 403인 상태에서 TG 타깃이 `healthy` — 리스너 규칙이 ALB 내부 헬스체크를 막지 않음을 증명한다 |
| P5 | 프로필 주입 | 기동 로그에 `The following 1 profile is active: "prod"` |
| P6 | JVM 옵션 전달 | `jvm_memory_max_bytes{area="heap"}` 합이 **805306368**(= 768MiB) |
| P7 | **user_data에 시크릿 없음** | IMDSv2로 읽은 user-data에서 `SECRET`/`PASSWORD`/`API_KEY` grep 결과 **0건** |
| P8 | IMDSv2 강제 | 토큰 없이 메타데이터 조회 시 **401** |
| P9 | Redis AZ 정렬 | ElastiCache의 `CustomerAvailabilityZone` == App 인스턴스 AZ |
| P10 | **ALB 502 없음** | 3분 지속 부하 중 `HTTPCode_ELB_502_Count` **Sum = 0** |
| P11 | 스케일아웃 발동 | 임계값 초과 부하에서 `Launching a new EC2 instance` 활동 1건 + 원인 알람 명시 |
| P12 | 철거 완결성 | destroy 후 ALB·RDS·ElastiCache·EC2·ASG 조회 결과가 전부 비어 있음 |

### P10이 필요한 이유

ALB `idle_timeout`(기본 60초)이 백엔드 keep-alive보다 길면, ALB가 Tomcat이 방금 닫은 연결을 재사용하려다 **간헐 502**를 만든다. `application-prod.yml`에 `keep-alive-timeout: 65s`와 `max-keep-alive-requests: -1`을 넣어 앞서 막는다.

### TG 헬스체크를 `/actuator/health/liveness`로 두는 이유

`/actuator/health`는 DB·Redis가 내려가면 503을 반환한다. `health_check_type = "ELB"`인 ASG는 그 인스턴스를 죽이고 다시 띄우기를 반복하므로, **RDS 순간 장애 하나가 인스턴스 교체 폭풍이 된다.** liveness는 프로세스 생존만 본다.

**대가**: DB가 죽어도 ALB가 트래픽을 계속 보내 500이 나간다. fleet이 1대일 때는 어차피 뺄 곳이 없으므로 수용하고, 다중 인스턴스가 상시화되면 readiness로 전환할 여지를 남긴다.

## 한계

- **임계값의 안전계수 두 개가 실측이 아니다.** 측정되지 않은 경로(AI 코스 생성·미디어 업로드)의 비용을 모르는 채로 4배 여유를 잡았다. 그 경로를 재면 계수가 바뀔 수 있다.
- **출발점으로 쓴 열린 루프 실측 2,033 req/s는 `maxThreads=200` 시절 값이다.** 현재 구성(32)과 정확히 대응하지 않아 보수적인 쪽으로 택했지만, 같은 조건에서 다시 잰 값은 아니다.
- **DB는 단일 AZ이고 페일오버가 없다.** 앱 계층만 이중화된다.
- **퍼블릭 서브넷을 쓰므로 데이터 계층의 실질 경계는 SG 하나다.** NAT Gateway 비용을 피한 대가다.
- **`DB_DDL_AUTO=update`는 컬럼 삭제·타입 변경을 반영하지 않고 조용히 무시한다.** 실사용으로 전환한다면 Flyway 도입이 선행돼야 한다.
- **배포는 여전히 수동이다.** JAR 빌드와 S3 업로드는 사람이 한다. instance refresh로 교체는 자동화되지만 CD는 없다.

## 참고 문서

| 문서 | 이 작업에서 쓴 내용 |
|---|---|
| [tomcat-thread-sizing/ec2-measurement.md](../tomcat-thread-sizing/ec2-measurement.md) | 닫힌 루프 처리량, JIT 예열 −23%, 측정되지 않은 경로 경고 |
| [tomcat-thread-sizing/cpu-cost-decomposition.md](../tomcat-thread-sizing/cpu-cost-decomposition.md) | CPU 기반 스케일링이 여유를 과대평가한다는 근거 |
| [cache-effect-measurement/scenarios.md](../cache-effect-measurement/scenarios.md) | 열린 루프 90% 충족 상한 2,033 req/s (임계값 출발점) |
| [jvm-heap-sizing/ab-measurement.md](../jvm-heap-sizing/ab-measurement.md) | `-Xmx768m` 산정 근거, t3 크레딧 고갈 실측 |
| [guide/profile.md](../../guide/profile.md) | 프로필 적용·확인 절차, 수동 절차 제로화 원칙 |
| [terraform/loadtest/README.md](../../../terraform/loadtest/README.md) | terraform 운용 관례, drift 사고 사례, plan 시점 함정 |
| [deploy/prod/README.md](../../../deploy/prod/README.md) | JVM 옵션 정본과 확인 절차, 해소 대상인 한계 3개 |
