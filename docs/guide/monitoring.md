# 모니터링 가이드 — 로컬(네이티브)과 운영(Grafana Cloud)

이 저장소의 모니터링은 **수집기가 다른 두 계통**이다. 앱이 `/actuator/prometheus`로 지표를 내는 것만 같고, **그 뒤는 완전히 다르다.**

| | 1부 — 로컬 (§1~§5) | 2부 — 운영 (§6) |
|---|---|---|
| 수집기 | 로컬에 띄운 Prometheus | 인스턴스의 **Grafana Alloy** |
| 저장소 | 로컬 컨테이너 | **Grafana Cloud** (free tier) |
| 호스트 지표 | 없음 | Alloy 내장 node_exporter |
| 로그 | 없음(터미널에서 본다) | Loki (journald 전체) |
| 용도 | **부하테스트 판정** — 붙였다 뗐다 하는 실험 도구 | **운영 관측** — 인프라를 destroy해도 14일 남는다 |

**둘을 섞지 않는다.** 로컬 스택은 부하테스트 arm 전환(`$arm` 변수)을 전제로 만들어져 있어 운영에 그대로 쓸 수 없고, 반대로 운영 대시보드는 `job="yourtrip-app"`/`"yourtrip-host"`에 묶여 있어 로컬에서 뜨지 않는다.

아래 1부는 로컬 스택, 2부는 운영 인스턴스를 다룬다. 설계 근거는 [tasks/monitoring-config/](../tasks/monitoring-config/README.md)에 있다.

---

# 1부 — 로컬 (Prometheus & Grafana)

이 부분은 Spring Boot Actuator와 **Prometheus(메트릭 수집 DB)** 및 **Grafana(시각화 대시보드)**를 연동하여 애플리케이션 및 DB 상태를 실시간 모니터링하는 방법을 다룹니다.

**네이티브 실행이 기준이다.** 셋 다 같은 호스트에서 돌므로 서로를 `localhost`로 찾는다 — 도커에서 쓰던 `host.docker.internal`(앱을 가리키는 별칭)과 `prometheus`(서비스명)는 **컨테이너 밖에 존재하지 않아** 그대로 두면 스크레이프와 데이터소스가 통째로 실패한다. `docker-compose.yml`은 도커 경로를 쓰는 사람을 위해 남겨 뒀고, 그쪽으로 돌린다면 두 주소를 되돌려야 한다.

---

## 1. 모니터링 아키텍처 구조

```
[ Spring Boot (localhost:8080) ]
       │ /actuator/prometheus (Micrometer 메트릭 노출)
       ▼ (5초 간격 Scraping)
[ Prometheus (localhost:9090) ]
       ▲ PromQL 쿼리 조회
[ Grafana (localhost:3000) ]
```

---

## 2. 기동 방법

**Redis** — 네이티브로 띄우고 `.env`의 `REDIS_PORT`를 기본 포트 `6379`로 둔다(도커 매핑값은 `6479`였다).

```bash
redis-cli -p 6379 ping
```

> 도커는 `--maxmemory 256mb --maxmemory-policy allkeys-lru`를 실행 인자로 줬는데 네이티브는 그 설정을 스스로 갖지 않는다. 기본값 `noeviction`이면 메모리가 차는 순간 캐시 **쓰기가 실패**한다. 로컬에서 256MB를 넘길 일은 사실상 없고 실패해도 `RedisCacheErrorHandler`가 DB 폴백으로 흡수하지만, 맞추려면 `redis-cli CONFIG SET maxmemory 256mb` / `CONFIG SET maxmemory-policy allkeys-lru`.

**Prometheus** — 압축을 푼 디렉터리에서 이 저장소의 설정 파일을 지정해 실행한다. `--config.file`을 빼면 번들된 기본 설정으로 떠서 **앱을 스크레이프하지 않는다.**

```bash
prometheus.exe --config.file=<레포 루트>/prometheus.yml
```

**Grafana** — Windows installer(.msi)로 설치하면 서비스로 등록돼 자동 기동한다.

### 기동 상태 확인
* **Redis**: `localhost:6379`
* **Prometheus**: `http://localhost:9090`
* **Grafana**: `http://localhost:3000`

---

## 3. Prometheus (프로메테우스) 사용법

### 1) 프로메테우스 접속
브라우저에서 `http://localhost:9090`으로 접속합니다.

