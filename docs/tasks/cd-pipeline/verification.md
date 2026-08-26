# CD 파이프라인 검증 — 9개 통과, 실패 대응에서 결함이 나왔다

> **10개 중 9개 통과, 1개(C7) 기각이다.** 기각된 항목은 "배포가 실패해도 서비스가 살아남는가"인데, 실패 자체는 잘 막았고 **워크플로가 그 실패를 정리하는 방식이 서비스를 3분 53초 끊었다.**
>
> **원인**: 폴링이 25분 타임아웃에 걸리자 워크플로가 `cancel-instance-refresh`를 호출했다. 취소되는 순간 교체를 위해 2로 늘어나 있던 용량이 desired 1로 줄고, ASG가 종료 대상을 고르는데 **기본 종료 정책은 건강 여부를 보지 않고 가장 오래된 인스턴스를 집는다** — 그게 하필 유일하게 살아 있던 정상 인스턴스였다. 자세한 추이는 아래 "마주친 문제" 3번에 있다.
>
> **고친 것**: 타임아웃에서 refresh를 취소하지 않고 job만 실패시킨다. 진행 중인 교체를 그대로 두면 기존 인스턴스가 계속 트래픽을 받는다. CD 역할에서 `autoscaling:CancelInstanceRefresh` 권한도 회수해, 그 경로가 다시 생기지 않게 했다. **수정 후 재측정은 하지 않았다** — 재현하려면 25분 타임아웃을 다시 만들어야 하고 그동안 인프라를 띄워 둬야 한다.
>
> **판정을 완화하지 않은 이유**: 사전 등록한 C7의 조건은 넷 모두를 요구했고 그중 "외부 요청 실패 0건"이 깨졌다. 실패가 refresh 진행 중이 아니라 취소 순간에 났다는 사정은 있지만, 그 사정을 근거로 통과시키면 **기준을 측정 전에 못 박은 의미가 사라진다.** 사용자 입장에서 3분 53초 동안 서비스가 안 된 것은 사실이다.
>
> 나머지 9개 중 4개(C1·C2·C3·C9)는 **비용 없이** 쟀다. 운영이 온디맨드라 평시에 ASG가 없는데, `dev`에 머지하니 워크플로가 업로드까지만 하고 초록불로 끝났고 그 한 번의 실행이 넷을 동시에 증명했다 — **검증하려던 상태가 마침 기본 상태였다.**

## 측정 환경

| | |
|---|---|
| 1차 측정일 (C1·C2·C3·C9) | 2026-08-26 — 서버가 내려간 상태 |
| 2차 측정일 (나머지) | 2026-08-26 — `terraform/prod` apply 후 |
| AMI (핀 고정) | `ami-0ba2a8e103d0623b8` (AL2023 2023.12.20260817.0) |
| 앱 인스턴스 | t3.small (vCPU 2 / 2GB), AL2023 + Corretto 21 |
| AMI | *(측정 시 `app_ami_id`에 핀한 값을 기재)* |
| ASG | min 1 / max 2 / desired 1, `health_check_type = ELB` |
| refresh 조건 | `MinHealthyPercentage 100` / `MaxHealthyPercentage 200` / `SkipMatching false` |
| 도메인 | `yourtrip.cloud` |

> **`app_ami_id`를 핀하고 측정한다.** 핀하지 않으면 `data.aws_ami.al2023`이 plan마다 최신 이미지를 잡아 Launch Template이 바뀌는데, 그러면 **C4(LT 불변)를 AMI 회전과 구분할 수 없다.**

## 실행 순서

비용 때문에 순서가 정해져 있다. **C9(ASG 부재)를 마지막 근처에 두는 이유**는 그 상태가 destroy 이후에 자연히 생기기 때문이다 — 순서를 지키면 서버를 두 번 띄우지 않아도 된다.

