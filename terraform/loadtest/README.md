# Terraform — EC2 + RDS + ElastiCache 분리 부하테스트 환경

이 디렉토리는 [`docs/tasks/connection-pool-bottleneck/stage0/production/ec2-rds.md`](../../docs/tasks/connection-pool-bottleneck/stage0/production/ec2-rds.md)의
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

### 6. 로컬 `session-manager-plugin` 설치 확인

DB 시딩(아래 "실행 순서" 5번)은 App EC2에 아무것도 설치하지 않고 SSM Session Manager 포트포워딩으로 RDS에 직접 터널링한다 — 로컬에 AWS CLI의 SSM 플러그인이 필요하다.

```bash
session-manager-plugin --version
# 없다면: https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html
```

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

### 2. App JAR 로컬 빌드

App EC2는 빌드를 하지 않는다 — JAR는 로컬에서 미리 빌드해 scp로 전달한다(이유는 `templates/app-user-data.sh.tpl` 상단 주석과 [EC2-RDS-LOADTEST-GUIDE.md](../../docs/guide/EC2-RDS-LOADTEST-GUIDE.md) 참고 — t3.micro에서 직접 빌드하면 CPU 크레딧을 미리 갉아먹어 측정 시작 조건이 매번 달라지는 문제가 있었다).

```bash
# 저장소 루트에서, terraform.tfvars의 app_git_ref와 동일한 커밋을 체크아웃한 상태로
./gradlew bootJar -x test
```

`build/libs/*.jar`(`*-plain.jar` 제외) 하나가 나온다.

### 3. App JAR + CloudFront 개인키 전달

두 파일 다 Terraform state에는 절대 넣지 않는다(JAR는 빌드 산출물이라 애초에 대상이 아니고, 개인키는 기존 `terraform/README.md`와 동일 원칙). `scp`로 옮기면 systemd가 `Restart=on-failure`로 자동 재시도하다가 두 파일이 도착한 다음 재시도에서 스스로 기동한다(수동 restart 불필요 — 실측으로 검증됨).

**`/opt/app`은 root 소유라 `ec2-user`가 직접 쓸 수 없다**(실측으로 확인됨 — `scp: Permission denied`) — `/tmp`를 거쳐 `sudo mv`한다:

```bash
cd terraform/loadtest

scp -i ./yourtrip-loadtest-ssh ../../build/libs/yourtrip-0.0.1-SNAPSHOT.jar \
  ec2-user@$(terraform output -raw app_public_ip):/tmp/app.jar
scp -i ./yourtrip-loadtest-ssh ../cloudfront_private_key.pem \
  ec2-user@$(terraform output -raw app_public_ip):/tmp/cloudfront_private_key.pem

ssh -i ./yourtrip-loadtest-ssh ec2-user@$(terraform output -raw app_public_ip) '
  sudo mv /tmp/app.jar /opt/app/app.jar &&
  sudo mv /tmp/cloudfront_private_key.pem /opt/app/cloudfront_private_key.pem &&
  sudo chown ec2-user:ec2-user /opt/app/app.jar /opt/app/cloudfront_private_key.pem &&
  sudo chmod 600 /opt/app/cloudfront_private_key.pem
'
```

### 4. 앱 기동 확인

```bash
curl -sf http://$(terraform output -raw app_public_ip):8080/actuator/health
```

`{"status":"UP"}`가 나올 때까지 최대 수십 초 대기한다(빌드가 없으니 로컬에서 Gradle을 돌릴 때만큼만 걸린다 — EC2 자체는 그냥 JAR를 실행할 뿐이다). 안 뜨면 `ssh`로 접속해 `journalctl -u yourtrip-app -n 50`으로 원인을 확인한다.

### 5. DB 시딩 (SSM 포트포워딩 — App EC2엔 아무것도 설치하지 않음)

**RDS는 `publicly_accessible = false`라 로컬에서 직접 접속할 수 없다** — 엔드포인트 DNS 자체가 VPC 사설 IP(예: `10.42.1.x`)로만 resolve되기 때문에 인터넷에서 그 IP로 가는 경로가 없다(실측으로 확인됨). 초기 버전은 App EC2에 `postgresql` 클라이언트를 설치해 경유했으나, 이는 "측정 대상 인스턴스는 최대한 순수하게 유지한다"는 원칙(Prometheus/Grafana/Redis를 EC2 밖에 둔 것과 동일한 이유)에 어긋나 **SSM Session Manager 포트포워딩**으로 바꿨다 — App EC2를 릴레이로 삼아 로컬 5432(다른 포트로 매핑) → RDS 5432로 터널링하고, 로컬 `psql`을 그대로 쓴다. App EC2 IAM 역할에 `AmazonSSMManagedInstanceCore`가 붙어 있어야 하고(`iam.tf`에 이미 반영됨), 새로 붙인 정책이 실제로 적용되기까지 IAM 전파 지연(수십 초~수 분)이 있을 수 있다.

```bash
# 1. 터널 열기 (별도 터미널에서 계속 띄워둠 — Ctrl+C로 종료)
AWS_PROFILE=terraform-admin aws ssm start-session \
  --target $(terraform output -raw app_instance_id) \
  --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters '{"host":["'"$(terraform output -raw rds_endpoint)"'"],"portNumber":["5432"],"localPortNumber":["15432"]}'

# 2. (다른 터미널에서) 로컬 psql로 로컬 파일 그대로 시딩 — App EC2 경유·설치 전혀 없음
PGPASSWORD='<rds_password>' psql -h localhost -p 15432 -U postgres -d yourtrip \
  -f ../../scripts/sql/seed-benchmark.sql
```