### 2) 넥서스 / 메트릭 수집 연결 확인 (Target Check)
1. 상단 메뉴에서 `Status` $\rightarrow$ `Targets` 클릭합니다.
2. `presign`(8080)/`cloudfront`(8081) 항목이 **`UP`** 상태인지 확인합니다([prometheus.yml](../../prometheus.yml) 참고 — job 이름은 `test/presigned-url-bottleneck`에서 A/B 비교용으로 이렇게 재명명됐다).
   * `UP`: 해당 포트의 Spring Boot 백엔드가 실행 중이고 메트릭을 수집 중임.
   * `DOWN`: 그 arm의 서버가 꺼져 있음 — 두 arm을 항상 순차 실행하므로 한쪽이 `DOWN`인 것은 정상이다.

### 3) 주요 PromQL 쿼리 예시 (Prometheus 웹 UI 검색창)

* **초당 SQL 쿼리 발생 비율 (Throughput)**:
  ```promql
  rate(hibernate_statements_total[1m])
  ```
* **활성 DB 커넥션 수 (HikariCP Active)**:
  ```promql
  hikaricp_connections_active{pool="HikariPool-1"}
  ```
* **대기 중인 DB 커넥션 요청 수 (HikariCP Pending)**:
  ```promql
  hikaricp_connections_pending{pool="HikariPool-1"}
  ```
* **JVM Heap 메모리 사용량 (Bytes)**:
  ```promql
  jvm_memory_used_bytes{area="heap"}
  ```

---

## 4. Grafana (그라파나) 대시보드 설정 방법

### 1) 그라파나 접속 및 로그인
* **URL**: `http://localhost:3000`
* **기본 아이디**: `admin`
* **기본 비밀번호**: `admin` (첫 로그인 시 비밀번호 변경 화면이 나오면 `Skip` 또는 변경)

---

### 2) Prometheus 데이터 소스(Data Source) 연동

1. 좌측 메뉴의 **Connections** $\rightarrow$ **Data Sources** 클릭
2. **Add data source** 버튼 클릭 후 **Prometheus** 선택
3. Connection URL 설정:
   * **Prometheus server URL**: `http://localhost:9090` (도커로 돌린다면 컨테이너 간 통신이라 `http://prometheus:9090`)
4. 맨 아래 **Save & test** 버튼 클릭 $\rightarrow$ `"Successfully queried the Prometheus API"` 초록색 문구가 나오면 정상 연결 완료!

---

### 3) 완성형 Spring Boot 대시보드 가져오기 (Import Dashboard)

Grafana 커뮤니티에 공개된 완성도 높은 스프링 부트 대시보드를 ID만 입력하여 1초 만에 로드할 수 있습니다.

1. 좌측 메뉴의 **Dashboards** $\rightarrow$ 우측 상단 **New** 버튼 $\rightarrow$ **Import** 클릭
2. **Find and import dashboards...** 입력창에 대시보드 ID **`11378`** 입력 후 **Load** 클릭
   *(참고: `11378`은 "JVM (Micrometer)" 대시보드 공식 ID입니다)*
3. 하단 **Prometheus** 데이터 소스 선택 드롭다운에서 방금 등록한 `Prometheus` 선택
4. **Import** 버튼 클릭!

🎉 **결과**: CPU, JVM 힙 메모리, Garbage Collection 타임, HTTP 요청 처리 속도, HikariCP 커넥션 풀 현황이 일목요연한 대시보드로 시각화됩니다.

> **미리 만들어 둔 대시보드도 있다**: `test/presigned-url-bottleneck`이 `scripts/grafana/provisioning`에 **Presign CPU Bottleneck** 대시보드를 남겨 뒀다. 도커로 돌리면 `docker-compose.yml`이 그 디렉터리를 볼륨 마운트해 기동 즉시 **Dashboards → Bottleneck Test 폴더**에 준비되지만, **네이티브에서는 자동으로 실리지 않는다** — Grafana의 대시보드 화면에서 `presign-bottleneck.json`을 수동 import 하거나, 설치 경로의 `conf/provisioning/` 아래로 복사한다. CPU 사용률/HikariCP 커넥션 점유·대기/Tomcat 스레드/GC/로그 발생률/Hibernate 쿼리 실행률 8패널이며, `$arm` 변수로 `presign`/`cloudfront` job을 전환해 본다. 자세한 내용은 [PRESIGN-BOTTLENECK.md](../tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK.md) 참고.

### AI 코스 — 슬롯 결핍 관측 대시보드 (#149)

[`scripts/grafana/dashboards/ai-course-slot-vacancy.json`](../../scripts/grafana/dashboards/ai-course-slot-vacancy.json)을
**Dashboards → New → Import**에서 붙여넣고, `Prometheus` 변수에 위에서 만든 데이터소스를 고른다.

