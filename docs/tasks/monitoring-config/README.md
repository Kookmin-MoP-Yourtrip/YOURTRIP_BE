# 운영 모니터링을 Grafana Cloud + Alloy로 구성한다 — 에이전트를 늘리지 않고 로그까지 얹는다

> [#121](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/121)의 설계·판정 기준 기록이다. 아래 판정 기준은 **구축 전에 못 박은 것**이고, 실제 검증 결과는 [verification.md](verification.md)에 있다.
>
> **왜 하는가**: 앱은 `/actuator/prometheus`를 이미 노출하는데 **운영에서 그것을 가져가는 경로가 없다.** Prometheus·Grafana는 로컬 부하테스트 전용이고, 운영에서 나오는 관측값은 CloudWatch Agent의 `mem_used_percent` 하나뿐이다. [memory-map.md](../jvm-heap-sizing/memory-map.md) §7이 남긴 한계("CloudWatch Agent 130MB를 측정 목적의 상수로 취급했다 — 운영에서 이 에이전트를 켤지는 이 문서의 범위 밖이다")가 이 작업의 착수 지점이다.
>
> **결론만 먼저** — **Grafana Cloud free tier + Alloy 단일 에이전트**를 채택한다. 앱이 노출하는 시리즈는 실측 **471개**로 free 한도 10,000의 5%에 불과하고, Alloy가 node_exporter를 내장하므로 **CloudWatch Agent를 제거하면 상주 프로세스가 늘지 않는다.** 로그(journald)까지 같은 에이전트가 처리한다.
>
> **확정하지 못한 것** — Alloy의 상주 RSS를 재기 전이다. 이 값이 CloudWatch Agent의 **130.6MB**보다 큰지 작은지가 [jvm-heap-sizing](../jvm-heap-sizing/README.md)의 `-Xmx` 예산에 직접 영향을 준다. 판정 구간은 아래 M7에 미리 등록해 둔다.

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

## 사전 등록한 판정 기준

측정·검증 **전에** 못 박는다. 사후에 완화하지 않는다.

| # | 항목 | 통과 기준 |
|---|---|---|
| M1 | 앱 지표 도달 | Grafana Cloud에서 `jvm_memory_used_bytes`가 조회된다 |
| M2 | 호스트 지표 도달 | `node_memory_MemAvailable_bytes`가 조회된다 |
| M3 | 로그 도달 | Loki에서 `yourtrip-app` 유닛 로그가 조회되고, OS 로그(cloud-init 등)도 함께 있다 |
| M4 | 시리즈 수 | 활성 시리즈 **10,000 미만**. 여유율도 함께 기록한다 |
| M5 | actuator 외부 차단 유지 | ALB 경유 `/actuator/prometheus`가 여전히 **403**([prod-infra-iac](../prod-infra-iac/README.md)의 P2 재확인) |
| M6 | CloudWatch Agent 부재 | 인스턴스에 `amazon-cloudwatch-agent` 프로세스가 없다 |
| M7 | **Alloy 상주 메모리** | 아래 별도 판정 |
| M8 | 앱 무영향 | 힙 상한이 여전히 `805306368`(768MiB), 활성 프로필 `prod` |

### M7 — 메모리 판정

현행 `-Xmx768m`은 **OS+에이전트 실측 321MB**를 전제로 산정됐고, 그중 CloudWatch Agent가 **130.6MB**였다. 이를 제거하고 Alloy(RSS = X)를 올리면 OS 항목은 `190 + X`가 된다.

| 구간 | 판정 | 근거 |
|---|---|---|
| **X ≤ 110MB** | 채택 + 부수 효과 | OS 실측이 300MB 미만이 되어 `-Xmx` 기각선이 해제된다 → **힙 재판정 여지**(별도 이슈) |
| **110 < X ≤ 130.6MB** | 채택 | 기존 예산 대비 악화 없음 |
| **X > 130.6MB** | 조건부 | CloudWatch Agent보다 무겁다는 뜻. `prometheus.exporter.unix`의 불필요한 collector를 끄고 재측정. 그래도 초과하면 예산을 다시 짜거나 방식을 재검토한다 |

**측정 방법**은 [memory-map.md](../jvm-heap-sizing/memory-map.md) §2와 같게 한다 — 앱 정지 상태의 `MemTotal - MemAvailable`, 그리고 `ps -o rss= -C alloy`. 같은 방식이어야 321MB와 직접 비교된다.

## 한계

- **Alloy RSS를 재기 전에 이 문서를 쓴다.** M7이 미확정인 채로 방식을 확정했다. 시리즈 수·비용·프로세스 수라는 나머지 축에서 격차가 충분히 크다고 판단했기 때문이지만, **메모리 하나만으로 뒤집힐 여지는 남아 있다.**
- **`exception` 라벨을 자극하지 못했다.** 시리즈 실측 중 모든 응답이 `exception="none"`이었다. 실제 500이 발생하면 예외 클래스명마다 라벨 값이 생긴다. `GlobalExceptionHandler`가 `BusinessException`으로 뭉쳐 처리하므로 증가 폭은 제한적일 것으로 보이나 검증하지 않았다.
- **인증된 트래픽을 재현하지 않았다.** 스윕 47건 중 29건이 403이었다. JWT를 넣으면 같은 uri에 `status="200"` 조합이 추가되는데, 이는 상한 계산의 `× 6 status`에 이미 반영돼 있다.
- **시리즈 실측은 로컬 환경이다.** 운영에서 AI 코스 생성 등 클래스 로딩이 많은 경로를 타면 JVM 계열 지표가 조금 더 자랄 수 있다. 다만 그 계열은 고정분이라 자릿수가 바뀌지 않는다.
- **로그 볼륨을 추정하지 않았다.** free 50GB 대비 여유가 크다고 보지만 실측하지 않았다. journald 전체를 보내므로 부팅 로그가 인스턴스 교체마다 반복 유입된다.

## 참고 문서

| 문서 | 이 작업에서 쓴 내용 |
|---|---|
| [jvm-heap-sizing/memory-map.md](../jvm-heap-sizing/memory-map.md) | OS+에이전트 321MB, CloudWatch Agent 130.6MB, 기각선. M7 판정의 근거 |
| [jvm-heap-sizing/README.md](../jvm-heap-sizing/README.md) | `-Xmx` 예산 산정식 |
| [prod-infra-iac/README.md](../prod-infra-iac/README.md) | ALB `/actuator` 차단(P2), 사전 등록 판정 기준의 형식 |
| [guide/monitoring.md](../../guide/monitoring.md) | 로컬 Prometheus·Grafana 구성, 커뮤니티 대시보드 11378 |
| [guide/cd.md](../../guide/cd.md) | 배포 경로가 terraform을 거치지 않는다는 사실(인프라 변경과의 구분) |
| [deploy/prod/README.md](../../../deploy/prod/README.md) | 인스턴스 형상을 저장소가 들고 있는 방식 |
