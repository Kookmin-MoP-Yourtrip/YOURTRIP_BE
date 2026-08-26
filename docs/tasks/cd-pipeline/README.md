# CD 파이프라인 — 배포 대상을 terraform 밖으로 빼서 apply 없이 굴린다

> [#120](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/120)의 설계·판정 기준 기록이다. 아래 판정 기준은 **구축 전에 못 박은 것**이고, 실제 검증 결과는 [verification.md](verification.md)에 있다.
>
> **왜 하는가**: 교체는 이미 자동화돼 있는데 **빌드와 업로드가 수동이라 전체가 수동**이다. [deploy/prod/README.md](../../../deploy/prod/README.md)의 한계 절이 그 상태를 정확히 적고 있었다 — *"빌드와 업로드는 여전히 수동이다 … 완전한 CD는 후속 과제다."* [#119](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/119)가 ALB·ASG·instance refresh를 갖춰 놓았으므로, 남은 것은 부품을 잇는 일이다.
>
> **결론만 먼저**: **JAR 버전 하나만 terraform 밖으로 뺀다.** Launch Template에 박혀 있던 아티팩트 키를 SSM Parameter Store로 옮기면 LT가 불변이 되고, 배포는 `ssm put-parameter` + `start-instance-refresh` 두 번의 API 호출로 끝난다. terraform state를 러너가 만질 필요가 없어지는 것이 이 설계의 전부다.
>
> **확정하지 못한 것**: instance refresh 1회에 걸리는 실제 시간을 모른다. `InstanceWarmup`을 ASG 기본값(300초)에 맡겨 두고 **측정한 뒤** 조정 여부를 판단한다 — 측정 전에 튜닝하지 않는다. 또 refresh가 실패한 뒤 ASG가 불량 인스턴스를 반복 교체하는지는 **예상일 뿐 관측 대상**이다(C7).

## 이 작업이 만드는 것

```
dev 머지
   │
   ├─ build   job (AWS 자격증명 없음) ── gradlew build ── boot jar 식별 ── 업로드
   │
   └─ release job (OIDC 임시 자격증명)
         ├─ S3에 app/<sha>.jar 업로드
         ├─ ASG 있나? ── 없으면 여기서 초록불로 종료 (온디맨드 평시)
         ├─ SSM /yourtrip/prod/artifact_key 갱신
         ├─ start-instance-refresh (min_healthy 100 → 먼저 띄우고 나중에 죽인다)
         ├─ Successful / Failed 까지 폴링
         └─ 타깃 그룹 healthy 확인
```

롤백은 별도 경로가 아니다. `workflow_dispatch`에 이전 SHA를 넣으면 **같은 파이프라인의 뒤쪽 절반**이 그대로 돈다.

## 무엇이 이미 있었나

부품은 대부분 갖춰져 있었다. 이 작업은 새로 만든 것보다 **잇는 것**이 많다.

| 이미 있던 것 | 어디 |
|---|---|
| 아티팩트 S3 버킷(버저닝 Enabled) | `terraform/prod-permanent/s3_artifacts.tf` |
| EC2 역할의 아티팩트 읽기·SSM 조회 권한 | `terraform/prod/iam.tf` — `/yourtrip/prod/*`를 이미 덮고 있어 **추가 권한이 필요 없었다** |
| ASG instance refresh(Rolling, 100/200) | `terraform/prod/asg.tf` |
| ELB 헬스체크(`/actuator/health/liveness`) | `terraform/prod/alb.tf` |
| CI(테스트 + `bootJar`) | `.github/workflows/ci.yml` |

없던 것은 **GitHub이 AWS에 붙는 경로**와 **배포 워크플로** 둘뿐이었다.

## 설계 원칙 1 — JAR 버전만 terraform 밖으로 뺀다

문제의 뿌리는 user-data 한 줄이었다.

```bash
aws s3 cp "s3://${artifact_bucket}/${artifact_key}" /opt/app/app.jar
```

`${artifact_key}`는 `terraform.tfvars`의 값이 apply 시점에 **상수로 박힌** 것이다. S3에 새 JAR을 올려도 인스턴스는 옛 키를 받는다. 새 버전을 내보내려면 Launch Template의 새 버전이 필요하고, 그것은 곧 `terraform apply`가 필요하다는 뜻이다.

그런데 이 저장소는 **원격 backend가 없다.** 로컬 `terraform.tfstate`가 유일한 진실 공급원이고 `terraform.tfvars`에는 `my_ip_cidr` 같은 로컬 의존 값이 있다. 러너에서 apply를 돌리는 정석 경로가 막혀 있다.

### 세 가지 경로 비교

| | **A. SSM 간접 참조 (채택)** | B. CD가 LT 새 버전 생성 | C. 원격 backend + apply |
|---|---|---|---|
| CD가 하는 일 | `ssm put-parameter` + `start-instance-refresh` | `create-launch-template-version` + `update-auto-scaling-group` + refresh | `terraform apply` |
| tfstate drift | **없다.** LT가 불변이다 | **생긴다.** CLI로 만든 LT 버전을 state가 모른다 | 없다 |
| CD에 필요한 권한 | SSM 1개 + ASG 3개 | `ec2:CreateLaunchTemplateVersion`, `autoscaling:UpdateAutoScalingGroup` | 사실상 인프라 전체 변경권 |
| state 접근 | 불필요 | 불필요 | **필요** — 이 이슈 범위 밖의 선행 작업이 붙는다 |
| 저장소 규칙과의 정합 | 형상을 안 바꾸므로 충돌 없음 | [CLAUDE.md](../../../CLAUDE.md)의 *"형상을 콘솔·CLI로 직접 바꾸지 않는다"* 와 정면 충돌 | 충돌 없음 |
| 판정 | ✅ | ❌ drift | ❌ 전제가 범위 밖 |

B는 업계에서 흔한 방식이지만 이 저장소에서는 쓸 수 없다. **terraform이 만든 LT를 CLI가 건드리면 다음 apply 때 조용히 되돌아간다** — 배포한 버전이 apply 한 번에 사라지는 종류의 사고다.

### SSM 파라미터를 terraform이 관리하지 않는 이유

시크릿과 같은 이유(값이 tfstate에 남는 문제)가 여기엔 적용되지 않는다. 아티팩트 키는 비밀이 아니다. 그런데도 관리하지 않기로 한 **결정적 이유는 따로 있다.**

| | terraform이 관리한다 | **관리하지 않는다 (채택)** |
|---|---|---|
| destroy 후 재apply | **tfvars에 적힌 옛 SHA로 되돌아간다** — CD가 내보낸 최신본을 잃는다 | **파라미터가 살아남아 마지막으로 배포된 JAR로 그대로 뜬다** |
| 매 배포 후 plan | CD가 값을 바꾸므로 drift가 뜬다(`ignore_changes`로 덮어야 한다) | 리소스가 아니므로 drift 자체가 없다 |
| 저장소 관례 | 시크릿은 이미 "terraform 밖 CLI 1회 등록"이라 관례가 둘로 갈라진다 | 관례 일치 |

**운영이 온디맨드 모델이라는 점이 이 결정을 만들었다.** 서버를 내렸다 다시 올리는 일이 잦은데, 그때마다 "마지막에 뭘 배포했더라"를 사람이 기억해 tfvars에 적어야 한다면 자동화의 의미가 절반은 사라진다.

대신 **조용한 실패는 막는다.** 파라미터가 없으면 인스턴스는 정상적으로 뜨고 user-data만 실패해, 헬스체크가 계속 깨지는 이유를 찾아 로그를 뒤져야 한다. 그래서 terraform이 값을 **읽기만** 한다.

```hcl
data "aws_ssm_parameter" "artifact_key" {
  name = "${var.ssm_parameter_path}/artifact_key"
}
```

등록돼 있지 않으면 **plan이 그 자리에서 실패한다.** 값을 쓰지 않고 존재만 강제하는 것이 목적이다. 대가로 파라미터를 지운 뒤 destroy가 막히는 경우가 생기는데, 탈출구(`terraform state rm`)를 [terraform/prod/README.md](../../../terraform/prod/README.md) 트러블슈팅에 적어 뒀다.

## 설계 원칙 2 — 저장소에 비밀을 두지 않는다

[ci.md](../../guide/ci.md) §3이 *"CI가 시크릿을 요구하게 되는 순간이 곧 설계가 틀어진 신호다"* 라고 못박아 두고, 같은 표에서 CD의 몫을 **"AWS 인증 수단만"** 으로 예고해 뒀다. 그 칸을 채우는 방식이 이 원칙을 결정한다.

### 인증 방식 비교

| 축 | 정적 액세스 키(GitHub Secrets) | **OIDC → `sts:AssumeRoleWithWebIdentity` (채택)** |
|---|---|---|
| 자격증명 수명 | **무기한.** 지우기 전까지 유효하다 | 실행당 1시간, 자동 만료 |
| 저장소에 있는 것 | 운영 계정의 실제 비밀 | 역할 ARN — 비밀이 아니다 |
| 유출 시 | 언제 어디서든 사용 가능 | 만료된 토큰은 재사용 불가 |
| 로테이션 | 사람이 주기적으로. 실제로는 안 한다 | **불필요** |
| 접근 제한 | 워크플로를 수정할 수 있는 사람 전원 | 신뢰 정책이 **브랜치 단위**로 제한 |
| 설정 비용 | 낮다(키 2개 붙여넣기) | 높다(provider + 역할 + 신뢰 정책) — **terraform으로 코드화하면 1회** |

**이 저장소는 public이다.** 운영 계정 키를 GitHub에 상주시키는 선택은 그 사실 하나만으로도 어렵다. 설정 비용은 한 번이고 그 뒤로 로테이션 부담이 0이 되므로, 총비용에서도 OIDC가 이긴다.

결과적으로 **CD에도 저장된 시크릿이 0개**다. `ci.md` §3의 표는 "AWS 인증 수단만"이 아니라 "없음"으로 갱신됐다.

### 왜 `prod`가 아니라 `prod-permanent`인가

| 후보 | 판단 |
|---|---|
| `terraform/`(루트) | ❌ 이 state에는 앱용 IAM 액세스 키가 평문으로 들어 있다. 건드리는 횟수를 늘릴 이유가 없다 |
| `terraform/prod/` | ❌ **destroy 대상이다.** 서버가 내려간 평시에도 dev 머지마다 S3 업로드는 계속돼야 하는데, 역할이 여기 있으면 destroy 직후부터 **AssumeRole 자체가 실패해** 워크플로가 빨간불이 된다 |
| **`terraform/prod-permanent/`** | ✅ 아티팩트 버킷과 수명이 같다. 버킷 ARN을 같은 state에서 직접 참조할 수 있어 하드코딩도 사라진다 |

남는 결합이 하나 있다 — ASG는 `prod`가 만드는데 정책은 `prod-permanent`가 쓴다. 두 모듈의 `name_prefix` 기본값이 같다는 데 기대어 이름으로 ARN을 재구성하며, 이 암묵적 결합을 코드 주석에 명시해 뒀다.

### `sub` 조건을 `dev`로 못박는다

```
repo:Kookmin-MoP-Yourtrip/YOURTRIP_BE:ref:refs/heads/dev
```

롤백용 `workflow_dispatch`도 이 조건에 걸린다. dispatch의 `sub` 클레임은 **이벤트 종류가 아니라 선택된 ref**로 결정되기 때문이다. 그래서 **롤백은 반드시 `dev` 브랜치를 선택해 실행해야 한다.**

브랜치를 더 열어 둘 이유도 없다. 워크플로 파일은 기본 브랜치에 있어야 트리거되고 dispatch 버튼도 거기서만 나타나므로, `dev` 아닌 ref로 이 역할이 필요한 상황이 실제로 생기지 않는다.

`aud` 조건(`sts.amazonaws.com`)을 함께 건다. **`sub`만 걸고 `aud`를 빠뜨리는 것이 이 패턴에서 가장 흔한 구멍이다.**

### 최소 권한 — 주지 않은 것이 더 중요하다

| 주지 않은 것 | 왜 |
|---|---|
| `ssm:SendCommand` | `AWS-RunShellScript`는 운영 인스턴스의 **root 원격 실행**이다. 배포 파이프라인이 이걸 가지면 워크플로를 수정할 수 있는 권한이 곧 운영 서버 셸 권한이 된다 |
| `ssm:GetParametersByPath` | `/yourtrip/prod/env/*`의 시크릿을 통째로 조회할 수 있게 된다 |
| `s3:GetObject` | 롤백 전 존재 확인에는 `ListBucket` + prefix 조건이면 충분하다. 읽기까지 주면 CD가 과거 배포본을 통째로 내려받을 수 있다 |
| `s3:DeleteObject` | 아티팩트는 롤백의 근거다. 파이프라인이 지울 수 있으면 안 된다 |
| `ec2:*`, `autoscaling:UpdateAutoScalingGroup` | 형상 변경. **인프라의 모양은 terraform만 바꾼다**는 규칙을 IAM으로 강제한다 |

`ssm:PutParameter`는 `/yourtrip/prod/*`가 아니라 **`artifact_key` 하나로 한정**한다. 경로로 넓히면 CD가 `DB_PASSWORD`와 `JWT_SECRET`을 덮어쓸 수 있다.

`autoscaling`·`elasticloadbalancing`의 `Describe*`는 리소스 수준 권한을 지원하지 않아 `*`일 수밖에 없다. 읽기 전용이라 수용한다.

## 설계 원칙 3 — 두 경로가 같은 파일을 읽는다

LT를 불변으로 만든 대가로 **refresh를 부르는 주체가 둘**이 됐다.

| 부르는 쪽 | 언제 | preferences 출처 |
|---|---|---|
| terraform | LT가 바뀌는 apply(AMI 핀 변경 등) | `asg.tf`의 `instance_refresh` 블록 |
| CD 워크플로 | 매 배포 | `start-instance-refresh --preferences` |

**CLI 경로에는 terraform의 블록이 적용되지 않는다.** 명시하지 않으면 AWS 기본값 `MinHealthyPercentage = 90`이 쓰이는데, `desired = 1`에서 그 값은 *먼저 죽이고 나중에 띄우는* 순서를 허용한다. `asg.tf`가 무중단을 위해 잡아둔 100/200이 CLI 경로에서 조용히 무력화되는 것이다.

숫자를 양쪽에 각자 적으면 언젠가 한쪽만 고친다. 그래서 **파일 하나를 둘이 읽는다.**

```
deploy/prod/instance-refresh-preferences.json
   ├── terraform : jsondecode(file(...)) → instance_refresh.preferences
   └── CD        : --preferences file://...
```

이는 새 발명이 아니라 이 저장소가 이미 쓰는 방식이다 — `deploy/prod/yourtrip-app.service`와 `jvm-opts.env`를 user-data가 `file()`로 읽어 주입하는 것과 같은 구조다([prod-infra-iac](../prod-infra-iac/README.md)의 "설계 원칙 3").

### `SkipMatching`이 이 설계의 급소다

`SkipMatching`은 "이미 목표 Launch Template 버전으로 도는 인스턴스는 건너뛴다"는 옵션이다. 일반적인 구성에서는 유용한 최적화지만, **LT가 불변인 이 설계에서는 fleet 전체가 skip 대상이 된다.**

```
SkipMatching: true  →  교체할 인스턴스가 없다고 판단
                    →  refresh가 즉시 Successful
                    →  워크플로 초록불
                    →  그런데 옛 JAR이 계속 돈다
```

**배포가 성공한 것처럼 보이면서 아무것도 배포되지 않는다.** 실패 중 가장 나쁜 종류다.

기본값이 `false`라 명시하지 않아도 동작은 하지만, 정본 파일과 terraform 양쪽에 **값을 드러내고 이유를 주석으로 남겼다.** LT를 불변으로 만든 대가로 생긴 함정이라, 나중에 누군가 "배포 시간을 줄이자"며 켜기 쉽기 때문이다.

### `AutoRollback`을 켜지 않는다

Instance Refresh에는 실패 시 자동 롤백 옵션이 있다. 안전장치처럼 보이지만 **이 설계에서는 아무것도 하지 못한다.**

- `AutoRollback`은 **이전 Launch Template 버전 / 원래 desired configuration**으로 되돌린다.
- 이 구성에서 LT는 배포마다 바뀌지 않는다. 되돌릴 이전 버전이 곧 현재 버전이다.
- 되돌아가도 새 인스턴스는 부팅할 때 SSM에서 **여전히 같은(깨진) 키**를 읽는다.

즉 켜두면 복구는 못 하면서 "롤백이 걸려 있다"는 잘못된 안심만 만든다. **롤백의 유일한 실제 수단은 SSM 값을 되돌려 다시 굴리는 것**이고, 그것이 `workflow_dispatch` 경로다.

이슈 체크리스트가 요구한 *"실패 시 교체 자동 중단"* 은 AutoRollback 없이도 성립한다 — refresh는 새 인스턴스를 healthy로 승격하지 못하면 `Failed`로 멈추고, `min_healthy 100` 덕분에 **기존 인스턴스는 죽지 않는다.**

## 빌드와 배포를 job으로 가른다

`build`에는 `id-token: write`를 주지 않는다.

`./gradlew build`는 서드파티 Gradle 플러그인과 의존성의 코드를 러너에서 실행한다. 그 프로세스가 OIDC 토큰을 얻을 수 있으면 **악성 의존성 하나로 운영 계정 역할이 탈취된다.** job을 갈라 두면 AWS를 다루는 쪽은 프로젝트 빌드 코드를 아예 실행하지 않는다.

같은 이유로 `ci.yml`에 배포를 붙이지 않았다. 그 파일의 *"secrets도 env도 하나 없다"* 는 성질은 문서화된 설계 자산이고, 거기에 `id-token` 권한을 넣으면 그 문장이 거짓이 된다.

## 사전 등록한 판정 기준

측정·검증 **전에** 못 박는다. 사후에 완화하지 않는다.

| # | 항목 | 통과 기준 |
|---|---|---|
| C1 | OIDC 인증 성립 | 워크플로의 `aws sts get-caller-identity`가 `assumed-role/yourtrip-prod-github-actions-role/...` |
| C2 | 저장된 시크릿 0개 | `gh secret list`에 AWS 관련 항목 **0건**, 워크플로 파일의 `secrets.` 참조 **0건** |
| C3 | 아티팩트 규약 | `app/<7자리 sha>.jar`가 존재하고, `unzip -l`에 **`BOOT-INF/` 포함**(plain jar이 아님) |
| C4 | **LT 불변 / drift 0** | 배포 후 `terraform plan`에 `aws_launch_template`·`aws_autoscaling_group` 변경 **없음**. `Changes to Outputs`만 있는 것은 통과 — `current_artifact_key`가 CD로 바뀐 결과다. `app_ami_id`를 핀한 상태에서 측정한다 |
| C5 | **무중단** | 배포 전 구간 외부 1초 간격 요청 **실패 0건**. 총 요청 수와 구간을 함께 기록한다 |
| C6 | 새 버전 실적용 | refresh `Successful` + InService 인스턴스의 `LaunchTime`이 `put-parameter` 시각 **이후** + `/opt/app/app.jar` 체크섬이 새 S3 객체와 일치 |
| C7 | **실패 시 자동 중단** | 깨진 아티팩트 배포 시 ①refresh가 `Successful`이 되지 **않음** ②기존 인스턴스가 `InService`/`healthy` 유지 ③외부 요청 실패 **0건** ④워크플로 **빨간불** — 넷 모두 |
| C8 | 롤백 | dispatch로 이전 SHA 지정 시 refresh `Successful` + 타깃 `healthy` + C6를 이전 SHA로 만족. **트리거부터 healthy까지 소요 시간을 기록**(기준값 없음, 측정이 목적) |
| C9 | ASG 부재 시 스킵 | destroy 상태의 dev push에서 ①S3 업로드 성공 ②교체 스텝 `skipped` ③워크플로 **초록불** ④`artifact_key` 값 **불변** |
| C10 | 최소 권한 실증 | CD 자격증명으로 넷 다 `AccessDenied`: 버킷의 `app/` 밖 쓰기, `/yourtrip/prod/env/JWT_SECRET` 쓰기, `autoscaling update-auto-scaling-group`, `ec2 describe-instances` |

### C7을 어떻게 유발할 것인가

**실제로 일어날 법한 사고를 그대로 재현한다.** 임의의 깨진 파일보다 `-plain.jar`가 낫다.

```bash
./gradlew build
aws s3 cp build/libs/yourtrip-0.0.1-SNAPSHOT-plain.jar s3://<bucket>/app/0000000.jar
```

plain jar는 유효한 zip이고 매니페스트도 있지만 `Main-Class`가 없다. `java -jar`가 즉시 실패하고, systemd의 `Restart=on-failure`가 5초마다 재시도하며, 8080은 끝내 열리지 않는다 → 타깃 헬스체크 실패 → refresh가 새 인스턴스를 승격하지 못한다.

이 시나리오를 고른 이유는 **워크플로가 막고 있는 실패가 정확히 이것**이기 때문이다(`build/libs/*.jar` 글롭의 함정). 방어 로직을 우회해 넣어 봄으로써, 그 방어가 없었다면 무슨 일이 났을지를 함께 보여 준다.

원상복구는 C8(롤백)과 같은 절차다 — **실패 검증의 출구가 곧 롤백 검증의 입구다.** 끝난 뒤 `app/0000000.jar`를 지운다.

## 한계

- **검증은 데모용 1회 apply 위에서 이뤄진다.** 실트래픽이 사실상 0인 환경이라, C5의 "무중단"은 **인위적으로 발생시킨 1초 간격 요청** 기준이다. 실제 사용자 부하 중의 교체는 재지 않았다.
- **아티팩트가 무한히 쌓인다.** 버킷에 수명주기 규칙이 없고 버저닝까지 켜져 있어 이중으로 누적된다. 지금은 객체가 몇 개뿐이지만 dev 머지마다 약 50MB가 붙는다. 보존 기간은 "롤백 대상이 사라지면 안 된다"는 제약 아래 따로 정해야 해서 이번 범위에서 뺐다.
- **`build` job이 CI와 같은 일을 중복해서 한다.** dev push마다 `./gradlew build`가 두 번 돈다(ci.yml과 여기). `workflow_run`으로 CI 성공에 게이트하는 방법을 검토했으나, 워크플로 파일이 기본 브랜치 판본으로 실행돼 디버깅이 어렵고 `workflow_dispatch` 롤백 경로와 형태가 맞지 않아 기각했다. 대가는 러너 시간 수 분이다.
- **배포가 인프라를 올리지는 않는다.** ASG가 없으면 업로드만 하고 끝난다. 서버 기동은 여전히 사람이 `terraform apply`로 한다 — 온디맨드 모델에서 의도한 경계이지만, "머지하면 서비스에 반영된다"가 항상 참은 아니라는 뜻이다.
- **워크플로 자체는 CI가 검증하지 않는다.** YAML 문법 오류나 스텝 로직 오류는 dev에 머지된 뒤 실행돼야 드러난다.

## 참고 문서

| 문서 | 이 문서에서 쓴 내용 |
|---|---|
| [docs/guide/cd.md](../../guide/cd.md) | 배포·롤백 실행 절차(반복 참조용) |
| [verification.md](verification.md) | C1~C10 판정 결과와 실측값 |
| [docs/tasks/prod-infra-iac/](../prod-infra-iac/README.md) | ASG·instance refresh·SSM 시크릿 구조의 근거. 이 작업의 직전 선행 |
| [docs/guide/ci.md](../../guide/ci.md) | CI와의 책임 경계, 시크릿을 쓰지 않는 원칙 |
| [terraform/prod/README.md](../../../terraform/prod/README.md) | SSM 파라미터 등록, `artifact_key` 관련 트러블슈팅 |
| [terraform/prod-permanent/README.md](../../../terraform/prod-permanent/README.md) | OIDC 역할 위치와 저장소 변수 등록 |
| [deploy/prod/README.md](../../../deploy/prod/README.md) | JVM 옵션 정본과 배포 후 확인 명령 |
