# EC2 + RDS + ElastiCache 분리 배포 환경 부하테스트 가이드

## 배경

[TASK-PRESIGN-BOTTLENECK-FIX.md의 "개선 제안 — 배포 환경(EC2 + RDS) 분리 부하테스트"](../tasks/connection-pool-bottleneck/TASK-PRESIGN-BOTTLENECK-FIX.md)가 지목한 문제 — 지금까지의 모든 부하테스트가 앱·PostgreSQL·Redis·Prometheus·Grafana·k6를 전부 로컬 개발 노트북 한 대에서 동시에 돌린 결과라, "인덱스 추가 이후 남은 병목이 진짜 구조적 문제(HikariCP 20:1 풀 크기)인지, 로컬 머신의 CPU 경합 노이즈인지" 구분이 안 됐다 — 를 해소하기 위해 앱은 EC2, DB는 RDS, 캐시는 ElastiCache, 부하생성기는 별도 EC2로 분리한 환경이다.

이 문서는 그 분리 환경이 **이미 배포된 이후**의 부하테스트 실행 절차(Prometheus 재조준 → k6 실행 → 병목 확인 → 지표 측정)를 다룬다. 인프라를 처음부터 구축/재구축하는 절차(Terraform apply/destroy)는 [terraform/loadtest/README.md](../../terraform/loadtest/README.md)를 따로 참고한다 — 이 문서와 역할이 겹치지 않게 분리했다.

## 1. 배포 환경 구성요소

| 리소스 | 스펙 | 역할 |
|---|---|---|
| VPC (전용) | `10.42.0.0/16`, 단일 AZ(`ap-northeast-2a`) 배치 | 계정 기본 VPC에 의존하지 않는 격리된 네트워크. 모든 컴퓨트 리소스를 한 AZ에 몰아 네트워크 변수를 최소화 |
| App EC2 | `t3.micro` | 실제 배포 타겟과 동일 스펙. Spring Boot 앱(JVM)만 단독 실행 — Redis/모니터링과 분리해 "1GB로 버티는가"를 순수하게 검증 |
| k6 EC2 | `t3.micro` | 부하생성기 전용. 앱/DB와 물리적으로 분리해 부하생성기 자신이 CPU 경합을 일으키던 로컬 환경의 문제를 재현하지 않는다 |
| RDS PostgreSQL | `db.t3.micro`, 단일 AZ | 로컬 시드 규모(course 6,000행, place 30,000행, place_image 60,000행)에 충분. `publicly_accessible=false`라 로컬에서 직접 접속은 안 되지만, SSM Session Manager 포트포워딩으로 App EC2를 경유해 터널링한다(§2) — App EC2엔 아무것도 설치하지 않는다 |
| ElastiCache Redis | `cache.t3.micro`, 단일 노드 | 로컬 `docker-compose.yml`의 Redis(`maxmemory 256mb`, `allkeys-lru`)에 대응하는 관리형 대체 |
| Prometheus / Grafana | **EC2에 배치하지 않음** — 로컬 개발 머신 그대로 유지 | 측정 대상이 아니라 관측자이므로 어디서 실행되든 지표 값은 동일하다. EC2에 같이 올리면 이번 실험이 없애려던 "여러 프로세스가 CPU를 나눠 쓰는" 문제를 다시 만드는 셈이라 의도적으로 분리했다 |

**현재 값은 재배포하면 바뀐다** — 아래는 특정 시점의 스냅샷이며, 항상 `terraform output`으로 최신 값을 다시 확인한다.

```bash
cd terraform/loadtest
terraform output
```

## 2. 사전 준비 확인

**App EC2는 스스로 빌드하지 않는다** — JAR를 로컬에서 미리 빌드해 scp로 전달해야 앱이 뜬다(이유는 §7-3 참고, 상세 절차는 [terraform/loadtest/README.md](../../terraform/loadtest/README.md) "실행 순서" 2~3번). 인프라만 `apply`된 직후라면 이 단계부터 확인한다:

```bash
# terraform.tfvars의 app_git_ref와 동일한 커밋을 체크아웃한 상태로, 저장소 루트에서
./gradlew bootJar -x test

cd terraform/loadtest
# /opt/app은 root 소유라 ec2-user가 직접 못 쓴다(실측 확인) — /tmp 경유 + sudo mv
scp -i ./yourtrip-loadtest-ssh ../../build/libs/yourtrip-0.0.1-SNAPSHOT.jar \
  ec2-user@<App EC2 공인 IP>:/tmp/app.jar
scp -i ./yourtrip-loadtest-ssh ../cloudfront_private_key.pem \
  ec2-user@<App EC2 공인 IP>:/tmp/cloudfront_private_key.pem

ssh -i ./yourtrip-loadtest-ssh ec2-user@<App EC2 공인 IP> '
  sudo mv /tmp/app.jar /opt/app/app.jar &&
  sudo mv /tmp/cloudfront_private_key.pem /opt/app/cloudfront_private_key.pem &&
  sudo chown ec2-user:ec2-user /opt/app/app.jar /opt/app/cloudfront_private_key.pem &&
  sudo chmod 600 /opt/app/cloudfront_private_key.pem
'
```