패널은 세 구역이다.

| 구역 | 무엇을 보나 |
|---|---|
| **결핍** | 빈 슬롯 합계 · **사유별**(무엇을 고쳐야 하는가) · **슬롯 타입별**(저녁이 얼마나 자주 빠지는가) · 발생 추이 |
| **보정** | 출처별 `hit` vs 중복 폐기 vs **실제 배치**(뺄셈이 표로 보인다) · 그라운딩 결말 분포 |
| **교차 검증** | `no_candidate == unfilled` · `채운 슬롯 + 공석 = 전체`. **차이값을 표시하고 0이 아니면 배경이 빨개진다** |

**아무 요청도 없는 상태에서 "No data"가 아니라 0이 떠야 정상이다** — 발생 가능한 태그 조합을 기동
시점에 0으로 등록하기 때문이고(`AiCourseMetrics`), 그래야 "결핍이 없다"와 "그 조합을 한 번도 만들지
않았다"가 구분된다.

**데이터소스 UID를 JSON에 박지 않았다.** 로컬 스택은 provisioning으로 UID를 고정할 수 있지만, 이
대시보드가 참조할 UID가 하필 `prometheus-presign-bottleneck`(병목 테스트가 만든 이름)이라 의미가
어긋난다. 운영 대시보드와 같이 `ds_prom` **데이터소스 변수**로 뺐다.

---

## 5. 부하 테스트 시 추천 모니터링 시나리오

1. **Spring Boot 실행**: `./gradlew bootRun`
2. **Grafana 접속**: `http://localhost:3000`(admin/admin)에서 대시보드 오픈
3. **k6 부하 테스트 실행**: `scripts/k6/popular-courses-test.js`는 실제로 존재한 적 없는 경로였다(문서만 앞서 나갔던 흔적) — 실재하는 스크립트는 [`scripts/k6/detail-fixed.js`](../../scripts/k6/detail-fixed.js)(고정 동시성)와 [`scripts/k6/detail-ramping.js`](../../scripts/k6/detail-ramping.js)(포화점 탐색용 ramping)다. 자세한 사용법은 [PRESIGN-BOTTLENECK.md](../tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK.md)의 "재현 방법" 참고. 예:
   ```bash
   k6 run -e DOMAIN=uploadcourse -e MODE=pool -e CONCURRENCY=50 scripts/k6/detail-fixed.js
   ```
4. **대시보드 실시간 관찰**:
   - 캐시 미적용 시: `hikaricp_connections_active`가 10(최대치)까지 상승하고 `pending` 커넥션이 생기면서 latency가 증가하는 모습 포착
   - 캐시 적용 시: 동일한 k6 부하에도 `hikaricp_connections_active`가 거의 0~1에 머물고 응답 시간이 일정하게 유지되는 모습 포착 및 캡처

> 📸 **포트폴리오 팁**: k6 실행 중 Grafana 대시보드의 캐시 적용 전/후 쿼리 그래프를 캡처하여 README 및 트러블슈팅 문서에 이미지로 첨부하면 훌륭한 시각적 근거가 됩니다!

---

# 2부 — 운영 (Grafana Cloud + Alloy)

## 6. 운영 서버의 지표·로그를 보는 법

### 6-1. 구조

```
[ Spring Boot (인스턴스:8080) ]        [ 커널 /proc, /sys ]        [ journald ]
        │ /actuator/prometheus                │                          │
        └────────────┬────────────────────────┴──────────────────────────┘
                     ▼  (localhost 직행 — ALB를 지나지 않는다)
            [ Grafana Alloy (같은 인스턴스, 프로세스 1개) ]
                     │ remote_write (지표)   │ push (로그)
                     ▼                       ▼
        [ Grafana Cloud Prometheus ]   [ Grafana Cloud Loki ]
```

**에이전트가 하나뿐인 것이 이 구성의 핵심이다.** Alloy가 `prometheus.exporter.unix`로 node_exporter를 내장하고 `loki.source.journal`로 로그까지 읽으므로, 종전의 CloudWatch Agent를 걷어내면 **상주 프로세스가 늘지 않는다.** 왜 node_exporter+Promtail 3-프로세스 구성 대신 이 방식인지는 [monitoring-config/README.md](../tasks/monitoring-config/README.md)에 있다.

**ALB의 `/actuator` 403 차단과 충돌하지 않는다.** Alloy는 인스턴스 안에서 `localhost:8080`으로 직접 붙으므로 애초에 ALB를 지나지 않는다. 차단을 우회하는 것이 아니다 — 판정 기준 P9가 이 사실을 확인한다.

