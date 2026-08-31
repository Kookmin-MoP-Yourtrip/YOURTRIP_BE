# CD 가이드

`dev`에 머지하면 JAR이 빌드돼 S3에 올라가고, 운영 서버가 떠 있으면 무중단으로 교체되는 파이프라인([.github/workflows/deploy.yml](../../.github/workflows/deploy.yml))에 대한 문서다. 설계 근거와 실측은 [docs/tasks/cd-pipeline/](../tasks/cd-pipeline/README.md)에 있다.

## 1. 무슨 일이 일어나는가

```
dev 머지
   │
   ├─ build   : gradlew build → boot jar 식별 → 아티팩트로 전달
   └─ release : S3 업로드 → ASG 있나? → SSM 갱신 → instance refresh → 헬스 확인
                              └─ 없으면 업로드까지만 하고 초록불
```

핵심은 **어떤 JAR을 쓸지가 SSM 파라미터 하나에 적혀 있다**는 것이다.

| | 값 |
|---|---|
| 파라미터 | `/yourtrip/prod/artifact_key` |
| 형식 | `app/<커밋 7자리 SHA>.jar` |
| 읽는 시점 | 인스턴스가 **부팅할 때마다** |
| 쓰는 주체 | CD 워크플로(최초 1회는 사람) |

인스턴스는 뜰 때 이 값을 읽어 그 JAR을 내려받는다. 그래서 배포란 **파라미터를 바꾸고 인스턴스를 새로 띄우는 일**이고, 롤백은 **파라미터를 되돌리고 다시 띄우는 일**이다. 둘은 같은 절차다.

> **Launch Template은 배포로 바뀌지 않는다.** JAR 버전이 거기 박혀 있지 않기 때문이다. 배포 후 `terraform plan`을 돌려도 인프라 변경은 뜨지 않는다 — `Changes to Outputs`에 `current_artifact_key`만 보이면 정상이다.

## 2. 최초 셋업 (1회)

### 2-1. terraform apply

```bash
export AWS_PROFILE=terraform-admin
terraform -chdir=terraform/prod-permanent plan -out=tfplan
terraform -chdir=terraform/prod-permanent apply tfplan
```

OIDC provider와 CD 역할이 생긴다. 자세한 내용은 [terraform/prod-permanent/README.md](../../terraform/prod-permanent/README.md).

### 2-2. SSM 파라미터 등록

```bash
aws ssm put-parameter --name /yourtrip/prod/artifact_key \
  --type String --value "app/<short-sha>.jar" --overwrite
```

**등록하지 않으면 `terraform -chdir=terraform/prod plan`이 실패한다.** 의도한 동작이다 — 빠뜨린 채 apply하면 인스턴스는 정상적으로 뜨고 user-data만 조용히 실패해, 원인을 찾기 어려워진다.

### 2-3. GitHub 저장소 변수

```bash
gh variable set AWS_ROLE_ARN --body "$(terraform -chdir=terraform/prod-permanent output -raw github_actions_role_arn)"
gh variable set ARTIFACT_BUCKET --body "$(terraform -chdir=terraform/prod-permanent output -raw artifact_bucket_name)"
```

`secret`이 아니라 `variable`이다. 둘 다 비밀이 아니고, **이 워크플로에 저장된 시크릿이 0개**라는 사실 자체가 설계의 일부다.

```bash
gh secret list
```

AWS 관련 항목이 하나도 없어야 한다.

## 3. 배포한다

`dev`에 머지하면 끝이다. 별도 조작이 없다.

```bash
gh run watch
```

## 4. 롤백한다 — 이전 SHA를 다시 배포한다

되돌리기는 별도 기능이 아니라 **같은 파이프라인을 이전 SHA로 한 번 더 태우는 것**이다.

```bash
gh workflow run CD --ref dev -f artifact_sha=<되돌릴 7자리 SHA>
```

> ⚠️ **반드시 `--ref dev`로 실행한다.** CD 역할의 신뢰 정책이 `dev` 브랜치로 못박혀 있는데, `workflow_dispatch`의 `sub` 클레임은 이벤트 종류가 아니라 **선택한 ref**로 결정된다. 다른 브랜치를 고르면 AWS 인증 단계에서 거부된다.

되돌릴 SHA는 실패한 실행의 job summary에 **"직전 아티팩트"** 로 적혀 있다. 없으면 S3에서 찾는다.