두 파일 다 도착하면 systemd가 `Restart=on-failure`로 재시도하다가 자동으로 뜬다(수동 restart 불필요). 앱이 정상 기동했는지 확인한다:

```bash
curl -sf http://<App EC2 공인 IP>:8080/actuator/health
```

`{"status":"UP", "components":{"db":{"status":"UP"...}, "redis":{"status":"UP"...}}}`가 나와야 한다. 안 뜬다면 §7의 트러블슈팅부터 확인한다.

**DB 시딩 여부도 확인한다** — RDS는 `publicly_accessible=false`라 로컬에서 직접 `psql` 접속이 안 되므로, SSM Session Manager로 App EC2를 경유해 터널링한다(App EC2엔 아무것도 설치하지 않는다 — 상세 절차는 [terraform/loadtest/README.md](../../terraform/loadtest/README.md) "실행 순서" 5번, 배경은 §7-4 참고).

**App EC2를 재생성했다면 반드시 재시딩한다** — `DB_DDL_AUTO=create`라 앱이 부팅할 때마다 Hibernate가 스키마를 DROP 후 재생성해서, RDS 자체는 살아있어도 App EC2만 새로 뜨면 데이터가 사라진다(실측으로 확인됨). App EC2를 안 건드렸다면 이미 시딩된 상태일 수 있으니, 재실행 전 `SELECT count(*) FROM my_course;`로 먼저 확인한다.

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

로컬 실측에서 "인덱스로 쿼리는 49~236배 빨라졌는데 풀 포화 지표는 그대로였던" 원인이 로컬 머신의 CPU 공유 문제였다는 게 JFR로 밝혀졌었다([TASK-PRESIGN-BOTTLENECK-FIX-INDEX-LOCAL.md](../tasks/connection-pool-bottleneck/stage0/local/index.md) Phase C 참고). 이번 환경에서 같은 종류의 잡음이 남아있는지는 CloudWatch로 확인한다.

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

측정한 값을 아래 표에 채워 로컬 실측치(인덱스 추가 후, [TASK-PRESIGN-BOTTLENECK-FIX-INDEX-LOCAL.md](../tasks/connection-pool-bottleneck/stage0/local/index.md) "인덱스 추가 결과" 참고)와 비교한다.

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

### 7-1. `git clone --branch <커밋 SHA> --depth 1` 실패 (k6 EC2에만 해당)

`app_git_ref`에 브랜치명이 아니라 커밋 해시를 넘기면 `fatal: Remote branch <sha> not found in upstream origin`로 실패한다 — 이 조합은 브랜치/태그 이름만 지원한다. `terraform/loadtest/templates/k6-user-data.sh.tpl`에서 `git init` + `git fetch --depth 1 origin <SHA>` + `git checkout FETCH_HEAD` 방식으로 이미 수정 반영됨(GitHub 공개 저장소는 임의 SHA fetch를 허용).

> App EC2는 더 이상 git을 쓰지 않는다(§7-3 참고) — 이 함정은 k6 EC2가 부하 스크립트를 checkout할 때만 해당된다.

### 7-2. k6 rpm 저장소 404

`baseurl=https://dl.k6.io/rpm`을 직접 가리키는 `.repo` 파일 방식은 `repodata/repomd.xml`이 404다. `dnf install -y https://dl.k6.io/rpm/repo.rpm`(공식 문서 기준 최신 방법)으로 이미 수정 반영됨.

### 7-3. App EC2는 원래 t3.micro에서 직접 Gradle 빌드를 했었다 — CPU 크레딧을 갉아먹는 문제로 로컬 빌드+scp로 전환

초기 버전은 App EC2 `user_data`가 부팅 시 `git fetch` + `./gradlew bootJar`를 직접 실행했다. 실측으로 두 가지 문제가 드러났다:

