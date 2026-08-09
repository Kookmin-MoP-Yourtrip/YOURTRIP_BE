# EC2 + RDS + ElastiCache 분리 배포 환경 부하테스트 가이드

## 배경

[TASK-PRESIGN-BOTTLENECK-FIX.md의 "개선 제안 — 배포 환경(EC2 + RDS) 분리 부하테스트"](../tasks/TASK-PRESIGN-BOTTLENECK-FIX.md)가 지목한 문제 — 지금까지의 모든 부하테스트가 앱·PostgreSQL·Redis·Prometheus·Grafana·k6를 전부 로컬 개발 노트북 한 대에서 동시에 돌린 결과라, "인덱스 추가 이후 남은 병목이 진짜 구조적 문제(HikariCP 20:1 풀 크기)인지, 로컬 머신의 CPU 경합 노이즈인지" 구분이 안 됐다 — 를 해소하기 위해 앱은 EC2, DB는 RDS, 캐시는 ElastiCache, 부하생성기는 별도 EC2로 분리한 환경이다.

이 문서는 그 분리 환경이 **이미 배포된 이후**의 부하테스트 실행 절차(Prometheus 재조준 → k6 실행 → 병목 확인 → 지표 측정)를 다룬다. 인프라를 처음부터 구축/재구축하는 절차(Terraform apply/destroy)는 [terraform/loadtest/README.md](../../terraform/loadtest/README.md)를 따로 참고한다 — 이 문서와 역할이 겹치지 않게 분리했다.

## 1. 배포 환경 구성요소

| 리소스 | 스펙 | 역할 |
|---|---|---|
| VPC (전용) | `10.42.0.0/16`, 단일 AZ(`ap-northeast-2a`) 배치 | 계정 기본 VPC에 의존하지 않는 격리된 네트워크. 모든 컴퓨트 리소스를 한 AZ에 몰아 네트워크 변수를 최소화 |
| App EC2 | `t3.micro` | 실제 배포 타겟과 동일 스펙. Spring Boot 앱(JVM)만 단독 실행 — Redis/모니터링과 분리해 "1GB로 버티는가"를 순수하게 검증 |
| k6 EC2 | `t3.micro` | 부하생성기 전용. 앱/DB와 물리적으로 분리해 부하생성기 자신이 CPU 경합을 일으키던 로컬 환경의 문제를 재현하지 않는다 |
| RDS PostgreSQL | `db.t3.micro`, 단일 AZ | 로컬 시드 규모(course 6,000행, place 30,000행, place_image 60,000행)에 충분. `publicly_accessible=false` — **로컬에서 직접 접속 불가**(§7 참고) |
| ElastiCache Redis | `cache.t3.micro`, 단일 노드 | 로컬 `docker-compose.yml`의 Redis(`maxmemory 256mb`, `allkeys-lru`)에 대응하는 관리형 대체 |
| Prometheus / Grafana | **EC2에 배치하지 않음** — 로컬 개발 머신 그대로 유지 | 측정 대상이 아니라 관측자이므로 어디서 실행되든 지표 값은 동일하다. EC2에 같이 올리면 이번 실험이 없애려던 "여러 프로세스가 CPU를 나눠 쓰는" 문제를 다시 만드는 셈이라 의도적으로 분리했다 |

**현재 값은 재배포하면 바뀐다** — 아래는 특정 시점의 스냅샷이며, 항상 `terraform output`으로 최신 값을 다시 확인한다.

```bash
cd terraform/loadtest
terraform output
```

## 2. 사전 준비 확인

인프라가 이미 떠 있고 앱이 정상 기동했는지 먼저 확인한다.

```bash
curl -sf http://<App EC2 공인 IP>:8080/actuator/health
```

`{"status":"UP", "components":{"db":{"status":"UP"...}, "redis":{"status":"UP"...}}}`가 나와야 한다. 안 뜬다면 §7의 트러블슈팅부터 확인한다.

**DB 시딩 여부도 확인한다** — RDS는 `publicly_accessible=false`라 로컬에서 직접 `psql` 접속이 안 되므로, 시딩은 App EC2를 경유해서 한다(§7 참고). 이미 시딩했다면 재실행 전 반드시 재시딩(`TRUNCATE` 후 재삽입, `scripts/sql/seed-benchmark.sql` 자체가 이를 수행)해 로컬 벤치마크와 동일한 조건을 유지한다.

## 3. 로컬 Prometheus/Grafana 원격 재조준

