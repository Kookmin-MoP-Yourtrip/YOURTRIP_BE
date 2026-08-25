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

```bash
export AWS_PROFILE=terraform-admin

for k in DB_PASSWORD JWT_SECRET MAIL_EMAIL MAIL_PASSWORD KAKAO_API_KEY \
         AWS_ACCESS_KEY AWS_SECRET_KEY GEMINI_API_KEY; do
  read -rsp "$k: " v && echo
  aws ssm put-parameter --name "/yourtrip/prod/$k" --type SecureString --value "$v" --overwrite
done

# CloudFront 서명 개인키(PEM 파일 전체)
aws ssm put-parameter --name /yourtrip/prod/cloudfront_private_key \
  --type SecureString --value "file://../cloudfront_private_key.pem" --overwrite
```

등록 확인 (값은 출력하지 않는다):

```bash
aws ssm get-parameters-by-path --path /yourtrip/prod --query 'Parameters[].Name' --output text
```

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

## 알아둬야 할 것

- **AZ가 셋 다 같아야 한다.** App EC2·RDS·ElastiCache를 `availability_zone_primary` 하나로 고정한다. 부하테스트 환경에서 ElastiCache만 다른 AZ에 떨어져 Redis 명령 지연 바닥값이 0.2~0.4ms에서 **1.2ms로 굳은 사고**가 있었다(`elasticache.tf` 주석, 커밋 `7cbef86`). 그 대가로 앱은 단일 AZ에 묶인다 — ALB만 2 AZ에 걸쳐 있다.
- **`storage_encrypted`는 생성 후 바꿀 수 없다.** 최초 apply 전에 결정해야 하는 값이라 처음부터 `true`로 둔다.
- **`enable_dev_direct_access`를 켜두지 않는다.** 개발자 IP에서 ALB를 우회해 8080으로 붙을 수 있게 되는데, 그 경로로는 `/actuator` 차단 리스너 규칙을 지나치므로 "차단이 걸려 있다"는 착각을 만든다. 디버깅이 끝나면 끈다.
- **인프라 변경은 반드시 terraform을 거친다.** 원격 backend 없이 로컬 `terraform.tfstate`가 유일한 진실 공급원이라, 콘솔·CLI로 형상을 바꾸면 state에 기록되지 않아 drift가 된다. 실제 사고 사례와 복구 절차는 [../loadtest/README.md](../loadtest/README.md)의 "인프라 변경은 반드시 terraform을 거친다" 절에 있다. 인스턴스 start/stop처럼 **실행 상태만 바꾸는 조작**은 CLI로 해도 된다.
- `terraform.tfstate`·`terraform.tfvars`·SSH 키페어는 `.gitignore` 대상이며, worktree에서 작업했다면 [CLAUDE.md](../../CLAUDE.md)의 worktree 규칙에 따라 메인 워킹트리 사본도 갱신해야 한다.
