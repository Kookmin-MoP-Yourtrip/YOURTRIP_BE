# Tomcat `maxThreads` 축소 — 단일 Lettuce I/O 스레드의 경합 상대를 줄인다

> [#88](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/88)의 설계·진행 기록이다. 실측 결과와 판정은 [ec2-measurement.md](ec2-measurement.md)에 있다.
>
> **왜 하는가**: [redis-io-bottleneck.md](../cache-effect-measurement/redis-io-bottleneck.md)가 밝힌 A2(현재 운영)의 포화 주체는 앱 밖이 아니라 **앱 안의 단일 Lettuce I/O 스레드**였다. 앱↔Redis TCP 연결이 1개(`shareNativeConnection` 기본값 `true`)라 모든 Redis I/O가 netty 이벤트루프 스레드 하나를 통과하는데, 그 스레드가 vCPU 2개를 놓고 Tomcat 워커 129개와 경합해 **런큐에서 90.8%를 대기**했다. CPU 사용량은 9.3%뿐이라 `process_cpu_usage`·`tomcat_threads_busy`·`pending` 어디에도 잡히지 않고 **Redis 명령 지연(1.2 → 15.2ms, 응답의 52~68%)** 의 형태로만 나타났다. 이 작업은 그 **경합 상대(워커 200개)를 줄이는** 개선이다.
>
> #87(ElastiCache AZ 정렬)이 먼저 머지돼 왕복 바닥값이 1.30 → 0.33ms로 내려간 상태이므로, 이번 측정은 **정렬 이후 환경**에서 "경합을 줄이면 남은 I/O 지연·처리량이 얼마나 더 개선되는가"를 잰다.

## 사이징 원칙 — 왜 200이 아니고, 왜 3도 아닌가

Tomcat 기본값 200은 CPU와 무관한 범용 상수다(블로킹 I/O·JSP 시대부터 써온 값이고, Spring Boot도 코어 수에 맞춰 조정하지 않는다). 정해진 권장표는 없고 세 축으로 정한다.

| 축 | 원칙 | 이 앱에 대입 (vCPU 2) |
|---|---|---|
| **CPU 대비** — Goetz, *Java Concurrency in Practice*; HikariCP 위키가 풀 사이징에 인용하는 공식 | `N = cores × (1 + 대기시간 / 계산시간)` | 인기 코스 히트 경로: Redis 대기 0.33ms vs 계산 ~1.5ms → `2 × 1.2 ≈ 3`. 상세 조회(DB): 대기 5~8ms vs 계산 1~2ms → `2 × 5 ≈ 10` |
| **다운스트림 용량** | 후단이 소화 못 하는 워커는 어차피 후단 큐(`hikaricp_connections_pending`)에서 잔다. 대기줄은 Tomcat 커넥션 큐(`maxConnections`/`acceptCount`)에 두는 편이 싸다 | HikariCP 10개 → DB 경로에 10~20개 넘는 워커는 의미가 없다. 이 레포가 실측한 `pending 187 = busy 200 − 풀 10`이 그 증거 |
| **메모리** | 스레드당 스택(`-Xss`) × N | 1GB 박스에 `-Xss512k` × 200 = 최대 100MB. 32면 16MB |

### 선행 사례 — "코어 수에 맞춰 200보다 낮게"는 반복해서 나오는 결론이다

값을 정하기 전에 공개된 사례와 다른 서버 구현의 기본값을 조사했다. 네 갈래가 전부 같은 방향을 가리킨다.

**① Netflix — 기본값이 CPU 기아를 만든 실제 사고 사례.** 가장 가까운 선례다. 트래픽이 급증하면 **`maxThreads`와 `acceptCount`가 둘 다 크기 때문에** OS 커넥션 큐와 워커가 전부 차고, 바쁜 스레드가 계속 늘어 **CPU 기아(starvation)** 로 이어졌다. 대응은 두 가지였다 — (a) 스레드 수를 **응답 시간과 코어 수로 역산**하고, (b) 여러 지점(OS 큐 + Tomcat 스레드)에 요청이 쌓이지 않게 해서 용량을 넘기면 **빨리 503으로 실패**시킨다.

> "요청당 평균 5ms면 스레드 하나가 최대 200 rps를 처리한다. 쿼드코어면 최대 800 rps" → 피크에 바쁜 스레드 약 8개 → **3배 여유를 둬 `maxThreads` 24~30**. `acceptCount`는 **10부터 시작해** 커넥션 오류가 사라질 때까지만 올린다.

우리 계산도 같은 구조다 — 코어 2개, 히트 경로 실측(요청당 서버 2.2ms), 여기에 느린 요청 여유분을 얹어 32. 다만 **우리는 fail-fast(503)를 도입하지 않는다.** Netflix는 대규모 fleet에서 상류가 즉시 다른 인스턴스로 재시도할 수 있는 전제였고, 이 서비스는 단일 인스턴스라 거부보다 Tomcat 커넥션 큐에서 기다리게 하는 편이 낫다. `acceptCount`·`maxConnections`를 명시적으로 잡는 것은 후속 과제로 남긴다.

**② Undertow — 기본값 자체가 코어 비례다.** `io-threads = max(availableProcessors, 2)`, **worker(= 요청 처리 스레드) = io-threads × 8**. 2 vCPU면 **16**, 4 vCPU면 32이다. Netty/WebFlux의 이벤트루프도 `cores` 기준이다. **200 고정은 Tomcat·Jetty 쪽 관성**이고, 비동기 I/O 시대에 설계된 서버들은 하드웨어에서 값을 유도한다.

**③ 튜닝 가이드들의 공식** — 출발점으로 **`cores × 2~4`**(CPU-bound면 1~2×, I/O 대기가 길면 배수를 올림)를 제시하고, 공통적으로 세 가지를 짚는다: 과다 스레드는 **컨텍스트 스위칭·메모리로 지연 스파이크**를 만든다, `maxConnections`/`acceptCount`가 워커 뒤의 대기줄이다, **Spring Boot 기본값 200은 개발 편의용 범용 값이라 운영에서 그대로 쓰지 말라.**

**④ 이 저장소가 이미 인용하던 것** — HikariCP 위키의 풀 사이징과 Goetz의 `N = cores × (1 + wait/compute)`. 스레드 풀 일반론이라 Tomcat 워커에도 그대로 적용된다(위 표의 첫 축).

**2 vCPU에 대입하면**:

| 출처 | 공식 | 2 vCPU 환산 |
|---|---|---|
| Goetz / HikariCP 위키 | `cores × (1 + wait/compute)` | **3~10** (경로별) |
| 튜닝 가이드 | `cores × 2~4` | **4~8** |
| Undertow 기본값 | `max(cores,2) × 8` | **16** |
| Netflix 역산 | (피크 바쁜 스레드) × 3 | **24~30** 상당 |
| **이번 결정** | 실측 plateau의 최댓값 | **32** |

32는 이 범위의 **가장 보수적인 끝**이다. 더 낮은 값(8·16)도 실측에서 TPS·p95가 같았지만(→ [ec2-measurement.md](ec2-measurement.md)), 아래 "워크로드가 섞여 있다"는 이유로 여유분 쪽을 택했다.

> **한계**: 위 사례들은 전부 **권고와 계산식**이지 이 앱의 워크로드를 잰 것이 아니다. 그래서 값을 문헌에서 고르지 않고 **후보 5개를 실측해 곡선을 보고** 정했다. 문헌은 "200이 근거 없는 값"이라는 판단과 후보 범위(8~64)를 정하는 데만 썼다.

**이 앱의 함정은 워크로드가 섞여 있다는 것이다.** 공식대로면 히트 경로는 3개면 되지만, AI 코스 생성은 Gemini를 요청 스레드에서 **동기로** 기다린다(`GeminiService.generateContent`, 수 초). 10MB 미디어 업로드도 워커를 오래 붙든다. maxThreads를 16으로 줄이면 동시 AI 요청 16건이 다른 모든 API를 막는다. 이 경로들은 부하테스트로 재지 않는다(Gemini 과금·쿼터). 그래서 **후보 선택 규칙에 "느린 요청 여유분"을 명시적으로 넣고**(아래 판정 기준 4), 근본 해결(느린 엔드포인트의 bulkhead/비동기화)은 후속 이슈로 뺀다.

## arm 설계

같은 JAR·같은 세션에서 arm을 교대 측정한다(#87과 같은 방식 — 세션 간 15% 변동 위에서 비교하지 않기 위해). arm 전환은 App EC2 `/opt/app/.env`(systemd `EnvironmentFile` → 실제 OS 환경변수)에 키를 넣고 재기동하는 것으로 충분하다 — Spring relaxed binding으로 `SERVER_TOMCAT_THREADS_MAX` → `server.tomcat.threads.max`. **JAR 재빌드 없음.**

| arm | `.env` | 의미 |
|---|---|---|
| **T200** | (키 없음) | 기준선 = 현재 운영(Tomcat 기본값) |
| **T64 / T32 / T16 / T8** | `SERVER_TOMCAT_THREADS_MAX=n` | 후보. 8은 "너무 줄이면 워커 부족 큐잉이 시작되는 하한"을 함께 보기 위한 arm |
| **SNC** | `YOURTRIP_REDIS_SHARE_NATIVE_CONNECTION=false` (maxThreads 200) | 이슈 5번의 대안 — 공유 커넥션을 끄고 모든 명령이 풀(`max-active: 8`)에서 전용 커넥션을 빌린다 |

**SNC arm이 검증하는 가설.** `shareNativeConnection=false`면 채널이 최대 8개로 늘어 Lettuce I/O 스레드(lettuce-core 6.6.0의 `DefaultClientResources`: `max(availableProcessors, MIN_IO_THREADS=2)` = 2 vCPU에서 **2개**)에 분산된다. 그러나 채널을 나눠도 **경합 상대(워커)와 vCPU 2개는 그대로**라 런큐 대기가 사라지는 게 아니라 분산될 뿐이고, 풀 8개가 워커 200개의 새 동시성 상한이 되며(HikariCP에서 본 20:1 병목의 재판) 명령마다 commons-pool2 borrow/return 비용이 붙는다. 예측은 **"maxThreads 축소(경합 상대를 줄임) > SNC=false(피해자를 늘림)"** 다. 이 arm은 측정용 임시 토글(`LettuceShareNativeConnectionCustomizer`, 프로퍼티 게이트 `BeanPostProcessor`)로 구현했고 **측정 후 제거한다**(d144126의 벤치마크 토글과 같은 관례).

## 부하·수집·판정

- **부하**: `scripts/k6/popular-cold.js`를 `constant-vus`로, `FLUSHALL` 없이(= 워밍 히트 경로, #87 실측과 동일 스크립트) **VU 5 / 20 / 50 / 200 × 90s**. 이슈 표의 VU 축과 같다. ramping 대신 레벨별 고정 run을 쓰는 이유는 k6 p95/p99가 **레벨별**로 나오고, 스냅샷 Δ만으로 집계돼 단계 경계 슬라이싱 오차가 없기 때문이다.
- **순서**: arm 전환(재기동·`tomcat_threads_config_max_threads` 검증) → 예열 30s → 4레벨. **2회 반복, 2회차는 역순**(시간 표류가 특정 arm에만 얹히지 않게).
- **수집**: [scripts/loadtest/](../../../scripts/loadtest/) — k6 EC2에서 `poll-metrics.sh`(1초, `/actuator/prometheus` 전량), App EC2에서 `sample-host.sh`(loadavg·`procs_running`·steal), VU 200 구간 중반에 `sample-schedstat.sh 10`(스레드 그룹별 런큐 대기 비율). `aggregate.py`가 run별 한 행으로 만든다. 절차는 [guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md)의 하네스 절에 있다.
- **혼합 부하 검증**(이슈 4번): 선정 후보와 T200을 `popular-mixed.js`(배경 `/popular` 300 req/s + 상세 조회 VU ramping)로 비교해 상세 조회 p95·`pending`·워커 앞 대기(`tomcat_connections_current − busy`)가 악화되지 않는지 본다.

**판정 기준(측정 전에 못 박는다)**

1. **주 지표**: VU 200에서 Redis 명령 지연(`lettuce_command_firstresponse`, GET·MGET)과 lettuce 스레드의 런큐 대기 비율. 하한값(#87 이후 0.33ms)에 얼마나 근접하는가.
2. **부 지표**: VU 50·200의 TPS, VU 200의 p95.
3. **가드**: VU 5·20(저부하)에서 회귀 없음(세션 내 변동 ±2% 이내), 오류율 0, 혼합 부하에서 상세 조회 p95·`pending`이 T200 대비 5% 이상 악화되지 않음.
4. **선택 규칙**: 이득이 평탄해지는 구간(plateau)에서 **가장 큰 값**을 고른다 — 위 "느린 요청 여유분" 때문. 예: 16과 32가 동률이면 32.

## 진행 상황

- [x] 임시 토글·하네스 작성, `main` 기준 JAR 빌드·배포
- [x] 인프라 기동, 재시딩(`DB_DDL_AUTO=validate`로 시드 유지), 8키 워밍 확인, `lettuce_command_*` 노출 확인
- [x] 본 배치(6 arm × VU 4레벨 × 2회) + schedstat — [ec2-measurement.md](ec2-measurement.md)
- [x] 혼합 부하 검증(T200/T32/T16, arm마다 `FLUSHALL`)
- [x] **32**로 결정 → `application-prod.yml` 반영, 토글 제거, 최종 JAR로 `config_max = 32`·VU 200 재현 확인
- [x] 인프라 정리 — `.env` `DB_DDL_AUTO=create` 복원, EC2 2대·RDS 정지

## 참고 문서

- [ec2-measurement.md](ec2-measurement.md) — 실측 결과·판정
- [../cache-effect-measurement/redis-io-bottleneck.md](../cache-effect-measurement/redis-io-bottleneck.md) — 이 작업의 근거가 된 병목 규명
- [../../guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) — 분리 환경 실행 절차·하네스 사용법

### 외부 출처 (위 "선행 사례" 절의 근거)

| 출처 | 이 문서에서 쓴 내용 |
|---|---|
| [Tuning Tomcat For A High Throughput, Fail Fast System — Netflix TechBlog (2015)](https://netflixtechblog.com/tuning-tomcat-for-a-high-throughput-fail-fast-system-e4d7b2fc163f) ([미러](https://murphyswork.wordpress.com/2015/08/06/tuning-tomcat-for-a-high-throughput-fail-fast-system/)) | 기본값이 만든 CPU 기아, 응답시간×코어 역산(24~30), `acceptCount` 10부터 증량, fail-fast 503 |
| [Undertow 공식 문서](https://undertow.io/undertow-docs/undertow-docs-2.1.0/index.html) | `io-threads = max(cores,2)`, `task-core/max-threads = io-threads × 8` |
| [Configuring Thread Pools for Java Web Servers — Baeldung](https://www.baeldung.com/java-web-thread-pool-config) | 코어·워크로드 기반 산정, 과다 스레드의 컨텍스트 스위칭 비용 |
| [Understanding the Tomcat architecture and key performance metrics — Datadog](https://www.datadoghq.com/blog/tomcat-architecture-and-performance/) | `maxThreads` / `maxConnections` / `acceptCount`의 계층 구조와 관측 지표 |
| [10 Apache Tomcat Performance Tuning Tips — eG Innovations](https://www.eginnovations.com/blog/tomcat-performance-tuning/) | 과다 스레드가 성능을 떨어뜨리는 메커니즘 |
| [Springboot load management: Restrict Maximum Threads](https://medium.com/@DilipCoder/springboot-load-management-mitigating-java-thread-overhead-restrict-maximum-threads-fbc5597be141) · [Tomcat, Why just 200 default threads?](https://alpitanand20.medium.com/tomcat-why-just-200-default-threads-febd2411b904) | `cores × 2~4` 출발점, "기본값 200은 개발 편의용" |
| HikariCP 위키 풀 사이징 · Goetz, *Java Concurrency in Practice* | `N = cores × (1 + wait/compute)` — 이 저장소가 커넥션 풀 논의에서 이미 인용해온 것 |