| # | 단계 | 확인하는 기준 |
|---|---|---|
| 0 | `prod-permanent` apply → `gh variable set` → SSM `artifact_key` 등록 | — |
| 1 | `prod` apply (user-data 전환 + 서버 기동) | — |
| 2 | 권한 경계 확인 job 1회 실행 | C10 |
| 3 | `dev` 머지 → 정상 배포 1회 | C1, C2, C3, C5, C6 |
| 4 | 배포 후 `terraform plan` | C4 |
| 5 | 깨진 아티팩트 배포 | **C7** |
| 6 | 이전 SHA로 롤백 | **C8** |
| 7 | `prod` destroy | — |
| 8 | 서버 없는 상태로 `dev` push | C9 |
| 9 | `prod` 재apply → 파라미터 생존 확인 → 다시 destroy | C6 보강 |

실제로는 destroy 후 파라미터만 확인해 9단계를 대신했다. `terraform destroy`로 41개 리소스를 모두 내린 뒤에도 `/yourtrip/prod/artifact_key`가 `app/5a5d515.jar`(Version 4)로 남아 있었다 — **"다시 올리면 마지막 배포본으로 뜬다"의 전제가 성립함**을 서버를 한 번 더 띄우지 않고 확인한 것이다. 고아 리소스(ALB·ASG·RDS·ElastiCache·EC2)는 전부 0건이었다.

> 9단계는 선택이다. "destroy했다가 다시 올리면 마지막 배포본으로 뜬다"는 이 설계의 주장을 실증하는 단계인데, 확인하려면 서버를 한 번 더 띄워야 한다. 비용을 감수할 때만 한다.

## 판정 결과

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| C1 | OIDC 인증 성립 | **통과** | 실행 [32929695826](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/actions/runs/32929695826)의 `AWS 자격증명 (OIDC)` 스텝 성공. 저장소에 AWS 시크릿이 하나도 없는 상태에서 임시 자격증명을 받았다 |
| C2 | 저장된 시크릿 0개 | **통과** | `gh secret list`가 빈 목록. 워크플로의 `secrets.` 참조 0건 |
| C3 | 아티팩트 규약 | **통과** | 머지 커밋 `809cb73` → `app/809cb73.jar` (113,316,917 B). `build` job의 `BOOT-INF/` 구조 검사를 통과한 산출물이며, plain jar(383 KB)와 크기가 300배 차이 난다 |
| C4 | LT 불변 / drift 0 | **통과** | 배포 후 `terraform plan`이 *"You can apply this plan to save these new output values to the Terraform state, **without changing any real infrastructure**"* — 리소스 변경 0건, `current_artifact_key` 출력만 `app/3146ef0.jar` → `app/5a5d515.jar` |
| C5 | 무중단 | **통과** | 05:18:18~05:27:21 UTC(9분 3초) 1초 간격 **433건 전부 200**. 교체 전 구간을 포함하며 200 아닌 응답 0건 |
| C6 | 새 버전 실적용 | **통과** | 교체된 인스턴스의 `/opt/app/app.jar` md5 `3709aba5ef04890e575c79a0318b44e6` = S3 `app/5a5d515.jar`와 일치(이전 인스턴스는 `c46c43bf…`). `LaunchTime` 05:20:38 > `put-parameter` 05:20:23. refresh `Successful` 6분 12초 |
| C7 | 실패 시 자동 중단 | **기각** | ①refresh 미성공 ②기존 인스턴스 생존 ④워크플로 빨간불은 충족. **③외부 요청 실패 0건이 깨졌다** — refresh 취소 시점에 502가 121건(3분 53초). 원인·수정은 "마주친 문제" 3번 |
| C8 | 롤백 | **통과** | dispatch로 `5a5d515` 지정 → SSM Version 4로 복원, refresh `Successful`(05:57:37~06:02:52, **5분 15초**), 타깃 healthy. 트리거(05:57:10)부터 서비스 응답 복구(05:59:46)까지 **2분 36초** |
| C9 | ASG 부재 시 스킵 | **통과** | 업로드 성공 · 교체 관련 5개 스텝 전부 `skipped` · 워크플로 초록불 · **`artifact_key`가 Version 1 `app/3146ef0.jar` 그대로** — 값을 건드리지 않아야 다음 apply가 마지막 배포본으로 뜬다 |
| C10 | 최소 권한 실증 | **통과** | 프로브 5개 전부 거부 — 실행 [32933562944](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/actions/runs/32933562944). 아래 상세 |