```bash
aws s3 ls "s3://$(terraform -chdir=terraform/prod-permanent output -raw artifact_bucket_name)/app/"
```

지금 무엇이 배포돼 있는지는 두 곳에서 확인할 수 있다.

```bash
aws ssm get-parameter --name /yourtrip/prod/artifact_key --query 'Parameter.Value' --output text
```

```bash
terraform -chdir=terraform/prod output -raw current_artifact_key
```

> **자동 롤백은 없다.** Instance Refresh의 `AutoRollback`은 이전 Launch Template 버전으로 되돌리는 기능인데, 이 구성에서 LT는 배포로 바뀌지 않으므로 되돌릴 대상이 없다. 되돌아가도 새 인스턴스는 SSM에서 같은 키를 읽는다. 대신 **실패한 배포는 서비스에 닿지 않는다** — 아래 6절.

## 5. 서버가 내려가 있을 때

운영은 온디맨드라 대부분의 기간 ASG가 없다. 그때 dev에 머지하면:

- JAR은 S3에 **올라간다**
- 교체 스텝은 **건너뛴다**
- 워크플로는 **초록불**로 끝난다
- `artifact_key`는 **건드리지 않는다**

마지막 항목이 중요하다. 그래야 나중에 `terraform apply`로 서버를 올렸을 때 **마지막으로 실제 배포됐던 버전**이 뜬다.

서버를 올린 뒤 그동안 쌓인 최신 커밋을 배포하려면, 4절과 같은 방식으로 그 SHA를 지정해 수동 실행한다.

## 6. 실패했을 때

### 어느 스텝에서 죽었는지부터 본다

| 죽은 스텝 | 의미 | 대응 |
|---|---|---|
| 저장소 변수 확인 | `AWS_ROLE_ARN`/`ARTIFACT_BUCKET` 미설정 | 2-3 |
| AWS 자격증명 | 신뢰 정책 불일치 | 브랜치가 `dev`인지 확인(4절 경고). 저장소 이름이 바뀌었다면 `github_repository` 변수 |
| Boot JAR 식별 | `build/libs`에 boot jar가 없거나 plain jar만 있다 | 빌드 로그를 본다. `BOOT-INF/` 검사에 걸렸다면 애초에 배포되면 안 되는 산출물이다 |
| 진행 중인 refresh 확인 | 사람이 이미 교체를 돌리고 있다 | 끝난 뒤 다시 실행 |
| 교체 완료까지 대기 | **새 인스턴스가 healthy가 되지 못했다** | 아래 |
| 타깃 헬스 확인 | 교체는 됐는데 타깃이 정상이 아니다 | 아래 |

### 교체가 실패했다면 — 서비스는 살아 있다

`min_healthy 100`이라 **새 인스턴스가 healthy가 되기 전에는 기존 것을 죽이지 않는다.** 즉 배포가 실패해도 이전 버전이 계속 서비스한다. 급하지 않다.

> ⚠️ **진행 중인 instance refresh를 취소하지 않는다.** 취소하면 교체를 위해 늘어나 있던 용량이 원래대로 줄어드는데, ASG는 줄일 대상을 **건강 여부와 무관하게** 가장 오래된 인스턴스로 고른다. 그게 유일하게 살아 있던 정상 인스턴스일 수 있다 — 실제로 그래서 **3분 53초 서비스가 끊긴 적이 있다**([verification.md](../tasks/cd-pipeline/verification.md)의 "마주친 문제" 2번). 워크플로도 타임아웃 때 취소하지 않고 job만 실패시킨다. 정리하려면 **취소가 아니라 롤백**(4절)을 쓴다 — 정상 SHA로 새 refresh를 걸면 이전 것은 자연히 대체된다.

원인을 본다.

```bash
aws autoscaling describe-instance-refreshes --auto-scaling-group-name yourtrip-prod-asg \
  --query 'InstanceRefreshes[0].[Status,StatusReason]' --output text
```

```bash
aws elbv2 describe-target-health \
  --target-group-arn "$(terraform -chdir=terraform/prod output -raw target_group_arn)" \
  --query 'TargetHealthDescriptions[].[Target.Id,TargetHealth.State,TargetHealth.Reason]' --output table
```

새 인스턴스 안을 본다. ALB가 `/actuator/*`를 403으로 막고 있어 **외부에서는 확인할 수 없다.**

