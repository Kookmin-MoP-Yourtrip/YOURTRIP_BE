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

다른 서버 구현의 기본값과 공개된 사례가 참고가 된다.

- **Undertow**는 기본값 자체가 코어 비례다 — `io-threads = max(cores, 2)`, **worker = io-threads × 8**(2 vCPU면 **16**). Netty/WebFlux 이벤트루프 = `cores`. Jetty·Tomcat만 200 고정이다([Undertow 문서](https://undertow.io/undertow-docs/undertow-docs-2.1.0/index.html)).
- **Netflix**는 트래픽 급증 시 기본값(200 + 큰 `acceptCount`)이 OS 큐와 워커를 전부 채워 CPU 기아로 이어지는 문제를 겪고, 스레드 수를 **응답 시간 × 코어 수로 역산**했다 — "요청당 5ms면 스레드 하나가 200 rps, 쿼드코어면 800 rps → 피크에 바쁜 스레드 약 8개 → 3배 여유를 둬 maxThreads 24~30"([Tuning Tomcat For A High Throughput, Fail Fast System](https://netflixtechblog.com/tuning-tomcat-for-a-high-throughput-fail-fast-system-e4d7b2fc163f), 2015). 이번 작업과 같은 구조다.
- 튜닝 가이드들은 출발점으로 **`cores × 2~4`** 를, CPU-bound면 1~2×를 제시하고, 과다 스레드가 컨텍스트 스위칭·메모리로 지연 스파이크를 만든다는 점과 `maxConnections`/`acceptCount`가 그 뒤의 대기줄이라는 점을 반복해서 짚는다([Baeldung](https://www.baeldung.com/java-web-thread-pool-config), [Datadog](https://www.datadoghq.com/blog/tomcat-architecture-and-performance/), [eG Innovations](https://www.eginnovations.com/blog/tomcat-performance-tuning/)).

이슈가 제시한 16~32는 `cores × 8`(16)과 그 두 배(32)로 읽으면 근거가 선다 — Netflix 역산법과 Undertow 기본값 사이, 가이드의 `cores × 4`(8)보다는 느린 요청 쪽으로 보수적인 값이다.

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