## 구현 단계에서 로컬로 확인한 것

AWS 없이 확인할 수 있는 것은 먼저 확인했다. 아래는 실측이 아니라 **구현 검증**이며, 위 판정 결과와는 별개다.

### `-plain.jar` 함정이 실재한다 (C3의 전제)

`./gradlew assemble`의 산출물:

| 파일 | 크기 |
|---|---|
| `yourtrip-0.0.1-SNAPSHOT.jar` | 113,321,795 B (boot jar) |
| `yourtrip-0.0.1-SNAPSHOT-plain.jar` | 383,004 B |

`build/libs/*.jar`는 **두 파일에 매칭된다**(`ls build/libs/*.jar | wc -l` → `2`). 이 저장소에 남아 있던 업로드 안내가 정확히 그 글롭을 쓰고 있었으므로, 실제로 잘못된 JAR이 올라갈 수 있는 상태였다. plain jar가 배포되면 인스턴스는 `no main manifest attribute`로 뜨지 못한다.

워크플로의 2단 방어를 같은 명령으로 확인했다.

```bash
JAR=$(find build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar' -print -quit)
# -> build/libs/yourtrip-0.0.1-SNAPSHOT.jar
unzip -l "$JAR" | grep -q 'BOOT-INF/'                          # 통과
unzip -l build/libs/*-plain.jar | grep -q 'BOOT-INF/'          # 걸림 (BOOT-INF 없음)
```

1차(이름)로 걸러진 파일이 2차(구조)도 통과하고, plain jar는 2차에서 확실히 걸린다. **이름 규칙이 바뀌더라도 구조 검사가 남는다**는 것이 2단으로 둔 이유다.

### terraform 구성이 유효하다

```bash
terraform -chdir=terraform/prod validate           # Success
terraform -chdir=terraform/prod-permanent validate # Success
terraform fmt -check                               # 두 모듈 모두 차이 없음
```

`skip_matching` 인자와 `aws_iam_openid_connect_provider`의 `thumbprint_list` 생략이 provider 5.100에서 유효함을 이 단계에서 확인했다.

### 워크플로에 시크릿 참조가 없다

```bash
grep -c "secrets\." .github/workflows/deploy.yml   # 0
```

C2의 절반(워크플로 파일 쪽)은 이것으로 충족된다. 나머지 절반(`gh secret list`)은 저장소 설정이라 측정 시 확인한다.

## 실측 상세

### C10 — 다섯 개 프로브가 전부 거부됐다

`sts:GetCallerIdentity`는 `arn:aws:sts::…:assumed-role/yourtrip-prod-github-actions-role/GitHubActions`를 반환했다(C1). 그 자격증명으로 시도한 다섯 가지는 모두 막혔다.

| 프로브 | 오류 |
|---|---|
| 아티팩트 버킷의 `app/` 밖에 쓰기 | `AccessDenied` — `s3:PutObject` on `…/other/probe` |
| 시크릿 경로에 쓰기 | `AccessDeniedException` — `ssm:PutParameter` |
| 시크릿 목록 조회 | `AccessDeniedException` — `ssm:GetParametersByPath` |
| ASG 형상 변경 | `AccessDenied` — `autoscaling:UpdateAutoScalingGroup` |
| EC2 조회 | `UnauthorizedOperation` — `ec2:DescribeInstances` |

프로브는 **통과해 버렸을 때의 피해가 0이 되도록** 골랐다 — 시크릿 쓰기는 실제 키가 아니라 존재하지 않는 이름에, 읽기는 값이 아니라 이름 목록만, ASG 변경은 현재와 같은 `desired-capacity`로 시도했다. 기대값은 거부지만, 정책에 구멍이 있었다면 그 사실만 드러나고 운영은 멀쩡해야 한다.

