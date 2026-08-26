# terraform/prod — 운영 서버 (온디맨드)

운영 인프라 중 **데모·측정 때만 띄우고 끝나면 destroy하는 것**을 담는 루트 모듈이다. 도메인·인증서·배포 아티팩트는 수명이 달라 [../prod-permanent/](../prod-permanent/README.md)에 따로 있고, 그쪽은 destroy 대상이 아니다.

> ⚠️ **이 모듈만 destroy한다.** `terraform -chdir=terraform/prod destroy`. `prod-permanent`까지 지우면 도메인 위임부터 다시 해야 한다.

| 담는 것 | 비고 |
|---|---|
| VPC·서브넷 2개·IGW·라우팅 | 전용 `10.43.0.0/16` (loadtest의 10.42와 비충돌) |
| 보안그룹 4종 | alb / app / rds / elasticache — 규칙은 별개 리소스로 분리 |
| SSH 키페어 | break-glass용. 평상시 접속은 SSM Session Manager |
| IAM 역할·인스턴스 프로파일 | CloudWatch Agent + SSM + (아티팩트 읽기 · 시크릿 읽기) |
| RDS PostgreSQL | db.t3.micro, 단일 AZ, 암호화 켬 |
| ElastiCache Redis | cache.t3.micro, **App과 같은 AZ 고정** |
| ALB·Launch Template·ASG·DNS 레코드 | *(다음 단계에서 추가된다)* |

## 시크릿은 어디에 있는가

**이 모듈은 앱 시크릿을 만들지도, tfvars로 받지도 않는다.** DB 비밀번호·JWT 시크릿·API 키는 SSM Parameter Store에 SecureString으로 넣어두고, 인스턴스가 부팅 시 자기 IAM 역할로 직접 받아간다.

이유는 두 가지다.

- **user_data는 인스턴스 안에서 인증 없이 읽힌다**(`http://169.254.169.254/latest/user-data`). 이 앱은 Kakao·Gemini로 아웃바운드 HTTP를 하므로 SSRF 표면이 실재하고, 운영은 `0.0.0.0/0`에 노출된다. 부하테스트 환경은 개발자 IP만 열린 일회성이라 감수할 수 있었지만 여기서는 아니다.
- **terraform 밖에 두면 `destroy`가 지우지 않는다.** 온디맨드로 다시 apply할 때 시크릿을 재입력할 필요가 없다.

`aws_ssm_parameter` **리소스로 만들면 안 된다** — 값이 tfstate에 평문으로 돌아와 지금 없애려는 문제 그 자체가 된다. 아래처럼 CLI로 1회 등록한다.

경로를 두 갈래로 나눠 쓴다. **`env/` 하위만 일괄 조회해 `.env`를 만들기 때문에** 이 분리가 필요하다 — PEM을 같은 경로에 두면 여러 줄 개행이 `.env`를 깨뜨린다.

| 경로 | 용도 |
|---|---|
| `/yourtrip/prod/env/<KEY>` | `.env`에 `KEY=VALUE` 한 줄로 들어갈 값 |
| `/yourtrip/prod/cloudfront_private_key` | 파일(`/opt/app/cloudfront_private_key.pem`)로 떨어져야 하는 PEM |
| `/yourtrip/prod/artifact_key` | 인스턴스가 내려받을 JAR의 S3 키. **비밀이 아니라 `String`이다** |

```bash
export AWS_PROFILE=terraform-admin

for k in DB_PASSWORD JWT_SECRET MAIL_EMAIL MAIL_PASSWORD KAKAO_API_KEY \
         AWS_ACCESS_KEY AWS_SECRET_KEY GEMINI_API_KEY; do
  read -rsp "$k: " v && echo
  aws ssm put-parameter --name "/yourtrip/prod/env/$k" --type SecureString --value "$v" --overwrite
done

# CloudFront 서명 개인키(PEM 파일 전체). file:// 로 넘겨야 개행이 보존된다.
aws ssm put-parameter --name /yourtrip/prod/cloudfront_private_key \
  --type SecureString --value "file://../cloudfront_private_key.pem" --overwrite

# 배포할 JAR의 S3 키. 이후로는 CD가 배포마다 갱신하므로 사람이 만지는 것은 이 1회뿐이다.
# 비밀이 아니므로 SecureString이 아니고, env/ 하위도 아니다 — 그 아래 두면 user-data가
# 일괄 조회해 .env에 넣어버려 앱 환경변수를 오염시킨다.
aws ssm put-parameter --name /yourtrip/prod/artifact_key \
  --type String --value "app/<short-sha>.jar" --overwrite
```