Prometheus는 능동적으로 5초마다 대상 주소에 HTTP GET을 날려 지표를 긁어오는 구조([prometheus.yml](../../prometheus.yml))라, 대상 주소만 바꾸면 실행 위치는 로컬에 둔 채로 원격의 App EC2를 관측할 수 있다.

```diff
  - job_name: 'presign'
    metrics_path: '/actuator/prometheus'
    static_configs:
-     - targets: ['host.docker.internal:8080']
+     - targets: ['<App EC2 공인 IP>:8080']
```

```bash
docker compose up -d prometheus grafana
```

**확인**: `http://localhost:9090` → `Status` → `Targets`에서 `presign` job이 `UP`인지 확인한다(`cloudfront` job은 이번 환경에서 쓰지 않으므로 `DOWN`이 정상 — [MONITORING-GUIDE.md](MONITORING-GUIDE.md) §3-2 참고).

Grafana(`localhost:3000`, admin/admin)는 아무것도 바꿀 필요 없다 — `Dashboards → Bottleneck Test → Presign CPU Bottleneck`에서 그대로 확인 가능([MONITORING-GUIDE.md](MONITORING-GUIDE.md) §4 참고).

**측정이 끝나면 반드시 원상복구한다**:

```bash
git checkout -- prometheus.yml
```

## 4. k6 부하테스트 실행

k6 EC2에 SSH로 접속해 실행한다(k6가 로컬이 아니라 별도 EC2에 있는 이유는 §1 참고).

```bash
cd terraform/loadtest
ssh -i ./yourtrip-loadtest-ssh ec2-user@<k6 EC2 공인 IP>
```

접속 후, App EC2를 대상으로 mycourse/uploadcourse 각각 ramping 프로파일(VU 1→200, 450초, [LOAD-TESTING-GUIDE.md §6](LOAD-TESTING-GUIDE.md) 참고)을 실행한다:

```bash
cd /opt/app
k6 run -e BASE_URL=http://<App EC2 공인 IP>:8080 -e DOMAIN=uploadcourse -e MODE=pool scripts/k6/detail-ramping.js
k6 run -e BASE_URL=http://<App EC2 공인 IP>:8080 -e DOMAIN=mycourse   -e MODE=pool scripts/k6/detail-ramping.js
```

- `MODE=pool`: course_id를 2~3000 범위에서 무작위 선택 — 캐시가 고르게 데워지는 실제 트래픽에 가까운 패턴
- 두 도메인을 동시에 돌리지 않는다 — 로컬 벤치마크와 동일하게 순차 실행해야 지표가 섞이지 않는다
- 실행 중에는 §3에서 열어둔 Grafana 대시보드를 "Last 15 minutes" + 5초 auto-refresh로 관찰한다

## 5. 병목 지점 확인 방법

### 5-1. Prometheus에서 직접 확인 (`http://localhost:9090`)

| 지표 | PromQL | 의미 |
|---|---|---|
| 대기 중인 커넥션 요청 수 | `hikaricp_connections_pending{pool="HikariPool-1"}` | **0을 넘기 시작하는 시점이 포화의 첫 신호** — TPS/CPU가 눈에 띄게 나빠지기 전에 가장 먼저 반응한다 |
| 활성 커넥션 수 | `hikaricp_connections_active{pool="HikariPool-1"}` | 풀 크기(기본 10)에 도달했는지 확인 |
| 커넥션 점유시간 최댓값 | `hikaricp_connections_acquire_seconds_max{pool="HikariPool-1"}` | 롤링 윈도우 최댓값이라 절대 최댓값은 아님 — 추세 확인용 |
| 평균 커넥션 점유시간(더 정확함) | `rate(hikaricp_connections_usage_seconds_sum{pool="HikariPool-1"}[1m]) / rate(hikaricp_connections_usage_seconds_count{pool="HikariPool-1"}[1m])` | `_max`보다 신뢰도 높은 지표 — 카운터형이라 구간 평균을 정확히 계산할 수 있다 |
| JVM CPU 사용률 | `process_cpu_usage` | 이 프로세스만의 CPU — 낮은데 지연이 커지면 CPU 병목이 아니라는 뜻 |
| 시스템 전체 CPU | `system_cpu_usage` | App EC2 인스턴스 전체 CPU — `process_cpu_usage`와 같이 봐야 "다른 프로세스가 CPU를 뺏는지" 판단 가능. 이 환경은 앱만 단독 실행되므로 로컬과 달리 이 둘의 차이가 거의 없어야 정상 |
| Tomcat 활성 스레드 | `tomcat_threads_busy_threads` | `maxThreads`(기본 200)에 도달했는지 — 도달했다면 대부분의 요청 스레드가 DB를 기다리며 멈춰있다는 뜻 |

