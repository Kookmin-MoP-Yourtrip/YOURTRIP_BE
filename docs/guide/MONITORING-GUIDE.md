# Prometheus & Grafana 모니터링 구축 및 사용 가이드

이 문서는 Spring Boot Actuator와 Docker 기반 **Prometheus(메트릭 수집 DB)** 및 **Grafana(시각화 대시보드)**를 연동하여 애플리케이션 및 DB 상태를 실시간 모니터링하는 방법을 다룹니다.

---

## 1. 모니터링 아키텍처 구조

```
[ Spring Boot (Host OS:8080) ] 
       │ /actuator/prometheus (Micrometer 메트릭 노출)
       ▼ (5초 간격 Scraping)
[ Prometheus (Docker Container:9090) ]
       ▲ PromQL 쿼리 조회
[ Grafana (Docker Container:3000) ]
```

---

## 2. 모니터링 컨테이너 기동 방법

프로젝트 루트 디렉토리에서 Docker Compose로 Prometheus와 Grafana를 기동합니다.

```bash
docker compose up -d
```

### 기동 상태 확인
* **Redis**: `localhost:6479`
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
   * **Prometheus server URL**: `http://prometheus:9090` (도커 내부 네트워킹 사용)
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

> **자동 provisioning 대시보드도 있다**: `test/presigned-url-bottleneck`에서 `docker-compose.yml`의 `grafana` 서비스에 `scripts/grafana/provisioning`을 볼륨 마운트해, `docker compose up -d` 시점에 **Dashboards → Bottleneck Test 폴더 → Presign CPU Bottleneck**이 로그인 직후부터 준비돼 있다(수동 import 불필요). CPU 사용률/HikariCP 커넥션 점유·대기/Tomcat 스레드/GC/로그 발생률/Hibernate 쿼리 실행률 8패널이며, `$arm` 변수로 `presign`/`cloudfront` job을 전환해 본다. 자세한 내용은 [TASK-PRESIGN-BOTTLENECK.md](../tasks/TASK-PRESIGN-BOTTLENECK.md) 참고.

---

## 5. 부하 테스트 시 추천 모니터링 시나리오

1. **Spring Boot 실행**: `./gradlew bootRun`
2. **Grafana 접속**: `http://localhost:3000`(admin/admin)에서 대시보드 오픈
3. **k6 부하 테스트 실행**: `scripts/k6/popular-courses-test.js`는 실제로 존재한 적 없는 경로였다(문서만 앞서 나갔던 흔적) — 실재하는 스크립트는 [`scripts/k6/detail-fixed.js`](../../scripts/k6/detail-fixed.js)(고정 동시성)와 [`scripts/k6/detail-ramping.js`](../../scripts/k6/detail-ramping.js)(포화점 탐색용 ramping)다. 자세한 사용법은 [TASK-PRESIGN-BOTTLENECK.md](../tasks/TASK-PRESIGN-BOTTLENECK.md)의 "재현 방법" 참고. 예:
   ```bash
   k6 run -e DOMAIN=uploadcourse -e MODE=pool -e CONCURRENCY=50 scripts/k6/detail-fixed.js
   ```
4. **대시보드 실시간 관찰**:
   - 캐시 미적용 시: `hikaricp_connections_active`가 10(최대치)까지 상승하고 `pending` 커넥션이 생기면서 latency가 증가하는 모습 포착
   - 캐시 적용 시: 동일한 k6 부하에도 `hikaricp_connections_active`가 거의 0~1에 머물고 응답 시간이 일정하게 유지되는 모습 포착 및 캡처

> 📸 **포트폴리오 팁**: k6 실행 중 Grafana 대시보드의 캐시 적용 전/후 쿼리 그래프를 캡처하여 README 및 트러블슈팅 문서에 이미지로 첨부하면 훌륭한 시각적 근거가 됩니다!
