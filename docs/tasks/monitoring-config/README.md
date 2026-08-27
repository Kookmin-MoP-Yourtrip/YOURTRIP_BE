# 운영 모니터링을 Grafana Cloud + Alloy로 구성한다 — 에이전트를 늘리지 않고 로그까지 얹는다

> [#121](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/121)의 설계·판정 기준 기록이다. 아래 판정 기준은 **구축 전에 못 박은 것**이고, 실제 검증 결과는 [verification.md](verification.md)에 있다.
>
> **왜 하는가**: 앱은 `/actuator/prometheus`를 이미 노출하는데 **운영에서 그것을 가져가는 경로가 없다.** Prometheus·Grafana는 로컬 부하테스트 전용이고, 운영에서 나오는 관측값은 CloudWatch Agent의 `mem_used_percent` 하나뿐이다. [memory-map.md](../jvm-heap-sizing/memory-map.md) §7이 남긴 한계("CloudWatch Agent 130MB를 측정 목적의 상수로 취급했다 — 운영에서 이 에이전트를 켤지는 이 문서의 범위 밖이다")가 이 작업의 착수 지점이다.
>
> **결론만 먼저** — **Grafana Cloud free tier + Alloy 단일 에이전트**를 채택한다. 앱이 노출하는 시리즈는 실측 **471개**로 free 한도 10,000의 5%에 불과하고, Alloy가 node_exporter를 내장하므로 **CloudWatch Agent를 제거하면 상주 프로세스가 늘지 않는다.** 로그(journald)까지 같은 에이전트가 처리한다.
>
> **확정하지 못한 것** — Alloy의 상주 RSS를 재기 전이다. 이 값이 CloudWatch Agent의 **130.6MB**보다 큰지 작은지가 [jvm-heap-sizing](../jvm-heap-sizing/README.md)의 `-Xmx` 예산에 직접 영향을 준다. 판정 구간은 아래 P2에 미리 등록해 둔다.

## 무엇이 문제였나

운영 지표를 **아무도 보고 있지 않다.** 세 갈래가 제각각이었다.

| 영역 | 상태 |
|---|---|
| 로컬 개발 | `docker-compose.yml`의 Prometheus + Grafana — 부하테스트 전용 |
| 운영 서버 | CloudWatch Agent가 `mem_used_percent` 하나만 |
| 운영 대시보드 | **없다** |

세 번째가 빠뜨린 것이 아니라는 점이 중요하다. **운영 지표를 수집한 적이 없으므로 대시보드가 존재할 수 없었다.** 순서가 "수집 경로 → 대시보드"인데 첫 단계가 비어 있었다.

이슈 본문의 체크리스트는 "기존 Grafana 대시보드 이식"이라고 적고 있지만, `scripts/grafana/`에 있는 것은 [presign-bottleneck.json](../../../scripts/grafana/provisioning/dashboards/presign-bottleneck.json) **하나뿐이고 8패널 전부 `$arm` 변수(`job=presign|cloudfront`)에 묶인 부하테스트 판정용**이다. 옮겨올 운영 대시보드는 처음부터 없었고, 재사용되는 것은 PromQL 표현식과 provisioning 패턴, 그리고 [monitoring.md](../../guide/monitoring.md) §4-3이 안내하는 커뮤니티 대시보드 `11378`이다.

## 결정과 근거

### 왜 Grafana Cloud인가

| 후보 | 판단 |
|---|---|
| **Grafana Cloud free + Alloy** | ✅ 비용 0(10k 시리즈 / 로그 50GB / 14일 보존). PromQL·대시보드 자산을 그대로 쓴다. localhost 스크레이프라 ALB의 `/actuator` 차단 규칙과 충돌하지 않는다 |
| CloudWatch Agent 스크레이프 | ❌ 커스텀 메트릭이 **지표당 월 $0.30**이다. 실측 471 시리즈를 그대로 올리면 **월 $141**. 화이트리스트로 줄이면 비용은 잡히지만 Grafana 자산을 버리면서 관측 범위까지 좁아진다 |
| 별도 모니터링 인스턴스 | ❌ 상시 켜면 "운영은 내렸는데 모니터링만 과금"이고, 함께 내리면 **지표 이력이 인스턴스와 함께 사라진다** |

**결정적 근거는 `terraform/prod`가 온디맨드라는 사실이다.** 이 저장소의 운영 인프라는 데모·측정 때만 띄우고 끝나면 destroy한다. 그래서 실제 요구는 "상시 감시"가 아니라 **"띄운 동안 관측하고, 내린 뒤에도 그 기록을 열람"** 이다. 이건 부하테스트 결과를 문서로 남겨온 방식과 같은 요구이고, **데이터를 박스 밖에 두는 방식만 이를 만족한다.** 세 번째 후보가 여기서 탈락한다.

> **단, "내린 뒤에도 남는다"는 14일 창 안에서만 성립한다.** free tier의 보존 기간이 14일이기 때문이다(플랜 자체는 영구 무료이고 만료가 없다 — 보존과 플랜 수명은 다른 얘기다). 즉 Grafana Cloud는 **측정 중에 보는 창**이고, **남기는 곳은 이 저장소**다. 측정 수치를 [verification.md](verification.md)로 옮겨 적고 대시보드를 캡처하는 것이 이 작업의 산출물에 포함되는 이유다.

### 왜 node_exporter가 아니라 Alloy인가

호스트 지표(박스 메모리·CPU)는 JVM이 알 수 없다. `/actuator/prometheus`는 힙은 알아도 `MemAvailable`을 모른다. 그래서 호스트용 수집기가 따로 필요한데, 후보가 둘이었다.

**로그 중앙화 계획이 있다는 것이 이 선택을 갈랐다.** node_exporter는 메트릭 전용이라 로그 에이전트가 별도로 필요하고, 그 자리에 오던 **Promtail은 2026년 3월 EOL이다.** 새로 도입할 대상이 아니다.

| | 프로세스 수 |
|---|---|
| Prometheus agent + node_exporter + Promtail | 3 |
| **Alloy 단독** | **1** |

Alloy는 `prometheus.exporter.unix`로 node_exporter를 **내장**하고 `loki.source.journal`로 journald를 읽는다. 단일 프로세스로 세 갈래를 모두 처리한다. Alloy가 Prometheus agent보다 단일 프로세스 기준 30% 무겁다는 점은 알려져 있지만, **비교 대상이 3-프로세스 구성이면 그 단점은 성립하지 않는다.**

### 왜 CloudWatch Agent를 제거하는가

역할이 겹치기 때문이다. CloudWatch Agent가 이 박스에서 하는 유일한 일은 `mem_used_percent` 게시인데, 그건 Alloy의 내장 node_exporter가 더 상세하게(`node_memory_*` 전 항목) 대체한다.

그리고 **제거를 막는 의존성이 없다는 것을 확인했다.** `terraform/` 전체에 `cloudwatch_metric_alarm`이 없고, 스케일링 정책은 `ALBRequestCountPerTarget` 기반이라 이 지표를 쓰지 않는다. 소비자는 사람의 눈뿐이었다.

제거의 실익은 메모리다. [memory-map.md](../jvm-heap-sizing/memory-map.md) §2에서 **CloudWatch Agent는 RSS 130.6MB로 박스 최대 비-JVM 프로세스**였고, OS+에이전트 합 321MB가 기각선(300MB)을 넘겨 `-Xmx1024m`을 기각시킨 장본인이다.

> **단, 이것을 "규칙을 무르는 근거"로 쓰지 않는다.** 저장소 원칙은 사전 등록한 규칙을 사후에 완화하지 않는 것이다. 여기서 벌어지는 일은 **입력값(OS 실측)이 바뀌었으니 같은 규칙으로 다시 판정하는 것**이고, `-Xmx` 재판정 자체는 이 작업의 범위 밖이라 별도 이슈로 분리한다.

### 왜 CloudWatch 연결 자체는 남는가

**CloudWatch Agent를 지우는 것과 CloudWatch를 떠나는 것은 다르다.** ALB(5xx·타깃 응답시간), RDS(CPU·커넥션), ElastiCache 지표는 **AWS가 직접 게시**하므로 에이전트와 무관하게 CloudWatch에만 있다. 이걸 Grafana로 가져오는 경로도 Alloy 안에 있다(`prometheus.exporter.cloudwatch`, YACE 내장, **EC2 instance profile로 인증되므로 정적 키가 필요 없다** — [#122](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/122)의 방향과 정합).

다만 **이번 범위에 넣지 않는다.** 아래 참고.

## 이번에 하는 것과 하지 않는 것

**1단계만 한다** — 앱 JVM 지표 + 호스트 지표 + journald 로그.

| 단계 | 내용 | 이번 범위 |
|---|---|---|
| 1 | 앱 지표 + 호스트 지표 + 로그 | ✅ |
| 2 | `prometheus.exporter.cloudwatch` — RDS·ALB 인프라 지표 | ❌ 별도 이슈 |
| 3 | `prometheus.exporter.postgres` / `.redis` — DB·캐시 내부 지표 | ❌ 별도 이슈 |

**2·3단계를 미루는 이유는 시리즈 수와 귀속이다.** `postgres_exporter`는 테이블별 지표를 내므로 수백~수천 시리즈가 늘어 free 한도를 처음으로 압박한다. 그리고 컴포넌트 몇 줄 추가로 언제든 켤 수 있으므로(인스턴스 교체도 불필요) **전후 효과를 따로 측정할 수 있게 분리하는 편이 낫다.**

## 실측 — 앱이 노출하는 시리즈 수

이 숫자 하나가 후보 ①과 ②의 우열을 갈랐다. free tier 10,000 한도와 CloudWatch의 `$0.30 × N` 과금이 **동일한 입력값**을 쓰기 때문이다.

로컬에서 앱을 띄우고 `/actuator/prometheus`의 주석 아닌 줄을 셌다(2026-08-27).

| 시점 | 총 시리즈 |
|---|---|
| 부팅 직후 | 288 |
| k6 부하 후 (단일 엔드포인트 300요청) | 324 |
| **전체 엔드포인트 스윕 후 (47개 매핑)** | **471** |

구성은 **비-HTTP 고정분 321 + `http_server_requests` 계열 150**이다. 고정분은 JVM(메모리 영역·GC·스레드), HikariCP, Lettuce(30), 캐시(9), Logback 등 **트래픽과 무관하게 일정한 값**이다.

### 카디널리티 폭발이 구조적으로 없다

측정 중 확인한 두 가지가 이 방식의 안전성을 뒷받침한다.

**(가) `uri` 라벨이 경로 패턴으로 정규화된다.** k6가 course_id를 2~3000 무작위로 300번 쳤는데 시리즈는 하나만 생겼다.

```
http_server_requests_seconds_count{...,status="404",uri="/api/upload-courses/{uploadCourseId}"}
```

**시리즈 수가 트래픽 양이나 데이터 건수에 비례하지 않는다.**

**(나) 매핑 없는 요청은 `uri="UNKNOWN"` 하나로 뭉친다.** 인터넷에 노출된 서버가 받는 봇 스캔(`/wp-login.php`, `/.env` 등)이 시리즈를 무한히 늘리지 못한다. 운영 노출을 앞둔 상황에서 이것이 사실상 가장 큰 리스크였는데 해당 없음으로 확인됐다.

고유 `uri` 34개에 status를 넉넉히 6종(200/400/401/403/404/500)으로 잡아도 상한은 이렇다.

```
34 uri × 6 status × 3(count/sum/max) = 612  +  비-HTTP 321  ≈  930
```

**인스턴스 1대당 1,000 시리즈 미만**이고, ASG max 2대에 배포 시 이전 인스턴스 시리즈가 겹치는 것까지 감안해도 3,000 수준이다. free 한도의 30%다.

## 설계 결정과 대가

### 왜 토큰을 `password_file`로 주입하는가

Alloy의 `basic_auth`는 `password`와 `password_file`을 상호 배타로 받는다. 셋 중 하나를 골라야 했다.

| 방식 | 판단 |
|---|---|
| `password = sys.env("TOKEN")` | ❌ `/proc/<pid>/environ`에 남는다. 시크릿을 user-data에서 SSM으로 옮긴 것과 같은 이유로 피한다 |
| `local.file` + `is_secret = true` | △ 동작하지만 **`aws ssm get-parameter --output text`가 붙이는 끝 개행을 직접 지워야 한다.** 안 지우면 basic auth 헤더가 깨지는데, 그 실패는 "인증 실패"로만 보여 원인을 찾기 어렵다 |
| **`password_file`** | ✅ **채택.** 설정 파일에 값이 없고, 읽는 쪽(`prometheus/common`)이 `TrimSpace`를 하므로 **개행 함정이 구조적으로 사라진다** |

Grafana 문서는 `password_file`이 요청마다 파일을 읽는다고 경고하며 고빈도에서는 `local.file`을 권한다. **이 박스의 쓰기 빈도는 분당 수 회라 해당 없다.** 스크레이프 대상이 크게 늘면 그때 옮긴다.

### ⚠️ `loki.source.journal`에는 조용한 실패 모드가 있다

Alloy 소스의 이 컴포넌트는 빌드 태그 게이트 뒤에 있다.

```go
//go:build linux && cgo && promtail_journal_enabled
```

태그가 없으면 같은 디렉터리의 `journal_stub.go`가 대신 컴파일되는데, **컴포넌트를 정상 등록만 하고 아무것도 읽지 않는다.** 설정 검증도 통과하고 로그에 오류도 남지 않는다.

공식 리눅스 빌드는 이 태그를 켜므로(`GO_TAGS="embedalloyui promtail_journal_enabled"`) RPM은 안전하다. **그러나 "오류가 없다"로는 이것을 판정할 수 없다.** 그래서 판정을 메트릭 존재로 못 박는다 — 실제 구현만 등록하는 메트릭이 있다.

```bash
curl -s localhost:12345/metrics | grep loki_source_journal_target_lines_total
```

stub 빌드에는 이 메트릭이 아예 없다. 아래 P6이 이것을 본다.

### 왜 `instance` 라벨을 hostname으로 두는가

instance refresh는 `min_healthy_percentage = 100`이라 **교체 중 두 인스턴스가 몇 분간 공존한다.** 라벨을 고정 문자열로 두면 그 구간에 같은 시리즈로 두 값이 들어가 remote_write가 중복·역순 샘플로 거절한다. `constants.hostname`을 쓰면 구조적으로 갈린다.

대가는 교체마다 새 시리즈가 생기는 것인데, **활성 시리즈는 최근 창에 샘플이 있는 것만 세므로** 옛 인스턴스는 곧 빠진다.

### 왜 Alloy 버전을 핀하는가

핀하지 않으면 **같은 커밋을 다른 날 apply했을 때 다른 에이전트가 떠서 메모리 실측(P2)의 비교 대상이 사라진다.** `asg.tf`가 AMI를 `var.app_ami_id`로 핀할 수 있게 해 둔 것과 같은 이유다. `var.alloy_version`으로 뺀다.

### 왜 collector를 처음부터 깎지 않는가

`prometheus.exporter.unix`의 비활성 목록은 **Grafana 공식 Linux 통합의 값을 그대로 쓴다**(`ipvs`, `btrfs`, `infiniband`, `xfs`, `zfs`). 더 깎고 싶은 유혹이 있지만 **시리즈 수는 이번에 재는 값 중 하나다.** 기준선을 먼저 만들고, 축소는 아래 기각선이 발동할 때만 한다. **측정 후에 목록을 새로 만드는 것은 사후 완화다.**

## 사전 등록한 판정 기준

측정·검증 **전에** 못 박는다. 사후에 완화하지 않는다.

### 사전 등록 예측

| 가설 | 내용 | 판별자 |
|---|---|---|
| **H1 (설계자 예측)** | Alloy RSS는 CloudWatch Agent(130.6MB)보다 **작다.** Grafana 공식 자원 추정이 "활성 시리즈 100만당 11GiB"이므로 예상 1,100~1,500 시리즈의 기인분은 **약 15MiB**이고, 나머지는 Go 런타임 상주분이다. **예측 구간 60~110MB** | `ps -o rss= -C alloy` 최댓값 |
| H2 | 내장 exporter 3개 + remote_write WAL + 로그 파이프라인의 고정 오버헤드가 CWA를 넘는다 | 위와 동일 |

### 채택 기준 — 전부 만족

| # | 항목 | 통과 기준 |
|---|---|---|
| P1 | Alloy 생존 | 부팅 30분 뒤 `systemctl is-active alloy` = `active`, `NRestarts` = 0, `dmesg \| grep -i oom` 0건, cloud-init 로그에 `Alloy 구성 실패` 0건 |
| P2 | **Alloy 상주 메모리** | 아래 별도 판정 |
| P3 | 앱 지표 도달·정확 | `sum(jvm_memory_max_bytes{job="yourtrip-app",area="heap"} > 0)` = **805306368**. 도달과 값 정확성을 한 등식으로 잠근다([deploy/prod/README.md](../../../deploy/prod/README.md)의 확인 절차와 같은 항등식) |
| P4 | 호스트 지표 도달 | `node_memory_MemAvailable_bytes{job="yourtrip-host"}` 조회됨. **종전 `mem_used_percent`와 같은 값이 `1 - MemAvailable/MemTotal`로 계산됨을 한 번 확인** — CloudWatch Agent를 걷어내도 잃는 것이 없다는 증명이다 |
| P5 | journald **전체** 도달 | `{job="yourtrip-journal"}`이 반환되고 `unit` 라벨에 `yourtrip-app.service` **그리고 OS 유닛 3종 이상**(cloud-init·sshd·amazon-ssm-agent 등)이 함께 있다. **앱 유닛만 있으면 기각** |
| P6 | journal 지원이 실제로 컴파일돼 있다 | `loki_source_journal_target_lines_total`이 존재하고 **30초 간격 2회 관측에서 증가**한다. 위 "조용한 실패 모드" 참고 — 오류 부재로는 판정할 수 없다 |
| P7 | 활성 시리즈 | **3,000 미만**이고 free 10,000 대비 여유율 기록. job별(`yourtrip-app`/`yourtrip-host`/`alloy`)로 분해한다 |
| P8 | 전송 실패 0 | 30분 창에서 `prometheus_remote_write_wal_samples_appended_total` 증가, `..._failed_samples_total` = 0, `..._dropped_samples_total` = 0, `loki_write_dropped_entries_total` = 0 |
| P9 | actuator 외부 차단 유지 | ALB 경유 `/actuator/prometheus`와 `/actuator`가 **둘 다 403**([prod-infra-iac](../prod-infra-iac/README.md) P2 재확인). localhost 스크레이프가 이 규칙을 우회하지 않음을 증명한다 |
| P10 | CloudWatch Agent 부재 | `pgrep -a amazon-cloudwatch-agent` 0건 **그리고** `rpm -q` 미설치 **그리고** 역할에 `CloudWatchAgentServerPolicy` 미부착 |
| P11 | 앱 무영향 | 힙 상한 `805306368` 유지, 프로필 `prod`, **교체 중 1초 간격 폴링에서 200 아닌 응답 0건** |
| P12 | 철거 완결성 + 파라미터 생존 | destroy 후 ALB·RDS·ElastiCache·EC2·ASG 전부 비어 있음. **그리고** `/yourtrip/prod/grafana/*` 5개가 살아 있음 — 재apply 때 재입력이 없다는 주장의 실증 |

### 기각 기준 — 하나라도 해당하면

| # | 조건 | 조치 |
|---|---|---|
| R1 | P2가 최종 기각 구간 | CloudWatch Agent 복원 또는 수집 방식 재설계 |
| R2 | P5 실패(앱 유닛만) 또는 P6 실패 | 로그 수집 목표 미달 — 원인 규명 전까지 채택하지 않는다 |
| R3 | P11 위반 | 즉시 롤백 |
| R4 | P7이 10,000 초과 | free tier 전제가 깨진 것 — 설계 재검토 |

### P2 — 메모리 판정

**측정 방법을 [memory-map.md](../jvm-heap-sizing/memory-map.md) §2와 같게 한다** — 앱 정지 상태의 `MemTotal − MemAvailable`. 그래야 321MB와 직접 비교된다.

```
종전 OS+에이전트 실측            321.0 MB
  − amazon-cloudwatch-agent      130.6 MB
  ────────────────────────────────────
  = CWA 없는 잔여                190.4 MB
```

[jvm-heap-sizing/README.md](../jvm-heap-sizing/README.md) §5가 못 박은 선은 **"OS 실측이 300MB를 넘으면 1024 → 768로 내린다"** 였고 321MB로 발동했다. **같은 300MB 선을 그대로 쓴다** — 그러려면 Alloy RSS **X ≤ 109.6MB**여야 한다.

| 구간 | 판정 |
|---|---|
| **P2-A: X ≤ 109.6MB** | **채택.** 종전 기각선이 해제된다. ⚠️ **그러나 이번에 `-Xmx`를 올리지 않는다** — 힙 재산정은 별도의 사전 등록과 A/B가 필요한 독립 판단이고, 여기서 함께 바꾸면 변수가 둘이 되어 어느 쪽 효과인지 읽을 수 없다(#101이 A/B에서 `-Xss`를 함께 건드리지 않은 것과 같다). 해제 사실만 기록하고 **후속 이슈로 분리**한다 |
| **P2-B: 109.6 < X ≤ 130.6MB** | **채택.** 종전 CWA보다 무겁지 않다 = 예산 악화 없음. 300MB 선은 여전히 발동 상태이므로 `-Xmx768m`의 근거가 유지된다 |
| **P2-C: X > 130.6MB** | **1차 기각.** 아래 축소안을 **순서대로 1회** 적용하고 재측정. 그래도 초과하면 최종 기각(R1) |

**축소안 — 지금 등록한다. 측정 후에 새로 만들지 않는다.**

1. `disable_collectors`에 추가: `nfs`, `nfsd`, `mdadm`, `bonding`, `bcache`, `tapestats`, `hwmon`, `rapl`, `thermal_zone`, `edac`, `dmi` — t3.small/AL2023/EBS 단일 볼륨에 존재하지 않거나 상수인 장치 클래스
2. `prometheus.exporter.self`와 그 scrape 제거 — P2의 시계열 교차검증을 포기하는 대가
3. systemd 드롭인에 `Environment=GOMEMLIMIT=100MiB`

**측정 규약**

- 부팅 후 **30분 이상** 경과, 앱 기동 완료 상태에서 시작
- `ps -o rss= -C alloy`를 **1초 간격 300초** 폴링한 **최댓값**
- 같은 창의 `alloy_resources_process_resident_memory_bytes`와 **교차검증** — 두 경로가 5% 안에서 일치하지 않으면 폐기하고 재측정한다. [memory-map.md](../jvm-heap-sizing/memory-map.md) §7이 남긴 "1초 폴링은 첨두를 놓친다"는 한계를 이 항목이 보완한다
- OS 실측은 **앱을 정지시켜야 하므로 서비스가 끊긴다** → **destroy 직전에 딱 한 번만** 한다

## 한계

- **Alloy RSS를 재기 전에 이 문서를 쓴다.** P2가 미확정인 채로 방식을 확정했다. 시리즈 수·비용·프로세스 수라는 나머지 축에서 격차가 충분히 크다고 판단했기 때문이지만, **메모리 하나만으로 뒤집힐 여지는 남아 있다.**
- **`exception` 라벨을 자극하지 못했다.** 시리즈 실측 중 모든 응답이 `exception="none"`이었다. 실제 500이 발생하면 예외 클래스명마다 라벨 값이 생긴다. `GlobalExceptionHandler`가 `BusinessException`으로 뭉쳐 처리하므로 증가 폭은 제한적일 것으로 보이나 검증하지 않았다.
- **인증된 트래픽을 재현하지 않았다.** 스윕 47건 중 29건이 403이었다. JWT를 넣으면 같은 uri에 `status="200"` 조합이 추가되는데, 이는 상한 계산의 `× 6 status`에 이미 반영돼 있다.
- **시리즈 실측은 로컬 환경이다.** 운영에서 AI 코스 생성 등 클래스 로딩이 많은 경로를 타면 JVM 계열 지표가 조금 더 자랄 수 있다. 다만 그 계열은 고정분이라 자릿수가 바뀌지 않는다.
- **로그 볼륨을 추정하지 않았다.** free 50GB 대비 여유가 크다고 보지만 실측하지 않았다. journald 전체를 보내므로 부팅 로그가 인스턴스 교체마다 반복 유입된다.
- **보존이 14일이라 장기 추세를 볼 수 없다.** 이 방식의 장점으로 든 "인프라를 내려도 기록이 남는다"는 **14일 창 안에서만** 참이다. 그 뒤에는 지표·로그가 삭제되므로, **"지난달 대비 힙 사용량이 어떻게 변했나" 같은 질문에는 답할 수 없다.** 대조적으로 CloudWatch는 지표를 15개월 보존한다 — 보존만 놓고 보면 기각한 후보가 낫다. 비용(월 $141)과 자산 재사용을 함께 놓고 내린 결론이라는 점을 기록해 둔다.
  - **완화책은 이 저장소가 이미 쓰는 방법이다.** 측정 수치를 `verification.md`에 옮겨 적고 대시보드를 캡처한다. 부하테스트 결과를 문서로 남겨온 것과 같은 방식이며, 그것이 유일한 영구 기록이다.
  - 장기 추세가 실제로 필요해지면 그때 유료 전환(13개월 보존)이나 remote_write 이중화를 검토한다. **지금은 필요를 확인하지 못했으므로 하지 않는다.**

## 참고 문서

| 문서 | 이 작업에서 쓴 내용 |
|---|---|
| [jvm-heap-sizing/memory-map.md](../jvm-heap-sizing/memory-map.md) | OS+에이전트 321MB, CloudWatch Agent 130.6MB, 기각선. P2 판정의 근거 |
| [jvm-heap-sizing/README.md](../jvm-heap-sizing/README.md) | `-Xmx` 예산 산정식 |
| [prod-infra-iac/README.md](../prod-infra-iac/README.md) | ALB `/actuator` 차단(P2), 사전 등록 판정 기준의 형식 |
| [guide/monitoring.md](../../guide/monitoring.md) | 로컬 Prometheus·Grafana 구성, 커뮤니티 대시보드 11378 |
| [guide/cd.md](../../guide/cd.md) | 배포 경로가 terraform을 거치지 않는다는 사실(인프라 변경과의 구분) |
| [deploy/prod/README.md](../../../deploy/prod/README.md) | 인스턴스 형상을 저장소가 들고 있는 방식 |