### C7 — 실패한 배포는 트래픽에 닿지 못한다

`-plain.jar`을 `app/0000000.jar`로 올려 배포했다. 유효한 zip이고 매니페스트도 있지만 `Main-Class`가 없어 `java -jar`이 즉시 죽는다.

30초 간격으로 관측한 추이다.

| 시각(UTC) | 기존 인스턴스 | 새 인스턴스 | refresh |
|---|---|---|---|
| 05:30:55 | InService / healthy | 생성됨, TG `initial` | InProgress 25% |
| 05:32:17 | InService / healthy | **`unhealthy`** | InProgress 25% |
| 05:37:03 | InService / healthy | `Terminating`, **또 다른 인스턴스 생성** | InProgress 25% |
| 05:38:13 | InService / healthy | 새 인스턴스도 `unhealthy` | InProgress 25% |
| 05:45:24 | InService / healthy | 계속 교체 중 | InProgress 25% |

**기존 인스턴스는 전 구간 `InService` / `healthy`를 유지했고, 외부 요청은 한 건도 실패하지 않았다.** `min_healthy 100`이 "새 인스턴스가 건강해지기 전에는 기존 것을 죽이지 않는다"를 강제하므로, 교체가 25%에서 더 나아가지 못한 채 서비스는 그대로 돌았다.

#### 예상했지만 확인이 필요했던 것 — 교체 루프는 남는다

설계 문서에 *"refresh 실패 후 ASG가 불량 인스턴스를 반복 교체하는지는 예상일 뿐 관측 대상"*이라고 적어 뒀는데, 실제로 그랬다. `health_check_type = "ELB"`인 ASG는 헬스체크에 실패한 인스턴스를 죽이고 새로 띄우며, systemd의 `Restart=on-failure`가 앱을 5초마다 재시작하지만 8080은 끝내 열리지 않는다. 그래서 **인스턴스가 계속 생성·종료되는 루프**가 만들어진다.

정확한 서술은 이것이다 — **실패한 배포는 트래픽에 닿지 않지만, 인스턴스 교체 루프는 남는다.** 서비스는 안전하고 과금은 계속된다. 진단 중 fleet을 얼려 두려면 `suspend-processes`를 쓴다([terraform/prod/README.md](../../../terraform/prod/README.md)).

#### 그리고 여기서 판정이 깨졌다

25분이 지나 워크플로가 폴링을 포기하고 refresh를 **취소하자** 서비스가 끊겼다. 25분 내내 멀쩡하던 것이 정리 동작 한 번에 무너진 것이다. 아래 "마주친 문제" 3번이 그 전말이다.

## 항목별 확인 방법

### C1 · C2 — 인증과 시크릿

```bash
gh secret list
```

AWS 관련 항목이 0건이어야 한다. 워크플로 파일에도 `secrets.` 참조가 없다.

```bash
grep -c "secrets\." .github/workflows/deploy.yml
```

C1은 실행 로그의 `configure-aws-credentials` 스텝이 성공하는 것으로 확인한다.

### C3 — boot jar인가

```bash
BUCKET=$(terraform -chdir=terraform/prod-permanent output -raw artifact_bucket_name)
aws s3 cp "s3://$BUCKET/app/<sha>.jar" /tmp/check.jar
unzip -l /tmp/check.jar | grep -c 'BOOT-INF/'
```

0이면 plain jar가 올라간 것이다. 워크플로의 방어가 뚫렸다는 뜻이므로 **기각**이다.

### C4 — Launch Template이 안 바뀌었나

```bash
terraform -chdir=terraform/prod plan
```

`aws_launch_template.app`·`aws_autoscaling_group.app`에 변경이 없어야 한다. `Changes to Outputs`에 `current_artifact_key`만 나오는 것은 **통과**다 — CD가 SSM 값을 바꾼 결과이지 인프라 drift가 아니다.

