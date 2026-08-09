# Terraform — EC2 + RDS + ElastiCache 분리 부하테스트 환경

이 디렉토리는 [`docs/tasks/TASK-PRESIGN-BOTTLENECK-FIX.md`](../../docs/tasks/TASK-PRESIGN-BOTTLENECK-FIX.md)의
"개선 제안 — 배포 환경(EC2 + RDS) 분리 부하테스트" 절을 실행하기 위한 **일회성·임시 인프라**를 관리한다.
로컬 개발 노트북(12코어) 한 대에서 앱·DB·Redis·k6를 전부 같이 돌리며 생긴 CPU 경합 노이즈를 제거하고,
`hikaricp_connections_pending`/`acquire_seconds` 병목이 진짜 구조적 문제인지 환경 잡음이었는지를 확정하는 게 목적이다.

**기존 [`terraform/`](../README.md)(S3·CloudFront·IAM, 영구 인프라)와는 완전히 분리된 별도 state를 쓴다.**
이 인프라는 측정 후 바로 `destroy`하는 용도라, 영구 인프라와 state를 공유하면 실수로 함께 날아갈 위험이 있다.

## 아키텍처 요약

| 리소스 | 스펙 | 비고 |
|---|---|---|
| VPC (전용) | `10.42.0.0/16` | 계정 기본 VPC 유무에 의존하지 않음. public subnet 2개(AZ 요구조건 충족용), 실제 배치는 전부 1개 AZ로 고정 |
| App EC2 | `t3.micro` | 실제 배포 타겟과 동일 스펙. 앱(JVM) 단독 실행 |
| k6 EC2 | `t3.micro` | 부하 생성 전용. Redis를 ElastiCache로 분리해 경합 상대 없음 |
| RDS PostgreSQL | `db.t3.micro` | 단일 AZ, 로컬 시드 규모(course 6,000행 등)에 여유 |
| ElastiCache Redis | `cache.t3.micro` | 단일 노드. Docker Redis(`maxmemory 256mb`, `allkeys-lru`)의 관리형 대체 |

Prometheus·Grafana는 **EC2에 올리지 않는다** — 기존 로컬 `docker-compose.yml`을 그대로 쓰되 스크레이프 대상만 App EC2로 바꾼다(아래 5번).

## 사전 준비

### 1. SSH 키페어 생성

```bash
ssh-keygen -t ed25519 -f ./yourtrip-loadtest-ssh -C yourtrip-loadtest
```

CloudFront 서명용 키페어와는 무관한, 이번 EC2 SSH 접속 전용 키다. `.pem`/`.pub` 모두 `.gitignore`로 보호된다.

### 2. 기존 `terraform/` output 값 확인

App EC2가 실행하는 애플리케이션은 S3/CloudFront에 접근해야 하므로, 기존 영구 인프라의 output을 재사용한다(이 모듈이 S3/CloudFront를 새로 만들지 않는다):

```bash
terraform -chdir=../ output -raw s3_bucket_name
terraform -chdir=../ output -raw iam_user_access_key_id
terraform -chdir=../ output -raw iam_user_secret_access_key
terraform -chdir=../ output -raw cloudfront_domain_name
terraform -chdir=../ output -raw cloudfront_key_pair_id
terraform -chdir=../ output -raw cloudfront_distribution_id
```

### 3. `terraform.tfvars` 작성

```bash
cp terraform.tfvars.example terraform.tfvars
curl -s ifconfig.me   # my_ip_cidr에 /32 붙여서 채우기
```

`terraform.tfvars.example`의 주석을 따라 나머지 값(2번에서 확인한 값들, `rds_password`, `jwt_secret` 등)을 채운다.

### 4. 로컬 PostgreSQL 버전 확인

```bash
psql --version
```

`terraform.tfvars`의 `rds_engine_version`을 이 버전(major)에 맞춘다 — 로컬 대비 배율 비교의 기준을 맞추기 위함이다.

### 5. AWS 프리티어 잔여량 확인

AWS 콘솔 → Billing → Free Tier에서 EC2(t3.micro)/RDS(db.t3.micro)/ElastiCache(cache.t3.micro) 프리티어 자격이 남아있는지 확인한다. (`enable_detailed_monitoring`은 프리티어 대상이 아니라 소액 과금이 있다 — 몇 시간짜리 테스트라면 센트 단위.)

## 실행 순서

### 1. apply

```bash
terraform init
terraform plan -out=tfplan
terraform apply tfplan
```

```bash
terraform output -raw app_public_ip
terraform output -raw k6_public_ip
terraform output -raw rds_endpoint
```

### 2. CloudFront 개인키 전달

앱이 mycourse Signed URL을 서명하려면 개인키가 필요하다. Terraform state에는 절대 넣지 않으므로(기존 `terraform/README.md`와 동일 원칙) 수동으로 옮긴다:

```bash
scp -i ./yourtrip-loadtest-ssh ../cloudfront_private_key.pem \
  ec2-user@$(terraform output -raw app_public_ip):/opt/app/cloudfront_private_key.pem

ssh -i ./yourtrip-loadtest-ssh ec2-user@$(terraform output -raw app_public_ip) \
  'sudo systemctl restart yourtrip-app.service'
```