로컬에 `psql`이 없다면(Windows 등) Docker의 `postgres:16` 이미지로 대체 가능하다 — Docker Desktop(Windows/Mac)에서는 컨테이너가 `localhost`가 아니라 `host.docker.internal`로 호스트를 참조해야 한다:

```bash
docker run --rm -e PGPASSWORD='<rds_password>' -v "$(pwd)/../../scripts/sql:/sql" postgres:16 \
  psql -h host.docker.internal -p 15432 -U postgres -d yourtrip -f /sql/seed-benchmark.sql
```

**App EC2를 재생성(replace)할 때마다 다시 시딩해야 한다** — `DB_DDL_AUTO=create`라 앱이 부팅할 때마다 Hibernate가 스키마를 DROP 후 재생성해 기존 데이터가 사라진다(실측으로 확인됨 — `user_data` 수정으로 App EC2를 재생성했더니 시드 데이터가 0건이 됐었다). RDS 자체는 살아있어도 App EC2가 새로 뜨는 순간 데이터가 지워진다는 점을 기억한다.

### 6. 로컬 Prometheus/Grafana를 원격 App EC2로 재조준

`prometheus.yml`(레포 루트)의 스크레이프 대상을 바꾼다:

```diff
- targets: ['host.docker.internal:8080']
+ targets: ['<terraform output -raw app_public_ip>:8080']
```

```bash
docker compose up -d prometheus grafana
```

Grafana(`localhost:3000`)의 Dashboards → Bottleneck Test → Presign CPU Bottleneck에서 그대로 확인 가능하다.

### 7. k6 부하테스트 실행

```bash
ssh -i ./yourtrip-loadtest-ssh ec2-user@$(terraform output -raw k6_public_ip)
```

접속 후:

```bash
cd /opt/app
k6 run -e BASE_URL=http://<App EC2 공인 IP>:8080 -e DOMAIN=uploadcourse -e MODE=pool scripts/k6/detail-ramping.js
k6 run -e BASE_URL=http://<App EC2 공인 IP>:8080 -e DOMAIN=mycourse -e MODE=pool scripts/k6/detail-ramping.js
```

### 8. 지표 수집

- Prometheus range query: `hikaricp_connections_pending`, `hikaricp_connections_acquire_seconds_max`, `hikaricp_connections_active`
- CloudWatch: App EC2 `mem_used_percent`(커스텀 네임스페이스 `YourtripLoadtest`), App EC2/k6 EC2/RDS/ElastiCache `CPUCreditBalance`, `CPUUtilization`

로컬 실측치(포화 시작 VU~20, `acquire_seconds` 최대 2.7초)와 비교한다.

### 9. 원상복구 및 철거

```bash
# 저장소 루트에서
git checkout -- prometheus.yml

# 이 디렉토리에서
terraform destroy
```

## 알아둬야 할 것

- **`rds_password`, `jwt_secret` 등 민감값은 `terraform.tfstate`에 평문으로 저장된다.** 기존 `terraform/`과 동일한 트레이드오프이며(로컬 state, remote backend 없음), 테스트 종료 후 `terraform destroy`로 리소스 자체를 없애는 것이 최선의 완화책이다.
- **App EC2는 빌드를 하지 않는다 — JAR를 scp로 전달하기 전까지 앱은 계속 재시작을 반복한다(정상 동작).** `Restart=on-failure`가 5초 간격으로 재시도하다가 JAR와 CloudFront 개인키가 도착하면 다음 재시도에서 스스로 뜬다. 예전엔 EC2에서 직접 `git clone`+Gradle 빌드를 했었는데, t3.micro(vCPU 2개지만 물리 코어는 1개뿐인 SMT/하이퍼스레딩 버스터블)에서 3~4분간 CPU를 태우는 빌드가 부하테스트 시작 시점의 CPU 크레딧 잔액을 미리 갉아먹어 측정 조건이 매번 달라지는 문제가 있어 로컬 빌드+scp로 바꿨다.
- **`DB_DDL_AUTO=create`를 쓴다 — App EC2를 재생성할 때마다 시드 데이터가 사라진다(실측으로 확인됨).** 이 RDS는 매 측정 전 재시딩하는 테스트 전용 DB라 로컬 벤치마크와 동일하게 스키마를 앱이 직접 생성하게 두는데, `create` 모드는 앱이 부팅할 때마다 기존 테이블을 DROP 후 재생성한다. RDS 자체는 살아있어도 App EC2만 새로 뜨면(예: `user_data` 수정 후 `apply`) 데이터가 지워지므로, App EC2를 재생성했다면 §"실행 순서" 5번(DB 시딩)을 반드시 다시 실행한다. 운영 DB(`validate`)와는 다른 설정임을 인지하고 있을 것.
- **버스터블(t3) 인스턴스 4개 전부 CPU 크레딧 고갈 가능성이 있다.** `CPUCreditBalance`가 0에 가까워지면 "로컬 CPU 경합"이 "AWS 크레딧 고갈"이라는 같은 종류의 새로운 병목으로 바뀐 것뿐이므로, 이 경우 측정 신뢰도를 재평가해야 한다.