### C5 — 무중단

배포 중 별도 셸에서 1초 간격으로 친다. `/actuator/*`는 ALB가 403으로 막으므로 공개 경로를 쓴다.

```bash
while true; do curl -s -o /dev/null -w "%{http_code} " https://yourtrip.cloud/swagger-ui/index.html; sleep 1; done
```

200이 아닌 응답이 **0건**이어야 하고, 총 요청 수와 측정 구간을 함께 기록한다.

### C6 — 새 바이트가 실제로 도는가

```bash
aws ssm start-session --target <instance-id>
```

```bash
md5sum /opt/app/app.jar
```

S3 객체의 ETag(단일 파트 업로드면 MD5와 같다)와 대조한다. 인스턴스의 `LaunchTime`이 `put-parameter` 시각보다 뒤인지도 함께 본다.

### C7 — 실패했을 때 서비스가 살아남는가

**실제로 일어날 법한 사고를 재현한다.** plain jar는 유효한 zip이고 매니페스트도 있지만 `Main-Class`가 없어, `java -jar`가 즉시 죽고 systemd가 5초마다 재시작하며 8080은 끝내 열리지 않는다.

```bash
./gradlew build
aws s3 cp build/libs/yourtrip-0.0.1-SNAPSHOT-plain.jar "s3://$BUCKET/app/0000000.jar"
```

그다음 `dev` 브랜치에서 워크플로를 수동 실행하고 `artifact_sha=0000000`을 넣는다.

관찰해 기록할 것:

- `describe-instance-refreshes`의 `Status` 추이와 `StatusReason` 전문
- 그동안 **기존 인스턴스가 `InService`/`healthy`를 유지하는가**
- 외부 요청 실패가 0인가
- refresh 실패 후 ASG가 불량 인스턴스를 반복 교체하는가 (`health_check_type = ELB` + `Restart=on-failure`의 합작으로 그럴 것으로 **예상**하나, 이는 관측 대상이지 단정할 사실이 아니다)

교체 루프를 얼려 두고 진단하려면:

```bash
aws autoscaling suspend-processes --auto-scaling-group-name yourtrip-prod-asg \
  --scaling-processes ReplaceUnhealthy HealthCheck
```

### C8 — 롤백

C7의 원상복구가 곧 C8이다. **실패 검증의 출구가 롤백 검증의 입구다.**

```bash
aws autoscaling resume-processes --auto-scaling-group-name yourtrip-prod-asg
gh workflow run CD --ref dev -f artifact_sha=<정상 SHA>
```

트리거부터 타깃 `healthy`까지 걸린 시간을 잰다. **기준값은 없다 — 재는 것이 목적이다.** 이 값이 `InstanceWarmup`(현재 ASG 기본값 300초 상속)을 조정할지 판단하는 근거가 된다.

끝나면 오염된 아티팩트를 지운다.

```bash
aws s3 rm "s3://$BUCKET/app/0000000.jar"
aws s3api list-object-versions --bucket "$BUCKET" --prefix app/0000000.jar
```

버저닝이 켜져 있어 삭제 마커만 생긴다. 이전 버전이 남아 있는지 확인하고, 필요하면 버전을 지정해 지운다.

### C9 — 서버가 없을 때

`terraform -chdir=terraform/prod destroy` 이후 `dev`에 아무 커밋이나 push한다.

- S3 업로드 스텝: **성공**
- SSM 갱신·refresh 스텝: **skipped**
- 워크플로: **초록불**
- `artifact_key` 값: **변하지 않음** ← 이게 핵심이다

```bash
aws ssm get-parameter --name /yourtrip/prod/artifact_key --query 'Parameter.Value' --output text
```

### C10 — 줄 수 없는 권한은 정말 없는가