### 6-2. 무엇이 수집되나

| job | 내용 | 주기 |
|---|---|---|
| `yourtrip-app` | Micrometer 전량 — JVM(힙·GC·스레드·클래스), HTTP, HikariCP, Lettuce, 캐시, Logback | 15s |
| `yourtrip-host` | node_exporter — CPU(mode별), 메모리, 디스크, 네트워크, load | 60s |
| `yourtrip-journal` | journald **전체** (앱 유닛만이 아니라 cloud-init·sshd·ssm-agent 포함) | 스트리밍 |

`scrape_interval`이 서로 다른 것은 의도다 — 커뮤니티 대시보드가 짧은 `rate` 창을 쓰므로 앱 지표를 60s로 두면 패널이 비어 보인다.

### 6-3. 인스턴스에 놓이는 것

전부 [user-data](../../terraform/prod/templates/app-user-data.sh.tpl)가 부팅 시 만든다. **손으로 넣는 파일이 없다.**

| 경로 | 내용 | 출처 |
|---|---|---|
| `/etc/alloy/config.alloy` | 파이프라인 정의 | [deploy/prod/config.alloy](../../deploy/prod/config.alloy) (정본) |
| `/etc/alloy/endpoints.env` | Prometheus/Loki URL·username | SSM `/yourtrip/prod/grafana/*` |
| `/etc/alloy/grafana-cloud.token` | 접속 토큰 (0640 root:alloy) | SSM `/yourtrip/prod/grafana/token` |
| `/etc/systemd/system/alloy.service.d/10-yourtrip.conf` | EnvironmentFile + GOMEMLIMIT | user-data |

**토큰이 설정 파일에도 환경변수에도 없다.** `password_file`로 읽으므로 `/proc/<pid>/environ`에 남지 않는다. 이 선택의 근거와 대가는 monitoring-config README의 "설계 결정과 대가" 참고.

### 6-4. Grafana Cloud 접속과 대시보드

1. [grafana.com](https://grafana.com/orgs) → 스택 → **Launch** 로 Grafana에 들어간다
2. **Dashboards → New → Import → Upload JSON file** 에 [scripts/grafana/dashboards/prod/yourtrip-prod-overview.json](../../scripts/grafana/dashboards/prod/yourtrip-prod-overview.json) 을 올린다
3. 상단에서 **Prometheus / Loki 데이터소스**를 고르고, `instance` 변수에서 볼 인스턴스를 고른다

> **데이터소스 UID를 JSON에 박지 않은 이유**: Grafana Cloud는 스택마다 UID가 다르다. 로컬 스택처럼 provisioning으로 UID를 고정할 수 없으므로 `ds_prom`/`ds_loki` **데이터소스 변수**로 뺐다. 이 저장소를 clone한 사람이 자기 스택에 그대로 올릴 수 있다.

> **`instance` 변수가 필요한 이유**: 라벨이 `constants.hostname`이라 **인스턴스가 교체될 때마다 값이 바뀐다.** 고정 문자열로 두면 instance refresh 중 두 인스턴스가 공존하는 구간에 같은 시계열로 두 값이 들어가 remote_write가 거절한다.

### 6-5. 인스턴스에서 직접 확인하기

```bash
aws ssm start-session --target <instance-id>
```

```bash
systemctl is-active alloy && systemctl show -p NRestarts --value alloy
curl -s localhost:12345/metrics | grep -c loki_source_journal_target_lines_total
curl -s localhost:12345/metrics | grep -E 'samples_(failed|dropped)_total|loki_write_dropped'
```

- 두 번째 줄이 **0이면 로그가 조용히 안 걷히고 있다.** `loki.source.journal`은 빌드 태그(`promtail_journal_enabled`) 뒤에 있어서, 태그 없이 빌드되면 stub이 **오류 없이** 아무것도 읽지 않는다. **오류 부재로는 판정할 수 없다.**
- 박스가 한가하면 이 카운터가 안 움직이는 것이 정상이다. 살아 있는지 보려면 `logger -t check "hello"` 로 줄을 만들고 다시 센다.

### 6-6. 보존은 14일이다

free tier의 보존 기간이 14일이라, **"인프라를 내려도 기록이 남는다"는 장점은 14일 창 안에서만 참이다.** Grafana Cloud는 **측정 중에 보는 창**이고 **남기는 곳은 이 저장소**다 — 수치는 [verification.md](../tasks/monitoring-config/verification.md)로 옮겨 적는다. 부하테스트 결과를 문서로 남겨온 것과 같은 방식이다.