### 5-2. 포화 시작 VU(knee) 판정

`hikaricp_connections_pending`이 0을 넘기 시작하는 시점을 k6 ramping 스테이지 경계(60/60/60/90/90/90초 = VU 5/10/20/50/100/200)와 대조해 "몇 VU부터 포화가 시작됐는가"를 특정한다. [LOAD-TESTING-GUIDE.md §6](LOAD-TESTING-GUIDE.md)의 knee 개념과 동일한 방법론이다.

### 5-3. CloudWatch에서 확인 (환경 자체의 한계 여부 판별용)

로컬 실측에서 "인덱스로 쿼리는 49~236배 빨라졌는데 풀 포화 지표는 그대로였던" 원인이 로컬 머신의 CPU 공유 문제였다는 게 JFR로 밝혀졌었다([TASK-PRESIGN-BOTTLENECK-FIX.md](../tasks/TASK-PRESIGN-BOTTLENECK-FIX.md) Phase C 참고). 이번 환경에서 같은 종류의 잡음이 남아있는지는 CloudWatch로 확인한다.

```bash
# App EC2 메모리 사용률 (커스텀 네임스페이스 — CloudWatch Agent가 수집)
AWS_PROFILE=terraform-admin aws cloudwatch get-metric-statistics \
  --namespace YourtripLoadtest --metric-name mem_used_percent \
  --dimensions Name=InstanceId,Value=<App EC2 instance ID> \
  --start-time <ISO8601> --end-time <ISO8601> --period 30 --statistics Maximum

# 버스터블(t3) 인스턴스 CPU 크레딧 잔량 — App EC2/k6 EC2/RDS/ElastiCache 전부 확인
AWS_PROFILE=terraform-admin aws cloudwatch get-metric-statistics \
  --namespace AWS/EC2 --metric-name CPUCreditBalance \
  --dimensions Name=InstanceId,Value=<인스턴스 ID> \
  --start-time <ISO8601> --end-time <ISO8601> --period 60 --statistics Minimum
```

`CPUCreditBalance`가 테스트 도중 0에 가까워지면, "로컬 CPU 경합"이 "AWS 크레딧 고갈"이라는 같은 종류의 새 병목으로 바뀐 것뿐이라는 뜻이다 — 이 경우 이번 측정 결과의 신뢰도를 재평가해야 한다.

## 6. 성능 지표 측정 및 로컬 대비 비교

측정한 값을 아래 표에 채워 로컬 실측치(인덱스 추가 후, [TASK-PRESIGN-BOTTLENECK-FIX.md](../tasks/TASK-PRESIGN-BOTTLENECK-FIX.md) "인덱스 추가 결과" 참고)와 비교한다.

### mycourse (DB 접근이 필수인 경로 — 핵심 비교 대상)

| | TPS | p95 | `pending` 최대 | `acquire_seconds` 최대 | 포화 시작 VU |
|---|---|---|---|---|---|
| 로컬(인덱스 후, 비격리 환경) | 110.89/s | 1.63s | 181 | 2.7286s | VU~20 |
| **EC2+RDS 분리 환경(이번 측정)** | | | | | |

### uploadcourse (캐시 히트 위주 — 참고용, 원래도 풀 경합 없었음)

| | TPS | p95 | `pending` 최대 |
|---|---|---|---|
| 로컬(인덱스 후) | 1,760.8/s | 71.02ms | 0 |
| **EC2+RDS 분리 환경(이번 측정)** | | | |

**판정 기준**:
- mycourse의 포화 시작 VU가 20보다 뚜렷이 뒤로 밀리거나 `acquire_seconds`/`pending`이 크게 줄었다면 → 로컬에서 관찰된 잔여 병목은 **환경 노이즈였음이 확정**된다.
- 거의 변화가 없다면 → TASK-PRESIGN-BOTTLENECK-FIX.md 4단계(HikariCP 풀 크기 재검토)가 **진짜 구조적 병목**이라는 뜻이다.

## 7. 트러블슈팅 — 실측으로 발견된 함정

이 환경을 처음 구축하며 실제로 겪은 문제들이다. 재배포 시 다시 마주칠 수 있어 기록해둔다.

### 7-1. `git clone --branch <커밋 SHA> --depth 1` 실패

