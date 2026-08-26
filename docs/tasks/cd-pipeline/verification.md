# CD 파이프라인 검증 — 아직 측정하지 않았다

> ⚠️ **이 문서는 측정 전이다.** 아래는 [README.md](README.md)에서 사전 등록한 판정 기준 C1~C10을 **어떻게 확인할 것인지**를 적은 실행 계획이고, 결과 칸은 비어 있다. 측정이 끝나면 이 경고 blockquote를 지우고 실측값으로 채운다.
>
> 검증에는 **운영 인프라를 실제로 올려야 한다**(`terraform/prod` apply). 온디맨드 모델이라 평시에는 내려가 있고 올리는 동안 비용이 발생하므로, 아래 순서를 지켜 **서버를 한 번만 띄우고 끝낸다.**

## 측정 환경

| | |
|---|---|
| 측정일 | *(미기재)* |
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

> 9단계는 선택이다. "destroy했다가 다시 올리면 마지막 배포본으로 뜬다"는 이 설계의 주장을 실증하는 단계인데, 확인하려면 서버를 한 번 더 띄워야 한다. 비용을 감수할 때만 한다.

## 판정 결과

| # | 항목 | 결과 | 근거 |
|---|---|---|---|
| C1 | OIDC 인증 성립 | *(미측정)* | |
| C2 | 저장된 시크릿 0개 | *(미측정)* | |
| C3 | 아티팩트 규약 | *(미측정)* | |
| C4 | LT 불변 / drift 0 | *(미측정)* | |
| C5 | 무중단 | *(미측정)* | |
| C6 | 새 버전 실적용 | *(미측정)* | |
| C7 | 실패 시 자동 중단 | *(미측정)* | |
| C8 | 롤백 | *(미측정)* | |
| C9 | ASG 부재 시 스킵 | *(미측정)* | |
| C10 | 최소 권한 실증 | *(미측정)* | |

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

*(측정 후 기록한다.)*

## 한계

*(측정 후 기록한다. 설계 시점의 한계는 [README.md](README.md)의 "한계" 절에 있다.)*

## 참고 문서

| 문서 | 이 문서에서 쓴 내용 |
|---|---|
| [README.md](README.md) | 사전 등록한 판정 기준 C1~C10과 그 근거 |
| [docs/guide/cd.md](../../guide/cd.md) | 배포·롤백 실행 절차 |
| [terraform/prod/README.md](../../../terraform/prod/README.md) | apply·destroy 절차, 교체 루프를 얼리는 명령 |
| [docs/tasks/prod-infra-iac/verification.md](../prod-infra-iac/verification.md) | 같은 형식의 직전 검증 기록 |