> **Windows Git Bash에서는 `MSYS_NO_PATHCONV=1`을 앞에 붙인다.** 그러지 않으면 `/yourtrip/prod/...`가 Windows 경로로 변환돼, "이름은 슬래시로 시작해야 한다"는 엉뚱한 `ValidationException`이 난다.

등록 확인 (값은 출력하지 않는다):

```bash
aws ssm get-parameters-by-path --path /yourtrip/prod --recursive --query 'Parameters[].Name' --output text
```

`env/` 아래 8개와 `cloudfront_private_key`, `artifact_key` 각 1개, 합쳐 10개가 나와야 한다.

> **`artifact_key`는 terraform이 관리하지 않는다** — 시크릿과 같은 이유이면서 하나가 더 있다.
> `destroy`가 지우지 않아야, 서버를 내렸다가 다시 올렸을 때 **마지막으로 배포된 JAR로 그대로
> 뜬다.** terraform이 들고 있으면 재apply 때 tfvars에 적힌 옛 SHA로 되돌아가 CD가 내보낸
> 최신본을 잃는다. 대신 `terraform/prod`가 이 값을 `data`로 읽으므로, 등록하지 않으면
> **plan이 그 자리에서 실패한다**(조용한 부팅 실패로 넘어가지 않는다).

`S3_BUCKET`·`CLOUDFRONT_*` 같은 비밀 아닌 값은 SSM이 아니라 tfvars를 거쳐 user_data에 직접 렌더링된다 — 노출돼도 무해하고, terraform이 이미 아는 값이기 때문이다.

## 사전 준비

1. **`prod-permanent`가 먼저 apply돼 있어야 한다.** 인증서와 아티팩트 버킷이 필요하다.
2. **SSM 파라미터를 등록한다** (위 절차).
3. **SSH 키페어를 만든다.** 개인키는 terraform에 넣지 않는다.
   ```bash
   ssh-keygen -t ed25519 -f ./yourtrip-prod-ssh -C yourtrip-prod
   ```
4. **`terraform.tfvars`를 만든다.**
   ```bash
   cp terraform.tfvars.example terraform.tfvars
   ```
   - `my_ip_cidr`: `curl -s ifconfig.me` 결과에 `/32`를 붙인다
   - `artifact_bucket_name`: `terraform -chdir=../prod-permanent output -raw artifact_bucket_name`

## 실행

이 저장소의 관례대로 **plan을 파일로 저장해 확인한 뒤 apply**한다.

```bash
export AWS_PROFILE=terraform-admin
terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

## 철거

```bash
terraform destroy
```

데이터를 남겨야 하는 날에만 최종 스냅샷 접미사를 넘긴다. 기본은 스냅샷 없이 빠르게 지운다.

```bash
terraform destroy -var 'rds_final_snapshot_suffix=2026-08-25-demo'
```

**철거 후 고아가 없는지 반드시 확인한다.** ALB는 남아 있기만 해도 시간당 과금이 계속된다(월 환산 약 $16~18).

```bash
aws elbv2 describe-load-balancers --query 'LoadBalancers[].LoadBalancerName'
aws rds describe-db-instances --query 'DBInstances[].DBInstanceIdentifier'
aws elasticache describe-cache-clusters --query 'CacheClusters[].CacheClusterId'
aws autoscaling describe-auto-scaling-groups --query 'AutoScalingGroups[].AutoScalingGroupName'
aws ec2 describe-instances --filters "Name=tag:Project,Values=yourtrip" \
  "Name=instance-state-name,Values=running,stopped" --query 'Reservations[].Instances[].InstanceId'