이 역할은 web identity로만 맡을 수 있어 로컬에서 시험할 수 없다. **임시 검증 job**을 워크플로에 1회만 넣어 돌린 뒤, 실행 URL과 출력을 여기 남기고 **후속 커밋으로 제거한다.** 각 스텝에 `continue-on-error: true`를 붙인다.

넷 다 `AccessDenied`여야 한다.

```bash
aws s3 cp /tmp/x "s3://$BUCKET/other/x"                                    # app/ 밖 쓰기
aws ssm put-parameter --name /yourtrip/prod/env/JWT_SECRET --value x --type SecureString --overwrite
aws autoscaling update-auto-scaling-group --auto-scaling-group-name yourtrip-prod-asg --desired-capacity 2
aws ec2 describe-instances
```

## 마주친 문제

### 1. IAM 역할 `description`에 한글을 넣어 apply가 깨졌다

`aws_iam_role`을 만들 때 `CreateRole`이 거부했다.

```
ValidationError: Value at 'description' failed to satisfy constraint:
Member must satisfy regular expression pattern: [	
 -~¡-ÿ]*
```

허용 범위가 **Latin-1까지**라 한글(U+AC00~)이 들어갈 수 없다. 이 저장소는 주석·문서를 한국어로 쓰지만 **AWS 리소스의 `description` 필드는 예외**다 — 실은 `terraform/prod/iam.tf`의 서비스 연결 역할이 이미 영어로 돼 있었는데, 그게 관례인 줄 모르고 한글을 넣었다.

설명은 영어로 바꾸고 한국어 근거는 코드 주석에 남겼다. 주석에는 인코딩 제약이 없다.

**부분 적용된 상태로 멈춘다는 점도 함께 겪었다.** OIDC provider는 이미 생성됐고 역할에서 실패했는데, terraform은 성공한 것까지 state에 기록하므로 고친 뒤 다시 `plan`하면 `2 to add`(남은 것만)가 된다. 처음부터 다시 만들 필요가 없다.

### 2. refresh를 취소하자 정상 인스턴스가 종료됐다 (C7 기각의 원인)

C7 검증 중 25분 타임아웃에 걸린 워크플로가 `cancel-instance-refresh`를 호출했다. 그 직후 외부 요청이 502를 내기 시작했다.

```
05:54:43  기존 i-0b2b0c7d  InService/healthy   ← 25분간 계속 트래픽을 받고 있었다
          불량 i-02a81a2   Terminating         ← ASG 헬스체크가 계속 교체 중
05:55:18  불량 i-04b07d8   새로 InService      ← 용량이 잠시 2가 된다
05:55:49  ★ refresh Cancelled
          기존 i-0b2b0c7d  Terminating         ← 정상 인스턴스가 종료 대상이 됐다
05:55:52  502 시작 (healthy 타깃 0)
05:59:45  502 끝 — 롤백으로 뜬 새 인스턴스가 healthy
```

AWS가 남긴 종료 사유가 정확히 말해 준다.

```
Terminating EC2 instance: i-0b2b0c7dae66329d1
At 05:55:49Z an instance was taken out of service in response to a difference
between desired and actual capacity, shrinking the capacity from 2 to 1.
i-0b2b0c7dae66329d1 was selected for termination.
```

**메커니즘**: instance refresh가 도는 동안 `MaxHealthyPercentage 200` 덕에 ASG 용량이 일시적으로 2가 된다. 취소하면 그 여유가 사라지고 용량이 `desired = 1`로 돌아가는데, ASG는 줄일 인스턴스를 **기본 종료 정책**으로 고른다. 그 정책은 **건강 여부를 보지 않고** AZ 균형 → 가장 오래된 launch template 버전 → 가장 오래된 인스턴스 순으로 집는다. 25분간 서비스를 지탱하던 정상 인스턴스가 정확히 "가장 오래된" 것이었다.

**고친 방법**: 타임아웃에서 취소하지 않는다. job만 실패시키고 refresh는 그대로 둔다 — 진행 중인 교체를 놔두면 `MinHealthyPercentage 100`이 계속 기존 인스턴스를 보호한다. CD 역할에서 `autoscaling:CancelInstanceRefresh` 권한도 회수해 그 경로를 아예 닫았다.