### 3. 앱 기동 확인

```bash
curl -sf http://$(terraform output -raw app_public_ip):8080/actuator/health
```

`{"status":"UP"}`가 나올 때까지 대기한다(`user_data`의 Gradle 빌드가 완료되는 데 몇 분 걸릴 수 있다 — `ssh`로 접속해 `journalctl -u yourtrip-app -f`로 진행 상황을 볼 수 있다).

### 4. DB 시딩

**RDS는 `publicly_accessible = false`라 로컬에서 직접 접속할 수 없다** — `allow_dev_psql_access`로 보안그룹을 열어도, 엔드포인트 DNS 자체가 VPC 사설 IP(예: `10.42.1.x`)로만 resolve되기 때문에 인터넷에서 그 IP로 가는 경로가 없다(실측으로 확인됨). 반드시 같은 VPC 안의 App EC2를 경유한다:

```bash
# 1. 시드 스크립트를 App EC2로 전달
scp -i ./yourtrip-loadtest-ssh ../../scripts/sql/seed-benchmark.sql \
  ec2-user@$(terraform output -raw app_public_ip):/tmp/seed-benchmark.sql

# 2. App EC2에 psql 클라이언트 설치 (최초 1회)
ssh -i ./yourtrip-loadtest-ssh ec2-user@$(terraform output -raw app_public_ip) \
  'sudo dnf install -y postgresql16'

# 3. App EC2에서 RDS로 시딩 실행 (같은 VPC라 sg-rds가 sg-app을 허용)
ssh -i ./yourtrip-loadtest-ssh ec2-user@$(terraform output -raw app_public_ip) \
  "PGPASSWORD='<rds_password>' psql -h $(terraform output -raw rds_endpoint) -U postgres -d yourtrip -f /tmp/seed-benchmark.sql"
```

### 5. 로컬 Prometheus/Grafana를 원격 App EC2로 재조준

`prometheus.yml`(레포 루트)의 스크레이프 대상을 바꾼다:

```diff
- targets: ['host.docker.internal:8080']
+ targets: ['<terraform output -raw app_public_ip>:8080']
```

```bash
docker compose up -d prometheus grafana
```

Grafana(`localhost:3000`)의 Dashboards → Bottleneck Test → Presign CPU Bottleneck에서 그대로 확인 가능하다.

### 6. k6 부하테스트 실행

```bash
ssh -i ./yourtrip-loadtest-ssh ec2-user@$(terraform output -raw k6_public_ip)
```

접속 후:

```bash
cd /opt/app
k6 run -e BASE_URL=http://<App EC2 공인 IP>:8080 -e DOMAIN=uploadcourse -e MODE=pool scripts/k6/detail-ramping.js
k6 run -e BASE_URL=http://<App EC2 공인 IP>:8080 -e DOMAIN=mycourse -e MODE=pool scripts/k6/detail-ramping.js
```

### 7. 지표 수집

- Prometheus range query: `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds_max`, `hikaricp_connections_active`
- CloudWatch: App EC2 `mem_used_percent`(커스텀 네임스페이스 `YourtripLoadtest`), App EC2/k6 EC2/RDS/ElastiCache `CPUCreditBalance`, `CPUUtilization`

로컬 실측치(포화 시작 VU~20, `acquire_seconds` 최대 2.7초)와 비교한다.

### 8. 원상복구 및 철거

```bash
# 저장소 루트에서
git checkout -- prometheus.yml

# 이 디렉토리에서
terraform destroy
```

## 알아둬야 할 것

- **`rds_password`, `jwt_secret` 등 민감값은 `terraform.tfstate`에 평문으로 저장된다.** 기존 `terraform/`과 동일한 트레이드오프이며(로컬 state, remote backend 없음), 테스트 종료 후 `terraform destroy`로 리소스 자체를 없애는 것이 최선의 완화책이다.
- **App EC2 사용자 데이터가 빌드 중 임시 스왑(1GB)을 켰다가 끈다.** 부하테스트를 시작하기 전 스왑이 꺼져 있는지 `free -h`로 확인하는 걸 권장한다 — 켜진 채로 측정하면 "1GB로 버티는가"라는 핵심 질문이 디스크 I/O 지연으로 왜곡된다.
- **`DB_DDL_AUTO=create`를 쓴다** — 이 RDS는 매 측정 전 재시딩하는 테스트 전용 DB라, 로컬 벤치마크와 동일하게 스키마를 앱이 직접 생성하게 둔다. 운영 DB(`validate`)와는 다른 설정임을 인지하고 있을 것.
- **버스터블(t3) 인스턴스 4개 전부 CPU 크레딧 고갈 가능성이 있다.** `CPUCreditBalance`가 0에 가까워지면 "로컬 CPU 경합"이 "AWS 크레딧 고갈"이라는 같은 종류의 새로운 병목으로 바뀐 것뿐이므로, 이 경우 측정 신뢰도를 재평가해야 한다.