```

## 트러블슈팅

### apply 중 네트워크가 끊기면 멀쩡한 RDS가 tainted로 표시된다

**증상**: apply가 아래처럼 실패한다.

```
Error: waiting for RDS DB Instance (...) create: ... dial tcp: lookup rds.ap-northeast-2.amazonaws.com: no such host
Error: waiting for ElastiCache Cache Cluster (...) create: ... UnknownError
```

**무슨 일이 벌어진 것인가**: 생성 요청은 이미 AWS에 도달했고 리소스는 정상적으로 만들어지는 중이다. terraform이 **완료를 확인하지 못했을 뿐**인데, 그 경우 해당 리소스를 `tainted`로 표시한다.

**그대로 다시 apply하면 안 된다.** plan이 이렇게 나온다.

```
# aws_db_instance.this is tainted, so must be replaced
Plan: 5 to add, 0 to change, 2 to destroy.
```

정상 동작 중인 DB를 지우고 다시 만든다는 뜻이다. RDS 재생성은 5분 이상 걸리고, 데이터가 있었다면 함께 사라진다.

**대응**: 실제 상태를 먼저 확인한다.

```bash
aws rds describe-db-instances --query "DBInstances[].{ID:DBInstanceIdentifier,Status:DBInstanceStatus}" --output table
aws elasticache describe-cache-clusters --query "CacheClusters[].{ID:CacheClusterId,Status:CacheClusterStatus}" --output table
```

`available`이면 리소스는 멀쩡하므로 taint만 해제한다.

```bash
cp terraform.tfstate terraform.tfstate.pre-untaint-bak
terraform untaint aws_db_instance.this
terraform untaint aws_elasticache_cluster.this
terraform plan -out=tfplan        # destroy가 0인지 반드시 확인
```

실제로 `creating` 상태로 멈춰 있거나 `failed`라면 그때는 taint가 맞으므로 그대로 재생성한다.

### ASG가 인스턴스를 띄우지 못하고 계속 교체한다

타깃이 `unhealthy` → 인스턴스 교체 → 다시 `unhealthy`가 반복되면 앱이 기동하지 못하는 것이다. 먼저 **자동 교체를 멈춰야** 로그를 볼 시간이 생긴다.

```bash
aws autoscaling suspend-processes --auto-scaling-group-name yourtrip-prod-asg \
  --scaling-processes ReplaceUnhealthy HealthCheck
```

이건 형상이 아니라 실행 상태만 바꾸는 조작이라 drift가 아니다. 진단이 끝나면 반드시 되돌린다.

```bash
aws autoscaling resume-processes --auto-scaling-group-name yourtrip-prod-asg
```

진단은 SSM으로 인스턴스에 들어가지 않고도 할 수 있다.

```bash
aws ssm send-command --instance-ids <id> --document-name AWS-RunShellScript \
  --parameters 'commands=["grep -oE \"^[A-Z_]+\" /opt/app/.env | sort | tr \"\\n\" \" \"","journalctl -u yourtrip-app -n 40 --no-pager"]'
```

`.env`에 시크릿 8개가 있는지부터 본다. 없으면 user-data의 SSM 조회 구간이 실패한 것이다.

### `artifact_key`가 없어서 plan·destroy가 막힌다

`asg.tf`의 `data "aws_ssm_parameter" "artifact_key"`는 **읽기만 하는데도 plan을 막는다.** 등록을
안 했다면 그게 의도한 동작이다(사전 준비 참고) — 등록하면 풀린다.

문제는 **파라미터를 지운 뒤 `destroy`를 하려는 경우**다. 만들 리소스가 없는데도 data 읽기가
먼저 실패해 철거가 진행되지 않는다. 이때는 state에서 data를 떼어내고 다시 시도한다.

```bash
terraform state rm data.aws_ssm_parameter.artifact_key
```

data 소스라 state에서 빼도 실제 파라미터에는 아무 영향이 없고, 다음 apply 때 다시 읽는다.

## 알아둬야 할 것

- **AZ가 셋 다 같아야 한다.** App EC2·RDS·ElastiCache를 `availability_zone_primary` 하나로 고정한다. 부하테스트 환경에서 ElastiCache만 다른 AZ에 떨어져 Redis 명령 지연 바닥값이 0.2~0.4ms에서 **1.2ms로 굳은 사고**가 있었다(`elasticache.tf` 주석, 커밋 `7cbef86`). 그 대가로 앱은 단일 AZ에 묶인다 — ALB만 2 AZ에 걸쳐 있다.
- **`storage_encrypted`는 생성 후 바꿀 수 없다.** 최초 apply 전에 결정해야 하는 값이라 처음부터 `true`로 둔다.
- **`enable_dev_direct_access`를 켜두지 않는다.** 개발자 IP에서 ALB를 우회해 8080으로 붙을 수 있게 되는데, 그 경로로는 `/actuator` 차단 리스너 규칙을 지나치므로 "차단이 걸려 있다"는 착각을 만든다. 디버깅이 끝나면 끈다.
- **인프라 변경은 반드시 terraform을 거친다.** state가 S3 원격 backend로 갔어도(#157) 이 규칙은 그대로다 — 콘솔·CLI로 형상을 바꾸면 어디에 있든 state에 기록되지 않아 drift가 된다. 실제 사고 사례와 복구 절차는 [../loadtest/README.md](../loadtest/README.md)의 "인프라 변경은 반드시 terraform을 거친다" 절에 있다. 인스턴스 start/stop처럼 **실행 상태만 바꾸는 조작**은 CLI로 해도 된다.
- `terraform.tfstate`·`terraform.tfvars`·SSH 키페어는 `.gitignore` 대상이며, worktree에서 작업했다면 [CLAUDE.md](../../CLAUDE.md)의 worktree 규칙에 따라 메인 워킹트리 사본도 갱신해야 한다.