**왜 놓쳤나**: 사전 등록한 C7은 "실패한 배포"만 상정했지 "실패한 배포를 정리하는 동작"은 상정하지 않았다. 설계 단계에서 초안에는 *"타임아웃 시 refresh를 취소하지는 않는다 — 진행 중인 교체를 중간에 끊는 것이 더 위험하다"* 고 적었는데, 구현하면서 뒷정리가 깔끔해 보인다는 이유로 취소를 넣었다. **초안의 판단이 옳았고, 실측이 그것을 되돌려 놓았다.**

### 3. Git Bash가 SSM 경로를 Windows 경로로 바꿔 버린다

Windows의 Git Bash(MSYS)에서 `/yourtrip/prod`처럼 슬래시로 시작하는 인자를 **경로로 오인해 변환**한다.

```
ValidationException: The parameter doesn't meet the parameter name requirements.
The parameter name must begin with a forward slash "/".
```

"슬래시로 시작해야 한다"는 오류가 슬래시로 시작하는 값에 나오는 것이 혼란스러운데, AWS에 도착한 값이 이미 `C:/Program Files/Git/yourtrip/prod` 형태로 바뀌어 있어서다.

```bash
MSYS_NO_PATHCONV=1 aws ssm get-parameters-by-path --path /yourtrip/prod --recursive
```

리눅스 러너에서 도는 CD 워크플로는 이 문제를 타지 않는다. **사람이 로컬에서 절차를 밟을 때만** 걸린다.

## 한계

- **수정 후 재측정을 하지 않았다.** C7을 기각시킨 취소 경로는 코드에서 제거했지만, 그 수정이 실제로 무중단을 지키는지는 재현해 보지 않았다. 재현하려면 25분 타임아웃을 다시 만들어야 하고 그동안 인프라를 띄워 둬야 한다. **지금 문서가 주장할 수 있는 것은 "원인을 규명하고 그 경로를 닫았다"까지이고, "고쳐서 통과했다"가 아니다.**
- **C5의 무중단은 인위적 부하 기준이다.** 실트래픽이 사실상 0인 환경이라 1초 간격 요청으로 쟀다. 동시 접속이 있는 상태에서의 교체는 재지 않았다.
- **교체 소요 시간은 1회 측정이다.** 정상 배포 6분 12초, 롤백 5분 15초는 각각 한 번씩 잰 값이라 분산을 모른다. `InstanceWarmup`(ASG 기본값 300초 상속)을 조정할지는 이 값만으로 판단하지 않는 편이 낫다.
- **깨진 배포를 실패로 판정하는 데 25분이 걸린다.** 폴링 타임아웃이 그렇게 잡혀 있어서인데, 그동안 ASG는 불량 인스턴스를 6분 간격으로 계속 띄우고 죽인다. 서비스는 안전하지만 그 시간과 과금은 낭비다. 헬스체크 실패를 더 빨리 포착해 끊을 방법은 검토하지 않았다.
- **아티팩트가 3개 쌓였다.** `3146ef0`, `809cb73`, `5a5d515`에 검증용 `0000000`까지 넷이 올라갔고(검증 후 `0000000`은 삭제), 버킷에 수명주기 규칙이 없어 앞으로도 머지마다 113MB씩 쌓인다.

## 참고 문서

| 문서 | 이 문서에서 쓴 내용 |
|---|---|
| [README.md](README.md) | 사전 등록한 판정 기준 C1~C10과 그 근거 |
| [docs/guide/cd.md](../../guide/cd.md) | 배포·롤백 실행 절차 |
| [terraform/prod/README.md](../../../terraform/prod/README.md) | apply·destroy 절차, 교체 루프를 얼리는 명령 |
| [docs/tasks/prod-infra-iac/verification.md](../prod-infra-iac/verification.md) | 같은 형식의 직전 검증 기록 |
