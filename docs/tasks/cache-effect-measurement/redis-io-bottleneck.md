# 단일 Lettuce I/O 스레드 병목 — "앱 밖" 포화의 정체

> 캐싱 효과 측정([ec2-measurement.md](ec2-measurement.md))이 남긴 미해결 항목을 푼 기록이다. A2에서 포화 주체를 여러 시나리오에서 **"앱 밖"** 으로 적어둔 채 정체를 가리지 못했는데, `pending` 0 · 워커 7% · CPU 0.80으로 **앱 안의 어느 자원도 한계가 아닌데 처리량이 눕는** 상태였기 때문이다.
>
> **결론만 먼저**: 앱 밖이 아니라 **앱 안**이었다. 앱과 Redis를 잇는 TCP 연결이 1개라 모든 Redis I/O가 netty 이벤트루프 스레드 **하나**를 통과하는데, 그 스레드가 vCPU 2개를 놓고 Tomcat 워커 129개와 경합해 **런큐에서 90.8%를 대기**한다. CPU 사용량은 9.3%뿐이라 기존 지표 어디에도 귀속되지 않고, **Redis 명령 지연**의 형태로만 나타났다.
>
> 후속 개선은 [#87](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/87)(ElastiCache AZ 정렬 — 아래 "해소" 절)과 [#88](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/88)(Tomcat `maxThreads` 축소 — [tomcat-thread-sizing/](../tomcat-thread-sizing/README.md))로 등록했고 둘 다 실측을 마쳤다. #88 실측은 이 문서의 두 가지를 정정한다 — ① "런큐 대기 **비율**이 병목"이 아니다(비율은 포화 신호이고, 지연의 크기는 단일 채널 앞에 밀리는 in-flight 명령 수 ≤ 워커 수 × 2가 정한다). ② 워커를 줄여 얻은 것은 **Redis 대기 감소가 아니라 CPU 낭비 감소**다(요청당 CPU -26%) — Redis 명령 지연 하락은 대기 장소가 Tomcat 커넥션 큐로 옮겨간 부산물이다.

---


A2의 포화 주체를 여러 시나리오에서 "앱 밖"으로 적어둔 채 정체를 가리지 못했다. 후보는 넷이었고, 전부 실측으로 정리됐다.

| 후보 | 실측 | 판정 |
|---|---|---|
| 부하 생성기 (k6 t3.micro) | CPU 최대 53.9%, 크레딧 0 도달 없음 | **배제** |
| Redis 엔진 (ElastiCache) | `EngineCPUUtilization` 최대 **9.3%** | **배제** |
| 네트워크 (AZ 횡단) | **바닥값 1.2ms를 설명** | **부분 확인** |
| **Lettuce 단일 공유 커넥션 / netty 이벤트루프** | **런큐 대기 90.8%** | **확정** |

### 계측 공백의 실체는 앱이 아니라 하네스였다

문서 여러 곳에 "Lettuce 계측이 없다"고 적어왔는데, **`lettuce_command_*`는 처음부터 노출되고 있었다.** Boot 3.5.7의 `LettuceMetricsAutoConfiguration`이 `MeterRegistry` 빈을 보고 `MicrometerCommandLatencyRecorder`를 자동으로 붙인다. **측정 하네스의 폴링이 15개 지표 화이트리스트로 걸러내고 있었을 뿐이다.**

### Redis 지연이 응답 시간의 절반 이상이다

P1 A2에서 요청당 Redis 명령이 **정확히 2회**(랭킹 `GET` + 아이템 `MGET`)로 확인됐고, 그 지연이 부하와 함께 **12.7배** 늘어난다.

| VU | TPS | 응답 평균 | Redis 지연 | 요청당 Redis | **응답 중 비중** | 명령/s |
|---|---|---|---|---|---|---|
| 5 | 1,634 | 3.5ms | **1.200ms** | 2.40ms | **68.1%** | 3,265 |
| 20 | 2,731 | 9.4ms | 2.811ms | 5.62ms | 60.0% | 5,459 |
| 50 | 2,753 | 18.4ms | 5.290ms | 10.58ms | 57.4% | **5,506** |
| 200 | 2,617 | 58.4ms | **15.203ms** | 30.39ms | 52.0% | 5,230 |

**명령 처리량이 약 5,500/s에서 천장을 친다** — VU 20부터 200까지 평평한데 지연만 2.8 → 15.2ms로 늘어난다. 전형적인 큐잉이다. 그런데 **Redis 서버는 한가하다**(엔진 CPU 7%). 서버가 밀리는 게 아니라 거기 닿기까지, 정확히는 **돌아온 응답을 수거하기까지**가 밀린다.

같은 조건에서 A1은 1.200 → 1.658ms(**+38%**)에 그친다. 커넥션 풀에 묶여 명령을 초당 4,285개까지밖에 못 밀어넣기 때문이다. **A2가 풀에서 풀려나 5,500/s까지 밀어붙이자 다음 벽이 드러난 것**이다.

### 원인 — 단일 I/O 스레드의 런큐 대기

앱과 Redis를 잇는 **TCP 연결이 1개**다(`shareNativeConnection` 기본값 `true`). netty는 연결 하나를 스레드 하나에 고정하므로, **앱 전체의 Redis I/O가 스레드 1개를 통과한다.**

부하 중 `/proc/<pid>/task/<tid>/schedstat`으로 그 스레드를 10초간 관찰한 결과다.

| 그룹 | 실행 | **런큐 대기** | 대기 비율 | 스레드 수 |
|---|---|---|---|---|
| `http-nio-*` (Tomcat) | 18,376ms | 136,421ms | 88.1% | 129 |
| **`lettuce-epollEventLoop`** | **934ms** | **9,259ms** | **90.8%** | **1** |

**실행 + 대기 ≈ 10초(벽시계 전체)라는 점이 결정적이다.** 이 스레드는 한순간도 놀지 않았다. Redis가 느려서 기다리는 중이었다면 그 시간은 blocked 상태라 **런큐에 서지도 않는다.** 그런 시간이 사실상 0이라는 건 **처리할 응답이 항상 밀려 있었다**는 뜻이다.

CPU를 9.3%밖에 안 쓰는데 병목인 이유는, **이 스레드가 CPU를 많이 쓸 필요는 없지만 자주·빨리 얻어야 하기 때문**이다. vCPU 2개에 runnable 스레드가 11~13개(load average 8.5)라 매번 차례를 기다린다.

**즉 "앱 밖"이 아니라 앱 안의 CPU 경합이었고, 그것이 CPU 사용률이 아니라 I/O 지연의 형태로 나타나 기존 지표(`pending` 0, 워커 7%, CPU 0.87)로는 어디에도 귀속되지 않았다.** 결국 판정 3의 "CPU가 최종 천장"과 이어진다.

### 바닥값 1.2ms — ElastiCache가 다른 AZ에 있다

| 리소스 | AZ |
|---|---|
| App EC2 / k6 / RDS | `ap-northeast-2a` |
| **ElastiCache** | **`ap-northeast-2c`** |

`elasticache.tf`의 서브넷 그룹이 primary(2a)와 secondary(2c)를 모두 포함하는데 `preferred_availability_zone`을 지정하지 않아 AWS가 2c를 골랐다. **모든 Redis 명령이 AZ를 횡단한다.**

같은 AZ면 통상 0.2~0.4ms인데 측정값이 1.2ms인 것이 이걸로 설명되고, `completion ≈ firstresponse`(1.509 vs 1.505ms)로 시간이 **왕복에 쏠려 있다**는 것도 부합한다.

**arm 비교는 무사하다** — 클러스터를 재생성한 적이 없어 41개 run 전부와 과거 실측에서 동일했던 상수다. 다만 **캐싱 이득은 과소평가돼 있다.** A1·A2는 이 비용을 물고 A0는 `/popular`에서 Redis를 쓰지 않으므로, 같은 AZ였다면 캐시 arm이 더 빨랐을 것이다.

> **측정값은 하한이다.** `lettuce_command_*`는 **채널에 쓴 시점부터** 잰다. 그런데 이벤트루프가 아닌 스레드(Tomcat 워커)에서 온 쓰기는 netty가 **이벤트루프의 작업 큐에 넣어** 처리하므로, 그 큐에서 기다린 시간은 타임스탬프 이전이라 **아예 포함되지 않는다.** 실제 Redis 왕복 비용은 위 값보다 크다.

#### 해소 — 같은 AZ로 옮기고 전후를 실측했다

`elasticache.tf`의 `aws_elasticache_cluster`에 `availability_zone = var.availability_zone_primary`를 지정해 노드를 `ap-northeast-2a`로 고정했다([#87](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/87)).

> **인자명 함정** — AWS API의 `PreferredAvailabilityZone`에 대응하는 Terraform 인자는 `availability_zone`이다. `preferred_availability_zones`(복수)는 **Memcached 전용**이라 Redis 클러스터에는 쓸 수 없고, `az_mode`도 마찬가지다. 이슈 본문에 적었던 `preferred_availability_zone`을 그대로 쓰면 `Unsupported argument`로 실패한다.

**전후를 같은 세션에서 쟀다.** 원래 AZ 이동은 클러스터 재생성을 요구해 before/after가 다른 세션으로 갈리는데, 그러면 "세션 간 변동 15%"([ec2-measurement.md](ec2-measurement.md)의 한계) 위에서 비교하게 된다. 교체가 10분 안에 끝나므로 **같은 JAR·같은 시드·같은 부하로 앞뒤를 연달아 재서** 그 제약을 피했다.

| run | ElastiCache AZ | `remote` 라벨의 실제 IP | `firstresponse` | `completion` | TPS | 응답 평균 |
|---|---|---|---|---|---|---|
| before r1 | `2c` | `10.42.2.149` | **1.291ms** | 1.291ms | 1,210 | 4.00ms |
| before r2 | `2c` | `10.42.2.149` | **1.313ms** | 1.313ms | 1,159 | 4.18ms |
| after r1 | `2a` | `10.42.1.158` | **0.314ms** | 0.314ms | 2,068 | 2.29ms |
| after r2 | `2a` | `10.42.1.158` | **0.347ms** | 0.347ms | 2,078 | 2.28ms |

**명령 지연 1.302ms → 0.331ms (-74.6%).** 예측했던 "같은 AZ면 0.2~0.4ms" 구간에 그대로 들어왔다. 명령별로도 GET 1.291 → 0.309ms, MGET 1.314 → 0.352ms로 함께 내려간다.

- **요청당 Redis 비용 2.60ms → 0.66ms**, 응답 시간에서 차지하는 비중 **63.7% → 28.9%**.
- **TPS +75%(1,185 → 2,073), 응답 평균 -44%(4.09 → 2.29ms).** 왕복 비용만 걷어냈는데 처리량이 이만큼 오른 것은, 이 구간의 병목이 앱 자원이 아니라 **단일 I/O 스레드가 왕복을 기다리는 시간**이었다는 위 결론과 같은 방향이다.
- `completion ≈ firstresponse` 관계는 정렬 후에도 유지된다 — 남은 0.33ms도 여전히 왕복이 대부분이고, 서버 처리 비용이 아니다(Redis 엔진 CPU는 전후 모두 5~10%).

**`remote` 라벨이 AZ를 직접 증명한다.** Lettuce 메트릭의 `remote`에 해석된 사설 IP가 찍히는데, before는 `10.42.2.149`(secondary 서브넷 `10.42.2.0/24` = 2c), after는 `10.42.1.158`(primary 서브넷 `10.42.1.0/24` = 2a)이다. 엔드포인트 **DNS 이름은 재생성 후에도 그대로였고**(`...ouqrk4.0001.apn2...`) 뒤의 IP만 바뀌었다 — 그래서 `/opt/app/.env`의 `REDIS_HOST`는 고칠 필요가 없었다.

<details>
<summary>측정 조건</summary>

- **부하**: `scripts/k6/popular-cold.js`를 `-e VUS=5 -e DURATION=120s`로 전용(`constant-vus`). `FLUSHALL`을 병행하지 않아 콜드 스탬피드가 아니라 **워밍 상태의 정상 히트 경로**가 된다. 스크립트는 수정하지 않았다. 각 arm마다 예열 30초를 먼저 돌리고 본 측정 2회를 이어 붙였다.
- **집계**: 본 측정 직전·직후의 `/actuator/prometheus` 스냅샷 2장을 떠서 `Δsum ÷ Δcount`로 구간 평균을 냈다(1초 폴링 하네스가 없어도 저부하 평균에는 시계열이 필요 없다). `command` 라벨이 `GET`·`MGET`인 것만 집계해 조회수 스케줄러의 `EVALSHA`·`DEL`을 배제했다.
- **요청당 명령 2회가 재확인됐다** — 145,224 요청에 GET·MGET 각 290,448회로 정확히 2배다.
- **JAR은 교체하지 않았다.** 배포돼 있던 빌드를 그대로 쓰고 `.env`의 `YOURTRIP_BENCHMARK_*`가 A2 조합(`enabled`/`separated`)인 상태를 유지했다. before/after가 같은 바이너리를 쓰는 것이 여기서는 중요하다.
- **재시작해도 시드가 살아남게 `.env`의 `DB_DDL_AUTO`를 `create` → `validate`로 내렸다.** `create`면 재시작마다 스키마가 DROP/CREATE되어 시드가 사라지고, 그러면 `PopularCourseCacheWarmer`가 **빈 랭킹을 30분 TTL로 캐시해버린다**(실제로 이번 세션 초반에 발생해 `/popular`이 빈 배열을 반환했다). 이 조치로 before/after 양쪽이 "재시작 직후 + 예열 + 본 측정"으로 대칭이 됐다. **측정이 끝난 뒤 `create`로 되돌려** user_data 템플릿과의 divergence를 남기지 않았다.
- **스로틀링은 배제된다.** App EC2·k6 EC2 모두 `CpuCredits=unlimited`로 확인됐다(`describe-instance-credit-specifications`) — 측정 중 `CPUCreditBalance`가 0이었지만 unlimited 모드에서는 성능 저하로 이어지지 않는다. App EC2 CPU는 before 26~39%, after 68%로 어느 쪽도 포화가 아니다. 문서 한계 절의 "App EC2가 `unlimited` 모드라는 전제가 terraform에 고정돼 있지 않다"는 지적은 여전히 유효하다(계정 기본값에 의존하는 상태 그대로다).

</details>

**41개 run의 값은 그대로 둔다.** 위 실측은 AZ 횡단 비용이 얼마였는지를 사후에 특정한 것이지, 기존 측정을 무효화하지 않는다. 다만 **A1·A2가 물던 요청당 2.6ms가 이제 0.66ms**이므로, 같은 조건을 다시 재면 캐시 arm의 이득은 기록된 것보다 크게 나온다.

---

## 참고 문서

- [ec2-measurement.md](ec2-measurement.md) — 캐싱 효과 측정의 결론과 판정
- [scenarios.md](scenarios.md) — 시나리오별 원본 표
- [environment.md](environment.md) — 측정 환경·신뢰성 검증