`app_git_ref`에 브랜치명이 아니라 커밋 해시를 넘기면 `fatal: Remote branch <sha> not found in upstream origin`로 실패한다 — 이 조합은 브랜치/태그 이름만 지원한다. `terraform/loadtest/templates/app-user-data.sh.tpl`/`k6-user-data.sh.tpl`에서 `git init` + `git fetch --depth 1 origin <SHA>` + `git checkout FETCH_HEAD` 방식으로 이미 수정 반영됨(GitHub 공개 저장소는 임의 SHA fetch를 허용).

### 7-2. k6 rpm 저장소 404

`baseurl=https://dl.k6.io/rpm`을 직접 가리키는 `.repo` 파일 방식은 `repodata/repomd.xml`이 404다. `dnf install -y https://dl.k6.io/rpm/repo.rpm`(공식 문서 기준 최신 방법)으로 이미 수정 반영됨.

### 7-3. CloudFront 개인키 없이는 앱이 기동 자체를 못 함

`CloudFrontService` 빈 초기화가 개인키 파일을 필수로 요구해서, 개인키 전달 전에는 `systemctl enable --now`가 무한 크래시 루프(`Restart=on-failure`)를 돈다 — "나중에 해도 되는 선택적 단계"가 아니라 **앱 기동의 필수 선행조건**이다.

```bash
scp -i ./yourtrip-loadtest-ssh ../cloudfront_private_key.pem \
  ec2-user@<App EC2 공인 IP>:/opt/app/cloudfront_private_key.pem

ssh -i ./yourtrip-loadtest-ssh ec2-user@<App EC2 공인 IP> \
  'sudo chown ec2-user:ec2-user /opt/app/cloudfront_private_key.pem && \
   sudo chmod 600 /opt/app/cloudfront_private_key.pem && \
   sudo systemctl restart yourtrip-app.service'
```

### 7-4. RDS `publicly_accessible=false` — 로컬에서 직접 `psql` 불가

`allow_dev_psql_access=true`로 보안그룹을 열어도 소용없다 — RDS 엔드포인트 DNS 자체가 VPC 사설 IP로만 resolve되기 때문에(`publicly_accessible=false`), 인터넷에서 그 IP로 가는 경로가 애초에 없다. 반드시 같은 VPC 안의 App EC2를 경유한다:

```bash
scp -i ./yourtrip-loadtest-ssh ../../scripts/sql/seed-benchmark.sql \
  ec2-user@<App EC2 공인 IP>:/tmp/seed-benchmark.sql

ssh -i ./yourtrip-loadtest-ssh ec2-user@<App EC2 공인 IP> 'sudo dnf install -y postgresql16'

ssh -i ./yourtrip-loadtest-ssh ec2-user@<App EC2 공인 IP> \
  "PGPASSWORD='<rds_password>' psql -h <RDS 엔드포인트> -U postgres -d yourtrip -f /tmp/seed-benchmark.sql"
```

### 7-5. `aws_security_group`(rule 아님)의 `description`에 한글을 쓰면 apply가 실패함

`terraform validate`로는 안 잡히고 실제 `apply` 시점에 `InvalidParameterValue: ... Character sets beyond ASCII are not supported`로 실패한다. `aws_security_group_rule`뿐 아니라 `aws_security_group` 자체의 `description`도 ASCII만 허용된다 — `terraform/loadtest/security_groups.tf`는 전부 영문으로 이미 수정됨.

## 8. 측정 종료 후 정리

```bash
# 1. 로컬 prometheus.yml 원상복구
git checkout -- prometheus.yml

# 2. 인프라 철거 (비용 최소화 — 임시 인프라이므로 측정 후 즉시 철거)
cd terraform/loadtest
AWS_PROFILE=terraform-admin terraform destroy
```

## 참고 문서

- [terraform/loadtest/README.md](../../terraform/loadtest/README.md) — 인프라 구축/철거 절차(이 문서와 역할 분리)
- [TASK-PRESIGN-BOTTLENECK-FIX.md](../tasks/TASK-PRESIGN-BOTTLENECK-FIX.md) — 이 부하테스트의 목적이 된 로컬 실측 기록과 비교 기준
- [LOAD-TESTING-GUIDE.md](LOAD-TESTING-GUIDE.md) — k6/JFR 사용법 전반
- [MONITORING-GUIDE.md](MONITORING-GUIDE.md) — Prometheus/Grafana 구축 및 기본 사용법