1. **CPU 크레딧 소진**: t3.micro는 vCPU 2개(단, 물리 코어는 1개뿐이고 SMT/하이퍼스레딩으로 논리 코어 2개를 낸 것) 버스터블 인스턴스인데, 빌드가 3~4분간 CPU를 거의 100% 태운다. 이게 **부하테스트가 시작되기도 전에 CPU 크레딧 잔액을 갉아먹어**, "측정 시작 시점의 인스턴스 상태"가 매번 달라지는 변수가 됐다.
2. **1GB RAM에서 Gradle 빌드가 OOM 위험**: 임시 스왑(1GB)을 켰다 끄는 우회책이 필요했다 — 스왑이 남아있으면 그 자체로 또 다른 변수(디스크 I/O 지연)가 됐다.

서버에서 직접 빌드하는 방식 자체가 재현 불가능한 아티팩트·롤백 불가 문제도 안고 있어, **로컬(또는 CI)에서 빌드한 불변 아티팩트를 scp로 전달하는 방식**으로 바꿨다(`terraform/loadtest/templates/app-user-data.sh.tpl` 상단 주석 참고). App EC2는 이제 git도, Gradle도 쓰지 않는다.

**그 결과 App EC2에서 CloudFront 개인키와 app.jar 둘 다 scp로 전달하기 전까지는 앱이 기동 자체를 못 한다** — `CloudFrontService` 빈 초기화가 개인키 파일을 필수로 요구하고, 애초에 `app.jar` 자체가 없기 때문이다. 둘 다 "나중에 해도 되는 선택적 단계"가 아니라 **앱 기동의 필수 선행조건**이다(§2 참고). `Restart=on-failure`가 계속 재시도하다가 두 파일이 도착하면 다음 재시도에서 자연스럽게 기동한다 — 수동 restart는 필요 없다.

### 7-4. RDS `publicly_accessible=false` — 로컬에서 직접 `psql` 불가 → SSM 포트포워딩으로 해결

RDS 엔드포인트 DNS 자체가 VPC 사설 IP로만 resolve되기 때문에(`publicly_accessible=false`), 보안그룹을 아무리 열어도 인터넷에서 그 IP로 가는 경로가 애초에 없다(실측으로 확인됨 — 세그먼트 보안그룹 규칙과 별개의 라우팅 문제).

초기 버전은 App EC2에 SSH로 접속해 `postgresql16`을 설치하고 그 안에서 `psql`을 실행하는 방식으로 우회했다. 이 방식은 동작은 했지만, "측정 대상 인스턴스는 최대한 순수하게 유지한다"는 이번 인프라의 설계 원칙(Prometheus/Grafana/Redis를 EC2 밖에 둔 것과 동일한 이유)에 어긋났다 — App EC2에 불필요한 패키지가 쌓이는 셈이었다. **SSM Session Manager 포트포워딩**으로 바꿔서 App EC2엔 아무것도 설치하지 않고, 로컬 `psql`이 RDS로 직접 터널링되게 했다(App EC2 IAM 역할의 `AmazonSSMManagedInstanceCore` 권한만 있으면 됨 — `terraform/loadtest/iam.tf`에 이미 반영, 상세 명령은 [terraform/loadtest/README.md](../../terraform/loadtest/README.md) "실행 순서" 5번).

새로 붙인 IAM 정책이 실제로 적용되기까지 **전파 지연**이 있을 수 있다 — SSM Agent 로그(`/var/log/amazon/ssm/amazon-ssm-agent.log`)에 `AccessDeniedException`이 보이면 몇 분 뒤 `sudo systemctl restart amazon-ssm-agent`로 재시도한다(실측으로 확인됨).

### 7-5. `/opt/app`은 root 소유라 `ec2-user`가 직접 `scp`로 못 씀

`scp ... ec2-user@<IP>:/opt/app/app.jar`을 바로 실행하면 `scp: dest open "/opt/app/app.jar": Permission denied`가 난다 — `user_data`가 root 권한으로 `mkdir -p /opt/app`을 실행해서 디렉토리 소유자가 `root`이기 때문이다. `/tmp`로 먼저 올리고 `ssh` 세션 안에서 `sudo mv`로 옮겨야 한다(§2 예시 참고).

### 7-6. `aws_security_group`(rule 아님)의 `description`에 한글을 쓰면 apply가 실패함

`terraform validate`로는 안 잡히고 실제 `apply` 시점에 `InvalidParameterValue: ... Character sets beyond ASCII are not supported`로 실패한다. `aws_security_group_rule`뿐 아니라 `aws_security_group` 자체의 `description`도 ASCII만 허용된다 — `terraform/loadtest/security_groups.tf`는 전부 영문으로 이미 수정됨.

### 7-7. `user_data`를 수정하고 `apply`해도 이미 떠 있는 인스턴스는 그대로임

