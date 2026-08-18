# k6 + Actuator + Hibernate Statistics 부하 테스트 가이드

이 문서는 **k6(외부 부하 생성 및 API Performance 측정)**와 **Spring Boot Actuator + Hibernate Statistics(서버 내부 DB 쿼리 수 및 커넥션 모니터링)**를 조합하여 캐싱 전후의 성능과 DB 쿼리 감소 효과를 정교하게 검증하는 방법을 안내합니다.

---

## 1. k6 설치 방법 (Windows)

k6는 Go 언어로 작성된 독립 실행형 CLI 도구입니다. 아래 방법 중 하나로 설치합니다.

### 방법 A: Windows Package Manager (`winget`) - 권장
터미널(CMD 또는 PowerShell)에서 아래 명령어를 실행합니다.
```powershell
winget install k6 --source winget
```

### 방법 B: Chocolatey 사용
```powershell
choco install k6
```

### 설치 확인
터미널에서 버전 명령어를 통해 정상 설치를 확인합니다.
```powershell
k6 version
```

---

## 2. Actuator & Hibernate Statistics 주요 메트릭 엔드포인트

애플리케이션 실행 후 (`http://localhost:8080`), 다음 엔드포인트를 통해 서버 내부 지표를 실시간 조회할 수 있습니다.

| 메트릭 종류 | URL 엔드포인트 | 설명 |
| :--- | :--- | :--- |
| **SQL 쿼리 수** | `/actuator/metrics/hibernate.statements` | 애플리케이션 시작 후 실행된 **총 SQL 문장 수(Count)** |
| **활성 DB 커넥션 수** | `/actuator/metrics/hikaricp.connections.active` | 현재 DB 쿼리를 수행 중인 커넥션 개수 |
| **대기 커넥션 스레드 수** | `/actuator/metrics/hikaricp.connections.pending` | DB 커넥션 풀이 부족하여 대기 중인 요청 스레드 수 |
| **휴식 중인 커넥션 수** | `/actuator/metrics/hikaricp.connections.idle` | 커넥션 풀에서 놀고 있는 커넥션 개수 |
| **HTTP 요청 처리 수/지연**| `/actuator/metrics/http.server.requests` | HTTP 요청별 평균/최대 응답 속도 및 처리 건수 |

> **curl 명령어 예시:**
> ```bash
> curl http://localhost:8080/actuator/metrics/hibernate.statements
> ```

---

## 3. 캐싱 성능 (DB 쿼리 감소량) 검증 절차

부하 테스트 중 DB 쿼리가 실제 얼마나 감소했는지 측정하는 표준 절차입니다.

### 1단계: 부하 테스트 전 SQL 실행 횟수 수집 ($S_1$)
부하 테스트를 시작하기 직전, 아래 엔드포인트를 호출하여 `COUNT` 값을 기록합니다.
```bash
curl -s http://localhost:8080/actuator/metrics/hibernate.statements
```
*응답 예시:* `{"name":"hibernate.statements","measurements":[{"statistic":"COUNT","value":15.0}]}` $\rightarrow S_1 = 15$

### 2단계: k6 부하 테스트 실행 (예: 1,000건 요청)
k6 스크립트를 통해 대상 API로 1,000건의 요청을 발사합니다.

### 3단계: 부하 테스트 후 SQL 실행 횟수 수집 ($S_2$)
부하 테스트가 종료된 직후, 동일한 엔드포인트를 호출하여 `COUNT` 값을 기록합니다.
```bash
curl -s http://localhost:8080/actuator/metrics/hibernate.statements
```
*응답 예시:* `{"name":"hibernate.statements","measurements":[{"statistic":"COUNT","value":17.0}]}` $\rightarrow S_2 = 17$

### 4단계: 결과 비교 ($\Delta S = S_2 - S_1$)
* **캐시 미적용 / Cache Miss 시**: $\Delta S \approx 1,000$ (요청 1건당 DB 쿼리 1회 이상 발생)
* **캐시 적용 / Cache Hit 시**: $\Delta S \approx 0$ 또는 $1\sim2$ (콜드 미스 시 1~2회만 실행되고 나머지는 캐시에서 처리됨)

---

