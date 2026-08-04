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
   - DB 쿼리 개수 감소를 입증할 때는 `application.yml`의 `spring.jpa.properties.hibernate.generate_statistics: true`로 설정합니다.
   - **서버의 극단적인 최대 TPS / 응답 속도 한계**를 측정할 때는 카운터 동기화 오버헤드를 없애기 위해 해당 옵션을 `false`로 끄고 k6를 실행하는 것을 권장합니다.

2. **HikariCP Pending 체크**
   - 부하 테스트 중 `/actuator/metrics/hikaricp.connections.pending` 값이 0 초과로 지속된다면, DB 커넥션 풀 크기(`max-lifetime`, `maximum-pool-size`)가 부족하거나 DB 쿼리 실행 시간이 너무 길어 스레드가 대기 중임을 의미합니다.