```bash
aws ssm start-session --target <instance-id>
```

```bash
sudo journalctl -u yourtrip-app -n 100 --no-pager
```

```bash
sudo tail -50 /var/log/cloud-init-output.log
```

JAR을 못 받았는지, 받았는데 기동에 실패했는지가 여기서 갈린다.

그다음 4절로 되돌린다.

### 교체 루프가 도는 것 같으면

`health_check_type = "ELB"`라 ASG가 불량 인스턴스를 계속 교체할 수 있다. 진단 중에는 얼려 둔다.

```bash
aws autoscaling suspend-processes --auto-scaling-group-name yourtrip-prod-asg \
  --scaling-processes ReplaceUnhealthy HealthCheck
```

```bash
aws autoscaling resume-processes --auto-scaling-group-name yourtrip-prod-asg
```

인스턴스 실행 상태만 바꾸는 조작이라 terraform drift가 되지 않는다.

## 7. 워크플로가 확인하는 것 / 사람이 확인하는 것

| 항목 | 누가 | 방법 |
|---|---|---|
| refresh 성공 | 워크플로 | `describe-instance-refreshes` 폴링 |
| 타깃 healthy | 워크플로 | `describe-target-health` |
| 프로필이 `prod`인가 | **사람** | [profile.md](profile.md) §4-2 |
| JVM 옵션이 적용됐는가 | **사람** | [deploy/prod/README.md](../../deploy/prod/README.md)의 확인 명령 |

뒤 두 개를 워크플로에 넣지 않은 데는 두 가지 이유가 있다.

첫째, **user-data가 결정론적으로 넣는 값**이라 배포마다 달라지지 않는다. 값이 틀렸다면 그건 배포 문제가 아니라 템플릿 문제이고, 템플릿을 고칠 때 확인하면 된다.

둘째, 확인하려면 `ssm:SendCommand`가 필요한데 그건 **운영 인스턴스의 root 원격 실행 권한**이다. 배포 파이프라인이 그걸 가지면 워크플로를 수정할 수 있는 권한이 곧 운영 서버 셸 권한이 된다. 이 확인의 가치보다 권한의 위험이 크다.

user-data나 `deploy/prod/`의 파일을 고쳤을 때는 사람이 직접 본다.

```bash
sudo journalctl -u yourtrip-app -n 200 --no-pager | grep -i profile
```

```bash
tr '\0' ' ' < /proc/$(systemctl show -p MainPID --value yourtrip-app)/cmdline; echo
```

## 8. CD가 할 수 있는 것과 없는 것

| 할 수 있다 | 할 수 없다 |
|---|---|
| 아티팩트 버킷 `app/` 아래에 쓰기 | 그 밖의 경로·버킷에 쓰기, 삭제 |
| `artifact_key` 파라미터 읽기·쓰기 | 시크릿(`/yourtrip/prod/env/*`) 접근 |
| instance refresh 시작·조회 | ASG·Launch Template 형상 변경, **진행 중인 refresh 취소** |
| 타깃 헬스 조회 | 인스턴스 원격 명령 실행(`ssm:SendCommand`) |

**인프라의 모양은 terraform만 바꾼다**([CLAUDE.md](../../CLAUDE.md))는 규칙을 IAM으로 강제한 결과다. 배포 파이프라인이 인프라를 바꿀 수 있으면 state가 모르는 변경이 생긴다.

## 9. 알아둘 한계

- **CD는 서버를 올리지 않는다.** ASG가 없으면 업로드까지만 한다. 기동은 사람이 `terraform apply`로 한다 — 온디맨드 모델에서 의도한 경계다.
- **`dev` 머지마다 아티팩트가 쌓인다.** 버킷에 수명주기 규칙이 없고 버저닝까지 켜져 있어 이중으로 누적된다. 정리 정책은 아직 없다.
- **워크플로 자체는 CI가 검증하지 못한다.** YAML이나 스텝 로직 오류는 `dev`에 머지돼 실행돼야 드러난다. 워크플로를 고쳤다면 첫 실행을 지켜본다.
- **`build`가 CI와 같은 빌드를 중복 수행한다.** dev push마다 `./gradlew build`가 두 번 돈다. 배포되는 바이트가 테스트를 통과한 빌드임을 보장하기 위한 대가다.