## 4. k6 부하 테스트 스크립트 작성 템플릿

k6 스크립트 파일(예: `script.js`)을 작성할 때 참고할 수 있는 표준 템플릿입니다.

```javascript
import http from 'k6/http';
import { check, sleep } from 'k6';

// 1. 부하 조건 및 성능 목표(Thresholds) 설정
export const options = {
  stages: [
    { duration: '10s', target: 10 }, // 10초 동안 동시 사용자 10명으로 유입 (Ramp-up)
    { duration: '30s', target: 50 }, // 30초 동안 동시 사용자 50명 유지
    { duration: '10s', target: 0 },  // 10초 동안 부하 종료 (Ramp-down)
  ],
  thresholds: {
    http_req_failed: ['rate<0.01'],   // 에러율 1% 미만 유지
    http_req_duration: ['p(95)<500'], // 95%의 요청이 500ms 이내 응답
  },
};

// 2. 테스트할 API 요청 시나리오
export default function () {
  const url = 'http://localhost:8080/api/upload-courses/popular';
  const params = {
    headers: {
      'Content-Type': 'application.json',
    },
  };

  const res = http.get(url, params);

  // 3. 응답 검증 (HTTP status 200 OK)
  check(res, {
    'is status 200': (r) => r.status === 200,
  });

  sleep(0.1); // 요청 간 0.1초 휴식
}
```

### 실행 명령어

#### 1) 일반 실행
```bash
k6 run script.js
```

#### 2) k6 웹 대시보드 및 HTML 리포트 생성 실행 (강력 추천 ⭐)
k6 내장 웹 대시보드를 켜면 부하 테스트 중 브라우저(`http://127.0.0.1:5665`)에서 실시간 차트를 볼 수 있으며, 테스트 종료 후 단독 실행 가능한 HTML 보고서(`report.html`)를 자동 저장할 수 있습니다.

```bash
# PowerShell
$env:K6_WEB_DASHBOARD="true"; $env:K6_WEB_DASHBOARD_EXPORT="report.html"; k6 run script.js

# CMD
set K6_WEB_DASHBOARD=true && set K6_WEB_DASHBOARD_EXPORT=report.html && k6 run script.js

# 또는 k6 CLI 옵션으로 바로 지정 (가장 간편)
k6 run --out web-dashboard=export=report.html script.js
```

---

## 5. ⚠️ 주의 사항 (포트폴리오 팁)

1. **Hibernate Statistics 옵션 관리**
   - `spring.jpa.properties.hibernate.generate_statistics`는 `application.yml`에 **상시 켜져 있습니다.** 측정할 때마다 켰다 되돌리면 세션 간에 이 축이 통제되지 않은 변수가 되기 때문입니다. 따라서 `hibernate_statements_total`은 별도 조치 없이 항상 나옵니다.
   - 이 값이 실제로 메트릭까지 이어지는지는 `HibernateMetricsRegistrationTest`가 검증합니다. 과거에 `hibernate-micrometer` 의존성이 없어 **설정을 켜도 메트릭만 조용히 사라지는** 상태였던 전례가 있습니다([TASK-CLOUDFRONT.md](../tasks/TASK-CLOUDFRONT.md) 참고).
   - **서버의 극단적인 최대 TPS 한계**를 재느라 이 오버헤드까지 배제하고 싶다면, 코드를 고치지 말고 `/opt/app/.env`에 아래를 넣어 런타임에만 끕니다. 맵 키의 언더스코어 때문에 일반 환경변수 relaxed binding으로는 `generate.statistics`로 잘못 매핑될 수 있어, 키를 그대로 보존하는 `SPRING_APPLICATION_JSON`을 씁니다.
     ```
     SPRING_APPLICATION_JSON={"spring.jpa.properties.hibernate.generate_statistics":"false"}
     ```
   - **통계 단독의 오버헤드는 아직 실측하지 않았습니다.** 상시 ON으로 둔 근거는 JFR 기반 선행 판정([PRESIGN-BOTTLENECK.md](../tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK.md) 경쟁 가설 ②)으로, 로깅 카테고리 **전체**가 1.3~1.6%로 임계값(15%)에 한참 못 미쳤다는 것입니다. 통계만 떼어낸 값은 아니므로, 최대 TPS를 소수점까지 다투는 측정에서는 위 방법으로 꺼서 비교하십시오.