AWS는 `user_data`를 **최초 부팅 시 한 번만** 실행한다. `templates/app-user-data.sh.tpl`을 고친 뒤 그냥 `terraform apply`하면 AWS에 등록된 `user_data` 속성만 갱신될 뿐, 이미 부팅된 인스턴스는 예전 스크립트로 실행된 상태 그대로 남는다(재부팅해도 cloud-init이 재실행되지 않음). `terraform/loadtest/ec2_app.tf`의 `aws_instance.app`에 `user_data_replace_on_change = true`를 설정해뒀기 때문에, 지금은 `user_data`가 바뀌면 `terraform apply`가 인스턴스를 자동으로 재생성(destroy+create)한다 — 이 옵션이 없다면 수동으로 `terraform apply -replace="aws_instance.app"`을 써야 한다.

## 8. 측정 종료 후 정리

두 단계로 나뉜다 — **매 측정 세션 종료 시**(실무 관행)와 **인프라 자체를 완전히 정리할 때**.

### 8-1. 매 측정 세션 종료 시 — App/k6 EC2만 정지

```bash
# 1. 로컬 prometheus.yml 원상복구
git checkout -- prometheus.yml

# 2. App EC2 · k6 EC2 인스턴스 ID 확인
cd terraform/loadtest
APP_ID=$(terraform output -raw app_instance_id)
K6_ID=$(terraform output -raw k6_instance_id)

# 3. 두 인스턴스만 정지 (RDS·ElastiCache·VPC는 그대로 둔다)
AWS_PROFILE=terraform-admin aws ec2 stop-instances --instance-ids "$APP_ID" "$K6_ID"
AWS_PROFILE=terraform-admin aws ec2 wait instance-stopped --instance-ids "$APP_ID" "$K6_ID"
```

- `terraform destroy` 대신 `stop-instances`를 쓰는 이유: EBS 루트 볼륨·인스턴스 자체는 그대로 남고 **컴퓨트 요금만 멈춘다** — 다음 세션에서 `start-instances`만으로 바로 재개할 수 있어, 매번 인프라를 처음부터 재구축(수십 분, jar/키 재배포 포함)하지 않아도 된다.
- **RDS·ElastiCache는 이 절차로 정지되지 않고 계속 과금된다.** RDS는 `aws rds stop-db-instance`로 정지할 수 있지만 AWS가 7일 뒤 자동으로 재시작시키는 제약이 있고, ElastiCache는 애초에 정지 기능이 없어 삭제/재생성만 가능하다. 이 저장소는 지금까지 RDS/ElastiCache는 정지 대상에서 제외하고 App/k6 EC2만 정지해왔다 — 장기간 재사용하지 않을 계획이라면 §8-2로 완전히 철거한다.

**재기동 시 주의 — 정지→재기동만 해도 데이터가 사라진다**: App EC2를 `stop`했다가 `start`하면(재생성이 아니어도) `yourtrip-app` 시스템 서비스가 다시 뜨면서 `DB_DDL_AUTO=create`가 스키마를 DROP 후 재생성한다 — RDS 자체는 살아있어도 App EC2를 정지→재기동하기만 하면 데이터가 사라진다는 뜻이다(§2의 "재생성했다면"과 같은 함정이 재생성이 아닌 단순 재시작만으로도 재현됨 — 실측으로 확인). 다음 측정 전 반드시 재시딩부터 한다.

### 8-2. 인프라 자체를 완전히 정리할 때 — 전체 철거

```bash
cd terraform/loadtest
AWS_PROFILE=terraform-admin terraform destroy
```

App/k6 EC2뿐 아니라 RDS·ElastiCache·VPC까지 전부 삭제해 과금을 완전히 없앤다 — 이후 다시 쓰려면 인프라를 처음부터 재구축(`terraform apply` + jar/키 재배포 + 재시딩)해야 한다.

## 참고 문서

- [terraform/loadtest/README.md](../../terraform/loadtest/README.md) — 인프라 구축/철거 절차(이 문서와 역할 분리)
- [TASK-PRESIGN-BOTTLENECK-FIX.md](../tasks/connection-pool-bottleneck/TASK-PRESIGN-BOTTLENECK-FIX.md) — 이 부하테스트가 속한 단계별 계획 문서
- [ec2-rds.md](../tasks/connection-pool-bottleneck/stage0/production/ec2-rds.md) — 이 부하테스트의 목적이 된 로컬 실측 기록과 실제 측정 결과
- [LOAD-TESTING-GUIDE.md](LOAD-TESTING-GUIDE.md) — k6/JFR 사용법 전반
- [MONITORING-GUIDE.md](MONITORING-GUIDE.md) — Prometheus/Grafana 구축 및 기본 사용법