2. **HikariCP Pending 체크**
   - 부하 테스트 중 `/actuator/metrics/hikaricp.connections.pending` 값이 0 초과로 지속된다면, DB 커넥션 풀 크기(`max-lifetime`, `maximum-pool-size`)가 부족하거나 DB 쿼리 실행 시간이 너무 길어 스레드가 대기 중임을 의미합니다.

---

## 6. Ramping 프로파일 — 포화점(knee) 규명

`test/presigned-url-bottleneck`에서 도입. **고정 동시성 두 점(예: 50, 200)만 측정하면 그 사이 어디서 처리량이 꺾이는지 알 수 없다** — 동시성 50과 200에서 TPS가 비슷하면 "이미 50에서 포화였다"는 뜻인지, "200까지도 여유가 있다"는 뜻인지 구분이 안 된다. VU를 서서히 올리며(`ramping-vus` executor) TPS가 꺾이는 지점(knee)과, 그 시점에 CPU/HikariCP/Tomcat 중 무엇이 한계에 닿아 있는지를 함께 보면 병목의 정체를 판정할 수 있다.

```javascript
export const options = {
  scenarios: {
    ramping: {
      executor: 'ramping-vus',
      startVUs: 1,
      stages: [
        { duration: '60s', target: 5 },
        { duration: '60s', target: 10 },
        { duration: '60s', target: 20 },
        { duration: '90s', target: 50 },
        { duration: '90s', target: 100 },
        { duration: '90s', target: 200 },
      ],
      gracefulRampDown: '15s',
    },
  },
};
```

실전 예시는 [`scripts/k6/detail-ramping.js`](../../scripts/k6/detail-ramping.js) 참고. 실행 중에는 Grafana의 **Presign CPU Bottleneck** 대시보드(§4 참고)를 "Last 15 minutes" + 5초 auto-refresh로 열어두고, k6가 보고하는 처리량 저하 시점과 `process_cpu_usage`/`hikaricp_connections_pending`이 한계에 닿는 시점을 나란히 관찰한다.

## 7. JFR(Java Flight Recorder) CPU 프로파일링

end-to-end TPS/latency만으로는 "무엇이 CPU를 쓰고 있는지"까지는 알 수 없다. JDK 내장 JFR로 실행 중인 애플리케이션의 CPU 샘플을 직접 뜬다(별도 프로파일러 설치 불필요).

```bash
# 1. JFR을 켠 채로 fat jar 실행 (java -jar여야 실제 배포 방식과 동일한 클래스로딩 경로를 탄다)
./gradlew bootJar
java -XX:StartFlightRecording=name=bench,settings=profile \
     -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints \
     -jar build/libs/yourtrip-0.0.1-SNAPSHOT.jar

# 2. k6로 부하를 건다 (§4/§6)

# 3. 앱이 살아있는 상태에서 명시적으로 덤프한다 — dumponexit=true와 강제 종료(taskkill /F,
#    kill -9)를 같이 쓰면 셧다운 훅이 생략되어 0바이트 파일이 남는다(실측으로 확인된 함정).
jcmd <PID> JFR.dump filename=results/dump.jfr

# 4. jdk.ExecutionSample 이벤트를 텍스트로 뽑고, 전용 파서로 관심 패키지별 CPU 샘플 비율을 집계한다
jfr print --events jdk.ExecutionSample --stack-depth 64 results/dump.jfr \
  | node scripts/jfr/parse-execution-samples.mjs
```

`settings=profile`은 기본(`default`) 대비 샘플링 주기가 촘촘해(10ms) 메서드 단위 CPU 귀속에 적합하다. `parse-execution-samples.mjs`는 (1) 최상위(leaf) 프레임 Top 30과 (2) 지정한 패키지(AWS SDK 서명, crypto, Hibernate, logback, HikariCP)가 스택 어디에라도 등장하는 샘플의 비율을 출력한다 — 실측 사례와 해석은 [PRESIGN-BOTTLENECK.md](../tasks/connection-pool-bottleneck/PRESIGN-BOTTLENECK.md) 참고.
