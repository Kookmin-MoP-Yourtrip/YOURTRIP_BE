# 요청당 CPU 26% 감소의 분해 — 전환 횟수인가, 캐시 지역성인가

> [#97](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/97)의 설계·사전 등록 기록이다. [ec2-measurement.md](ec2-measurement.md)가 한계로 남긴 항목을 정조준한다.
>
> **결론만 먼저(측정 전)**: 기존 실측만 다시 계산해도 **"26%는 전환을 더 많이 해서가 아니라 전환 1회당 비용이 비싸서 생긴다"**가 배제법으로 나온다. 직접 전환 비용의 몫은 **어떤 가정에서도 낭비의 6~16%가 상한**이고, 타임슬라이스 선점의 몫은 **1% 미만**이다. 남은 84~94%는 "같은 명령어 스트림이 더 느리게 도는 것" — 캐시/TLB 지역성이다.
>
> **다만 이것은 상한 논증이지 측정이 아니다.** 그래서 (a) 전환 횟수를 직접 재고, (b) 요청당 CPU를 **유저/커널로 갈라** 배분하고, (c) GC·JIT·스핀을 차감한 뒤, (d) 같은 박스에서 전환 1회 비용을 캘리브레이션해 잔여가 물리적으로 말이 되는지 확인한다.
>
> **새로 확인한 하드웨어 사실 하나가 판을 바꾼다** — App EC2(t3.small)의 2 vCPU는 **물리 코어 1개의 하이퍼스레드 2개**다. L1d 32KB와 **L2 1MB를 두 vCPU가 통째로 공유**한다. 지역성이 무너질 물리적 무대가 실재하며, 기존 문서 어디에도 기록돼 있지 않았다.

## 왜 이 분해가 필요한가

[#88](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/88)에서 `maxThreads`를 200 → 32로 줄이자 요청당 CPU가 26% 줄었고([ec2-measurement.md](ec2-measurement.md)), 그 원인을 "스레드 전환과 CPU 캐시 재적재 비용"이라고 **한 덩어리로** 적었다. 둘의 몫을 나누지 못했고, 하네스가 `/proc/stat`의 `ctxt`를 수집하지 않아 사후 소급도 불가능했다.

나누면 얻는 것:

- "스레드를 줄이면 왜 CPU가 덜 드는가"를 추정이 아니라 수치로 말할 수 있다.
- 같은 근거를 HikariCP·`courseImageCleanupExecutor` 사이징에 재사용할 수 있다. **원인이 전환 횟수라면 "블로킹 지점을 줄여라"가 처방이고, 지역성이라면 "동시 실행 스레드 수를 코어 수 가까이 눌러라"가 처방이다.** 처방이 갈리므로 나누는 값이 있다.

---

## 1. 측정 전 예산 계산 — 기존 데이터만으로 확정되는 것

재측정 없이 [ec2-measurement.md](ec2-measurement.md)의 VU 200 행(2회 평균)에서 산술로 나온다.

### 1-1. 낭비의 크기

| | T200 | T32 | 차 |
|---|---|---|---|
| TPS | 2,517 | 2,921 | |
| `process_cpu_usage` | 0.940 | 0.810 | |
| **요청당 CPU** = cpu × 2 ÷ TPS × 1000 | **0.7469** | **0.5546** | **0.1923 vCPU-ms** |

- 낭비 전력 = 0.1923ms × 2,517/s = **0.484 vCPU** — 2 vCPU 박스의 **24.2%**를 요청 처리가 아닌 곳에 태우고 있었다.
- 2.50GHz 환산으로 **요청 1건당 480,791 사이클**이 사라졌다.

### 1-2. TPS +16%의 분해 (오차 0의 항등식)

`TPS = 가동률 × 2000 ÷ 요청당 CPU` 이므로

```
TPS비 = (0.810 / 0.940) × (0.7469 / 0.5546) = 0.8617 × 1.3468 = 1.1605
실측  = 2921 / 2517                                          = 1.1605
```

**TPS +16.1% = 효율 +34.7% × 가동률 −13.8%.** 효율 개선의 상당 부분을 가동률 하락이 갉아먹은 구조다.

> **그래서 "CPU 사용률이 줄었다" 자체는 성과가 아니다.** 0.940 → 0.810의 절반은 일을 덜 해서가 아니라 **워커가 32개뿐이라 I/O 대기 동안 CPU를 다 못 채워서**다. 실제 성과는 사용률이 아니라 **요청당 비용**에 있다. T8에서 사용률이 더 내려가는데도(0.861) TPS가 T32와 같은 것이 그 증거다.

### 1-3. 용량-반응 — runnable 1개당 15.5~18.1 μs/req

낭비는 `maxThreads` 설정값이 아니라 **실제로 동시에 runnable인 스레드 수**를 따라간다. 겹치지 않는 두 구간이 거의 같은 기울기를 낸다.

| 구간 | Δrunnable | Δ요청당 CPU | 기울기 |
|---|---|---|---|
| T32 → T64 | 7.5 → 10.8 (+3.3) | +0.0512 ms | **15.5 μs/req/스레드** |
| T64 → T200 | 10.8 → 18.6 (+7.8) | +0.1411 ms | **18.1 μs/req/스레드** |

> **이 기울기를 회귀선으로 포장하지 않는다.** T16·T8은 runnable이 T32와 같은데(7.5~8.5) 요청당 CPU는 오히려 조금 높다(0.584·0.598 vs 0.555). 위 두 구간을 벗어나면 선형이 아니고, 같은 arm의 run 간 변동이 ±5%라 그 차이는 노이즈와 구분되지 않는다. **선형성은 T32 이상 구간에 한정된 관찰이다.**

### 1-4. 배제법 — 이 낭비는 "전환 횟수"로 설명될 수 없다

**요청 1건의 블로킹 지점은 정확히 2개다.** [`UploadCourseServiceImpl.getPopularCourses`](../../../src/main/java/backend/yourtrip/domain/uploadcourse/service/UploadCourseServiceImpl.java)의 히트 경로는 `@Transactional` 없이 랭킹 `GET` + 아이템 `MGET`이 전부다 — DB 커넥션 대여 0회(실측 0.0000), 이 경로에 `synchronized` 0건, 인터셉터 0건, JWT 필터는 토큰이 없어 DB에 닿지 않는다. 소켓 read/write와 poller 디스패치를 얹어도 **요청당 자발 전환은 4~6회**가 상한이다.

**(a) 낭비를 직접 전환 비용만으로 설명하려면**

| 전환 1회 비용 가정 | 필요한 요청당 **추가** 전환 |
|---|---|
| 1 μs | 192회 |
| 2 μs | **96회** |
| 5 μs | 38회 |

요청당 총 전환이 4~6회인 경로에서 96회의 *추가* 전환은 물리적으로 불가능하다.

**(b) 반대편 극단에서 눌러도 마찬가지** — "T200의 전환 6회는 전부 낭비이고 T32는 전환이 0회"라는, 성립할 수 없는 가정을 해도

- 6회 × 2 μs = 0.012 ms → 낭비의 **6.2%**
- 6회 × 5 μs = 0.030 ms → 낭비의 **15.6%**

→ **직접 전환 비용의 몫은 어떤 가정에서도 낭비의 6~16%가 상한이다.**

**(c) 타임슬라이스 선점은 1% 미만** — CFS 기본값(2 CPU 기준 `sched_latency` 12ms, `min_granularity` 1.5ms)에 실측 runnable을 대입하면

| runnable | per-cpu | 슬라이스 | 타임슬라이스 전환 | 요청당 |
|---|---|---|---|---|
| 18.6 (T200) | 9.3 | 1.50 ms | 1,333/s | 0.53회 |
| 7.5 (T32) | 3.8 | 3.20 ms | 625/s | 0.25회 |

Δ = 0.28회/req. 5 μs를 붙여도 **낭비의 0.73%**다. 슬라이스를 절반으로 잡아 전환율을 두 배로 올려도 1.5%다. 커널이 6.6+면 CFS가 아니라 EEVDF(`sched_base_slice_ns`)이므로 **실제 값은 측정 때 `/sys/kernel/debug/sched/`에서 읽어 기록한다**(`run-switch-benchmark.sh`가 자동으로 남긴다).

### 1-5. 하드웨어 — 두 vCPU가 물리 코어 하나를 공유한다

```
$ aws ec2 describe-instances --instance-ids i-06bc413840b95ca09 \
    --query 'Reservations[0].Instances[0].CpuOptions'
{ "CoreCount": 1, "ThreadsPerCore": 2 }
```

t3.small의 2 vCPU는 물리 코어 **1개의 하이퍼스레드 2개**다. Cascade Lake(Xeon Platinum 8259CL) 기준 **L1d 32KB와 L2 1MB를 두 vCPU가 통째로 공유**하고, 그 하나의 캐시를 runnable 18.6개가 번갈아 밟는다(T32는 7.5개).

자릿수 확인: L2 1MB = 16,384 캐시라인. 재개 1회가 L2의 1/4을 L3/DRAM에서 되채운다고 보면 4,096라인 × 실효 ~25사이클 ≈ 102,400 사이클, 재개 5회면 ≈ 512,000 사이클 — **낭비 예산 480,791 사이클과 같은 자릿수**다. *개연성 확인이지 증명이 아니다.*

### → 잠정 결론과 그 한계

**26%의 84~94%는 "전환 1회당 비용"에서 나온다.** 다만 이것은 배제법이고, 유저 모드에서 CPU를 더 태우는 다른 후보(**G1 young GC 빈도** — 스레드 200개 = TLAB 200개, JIT, 스핀-후-파킹)를 차감하지 않았다. 차감 없이 "나머지 = 캐시"라고 쓰면 #88이 저지른 것과 같은 종류의 오귀속이다.

---

## 2. 측정 설계 (측정 전에 못 박는다)

### 2-1. 주 지표는 `ctxt`가 아니라 user/system 시간 분리다

이슈는 `/proc/stat`의 `ctxt`를 지목했지만, §1-4의 계산상 `ctxt`는 **가설을 기각하는 용도**이지 26%를 배분하지 못한다. 배분을 실제로 해주는 것은 유저/커널 분리다.

> **요청당 실행하는 유저 모드 명령어 수는 arm과 무관하게 같다** — 같은 코드, 같은 요청, 같은 히트 경로, 요청당 Redis 명령 2.0회로 전 run 동일. 따라서 **유저 시간/요청의 차이는 "일이 늘어난 것"이 아니라 "같은 일이 느려진 것"** = IPC 저하 = 캐시/TLB다. 전환 경로·스케줄러·시스템콜은 전부 **커널 시간**에 잡힌다.

t3는 PMU가 노출되지 않아 `perf stat -e cache-misses`로 IPC를 직접 못 잰다(PMC는 대형/전용 호스트 한정 — [Brendan Gregg, The PMCs of EC2](https://www.brendangregg.com/blog/2017-05-04/the-pmcs-of-ec2.html), [aws/aperf#384](https://github.com/aws/aperf/issues/384)). user/system 분리는 그 대체재이면서 `/proc` 직독이라 "측정 대상 인스턴스는 순수하게 유지한다"는 이 저장소의 원칙과도 맞는다.

### 2-2. 사전 등록 예측

| # | 예측 | 기각되면 |
|---|---|---|
| **P1** | 요청당 전환 횟수는 T200과 T32가 **±2회 이내**로 같다(블로킹 지점 수가 정하므로) | 횟수 가설 부활 → 26%의 배분을 다시 계산 |
| **P2** | 낭비 0.192ms 중 **커널 몫 < 0.05ms(26%)**, **유저 몫 > 0.14ms(74%)** | 전환 경로가 주범 → §1-4 배제법이 틀린 것 |
| **P3** | GC CPU/요청의 arm 간 차이는 **낭비의 15% 미만** | GC가 3번째 버킷 → 별도 이슈로 분리 |
| **P4** | **T8은 T32보다 요청당 전환이 많다**(idle↔wake 왕복 증가) — T8의 요청당 CPU가 더 높은(0.598 vs 0.555) 이유 | T8 초과분은 다른 원인 |
| **P5** | VU 50(음성 대조군)에서는 arm 간 전환 횟수·user/sys 모두 차이 없음 | 부하와 무관한 계통 오차 존재 |

### 2-3. arm / 부하 / 반복

- **arm 4개: T200 · T64 · T32 · T8.** T64는 §1-3 용량-반응의 중간점, **T8은 역방향 대조군**이다(runnable은 T32와 같은데 요청당 CPU가 더 높다 → P4).
- **VU 2레벨: 200(본) · 50(음성 대조군).** VU 5·20은 arm 간 차이가 노이즈 안이라 뺀다.
- **2회 반복, 2회차 역순.** 같은 JAR·같은 부팅 세션.
- 부하는 `popular-cold.js` `constant-vus`, `THEME_MODE=all`, `FLUSHALL` 없음(워밍 히트 경로) — #88과 같은 조건이어야 0.747/0.555를 재현·대조할 수 있다.
- **재기동 직후 첫 고부하 run은 버린다**(JIT 예열).

### 2-4. 판정 기준

1. **주 지표**: 요청당 유저 시간 / 커널 시간의 arm 간 차이. 둘의 합이 기존 0.192ms를 재현하는가(실패 시 세션 간 표류로 보고 절대값 대신 비율만 읽는다).
2. **부 지표**: 요청당 전환 횟수(그룹별 = tomcat / lettuce / gc / jit / other), 자발 대 비자발 비율.
3. **차감**: GC CPU/요청, JIT 컴파일 시간, 마이너 페이지 폴트/요청.
4. **스핀 배제**: 유저 시간 증가가 지역성이 아니라 스핀-후-파킹(Lettuce 공유 채널의 netty outbound 큐 경합이 유일한 후보)일 수 있다. 스핀은 반드시 park으로 끝나므로 **자발 전환/요청이 유의하게 늘면 스핀 신호**로 읽고 지역성 귀속에서 뺀다.
5. **결론 규칙**: `낭비 = 커널 몫 + GC/JIT 몫 + 스핀 몫 + 잔여`. **잔여를 지역성으로 귀속하되, 잔여가 전체의 50% 미만이면 "지역성이 지배적"이라고 쓰지 않는다.**

---

## 3. 하네스 변경

### 3-1. 무엇을 새로 수집하는가

| 지표 | 출처 | 왜 |
|---|---|---|
| `ctxt`, `procs_blocked` | `/proc/stat` | 시스템 전역 전환 총량 — 이슈 요구 항목 |
| `jvm_utime`, `jvm_stime` | `/proc/<pid>/stat` 14·15번 | **주 지표**(§2-1) |
| `jvm_minflt`, `jvm_majflt` | `/proc/<pid>/stat` 10·12번 | TLB/메모리 압박 |
| pcount | `/proc/<pid>/task/*/schedstat` 3번째 | 스레드 그룹별 전환 횟수 |
| `voluntary`/`nonvoluntary_ctxt_switches` | `/proc/<pid>/task/*/status` | 자발(블로킹) 대 비자발(선점) 분해 |
| `jvm_gc_pause_seconds_*`, `jvm_gc_memory_allocated_bytes_total`, `jvm_compilation_time_ms_total` | `/actuator/prometheus` | GC·JIT 차감. **앱 변경 없음** — 폴러가 이미 전량 저장해 왔고 집계만 추가했다 |

### 3-2. 샘플러의 편향을 제거했다 — 기존 문서의 caveat를 지우는 수정

[ec2-measurement.md](ec2-measurement.md)는 "샘플러가 `/proc/<pid>/task/`를 두 번 훑는데 스레드가 많을수록 그 시간이 길어져 분모(벽시계)가 늘어난다 — T200 11,876ms vs T8 10,354ms, 최대 15%p"라고 인정하고 **"arm 간 수 %p 차이는 읽지 않는 것이 맞다"**고 못 박았다.

원인은 코드에 있었다. 스냅샷이 **스레드마다 `cat`과 `grep` 프로세스를 띄우고** 있었고(스레드당 fork 2회, T200이면 스냅샷당 400여 회), 게다가 t0 스냅샷을 tid마다 `grep`으로 훑어 O(N²)였다. `cat` → 셸 내장 `read`, `grep` → 연관 배열로 바꿔 **fork를 0으로** 만들었다.

WSL 리눅스에서 137스레드 프로세스로 측정한 전후:

| | 스냅샷 1회 소요 (5회 평균) |
|---|---|
| 기존(fork 방식) | **740.4 ms** |
| 신규(awk 1회) | **36.5 ms** |

**20배**다. 60초 창 기준으로 순회가 차지하는 비중이 2.5%(그리고 arm 의존)에서 0.12%로 내려간다. 요청당 전환 횟수를 정규화하려면 이 편향이 치명적이라 선행 조건이었다.

부수로 **벽시계를 `date`(CLOCK_REALTIME)에서 `/proc/uptime`(단조 증가)으로** 바꿨다. 검증 중 8초 창이 7.57초로 계측되는 것을 실제로 관측했다 — 창 도중 시계가 스텝되면 분모가 통째로 어긋난다.

### 3-3. `perf`는 쓰지 않는다

이슈 체크리스트 1번의 답은 **"쓸 수 없고, 쓸 필요도 없다"**이다.

- 하드웨어 이벤트(`cache-misses`)는 t3에서 PMU가 노출되지 않아 측정 불가다.
- 소프트웨어 이벤트(`context-switches`)는 되지만 `/proc/stat`의 `ctxt`와 같은 소스라 새 정보가 없다.
- App EC2에 `perf`를 설치하는 것은 [headless JRE만 깐다](../../../terraform/loadtest/templates/app-user-data.sh.tpl)는 명시적 결정과 충돌한다.

**주장으로 남기지 않고 실측한다** — 측정 대상이 아닌 **k6 EC2**(같은 t3 계열)에 `perf`를 임시 설치해 `perf stat -e cache-misses,context-switches true` 출력을 아래 실측 절에 붙이고 제거한다.

### 3-4. 전환 1회 비용 캘리브레이션

[`ContextSwitchCostBenchmark`](../../../src/test/java/backend/yourtrip/global/benchmark/ContextSwitchCostBenchmark.java) + [`run-switch-benchmark.sh`](../../../scripts/loadtest/run-switch-benchmark.sh). 방법은 [Li·Ding·Shen, *Quantifying the Cost of Context Switch* (ExpCS 2007)](https://www.usenix.org/legacy/events/expcs07/papers/2-li.pdf)를 따른다 — 메모리 접근이 없을 때의 비용 `c1`(직접)과 크기 S의 워킹셋을 훑을 때의 `c2`를 재고 **간접 = c2 − c1**.

원 논문은 파이프로 통신하는 두 프로세스를 썼지만, 재려는 대상이 "스레드가 많을 때 무슨 일이 생기는가"이므로 **스레드 쌍 N/2개**로 일반화했다(쌍마다 토큰이 따로 돌아 항상 N/2개가 runnable — T200 arm의 형상). N ∈ {2, 8, 32, 64, 200} × S ∈ {0, 4KB, 32KB, 256KB, 1MB}를 스윕하며, **S는 L1d 32KB·L2 1MB 경계를 걸치도록** 골랐다(§1-5).

판정: 큰 N의 페널티가 **S에 따라 커지면 캐시**, **S와 무관하게 평평하면 전환 경로**.

설계 제약 두 가지를 지켰다.
- **N마다 JVM을 새로 띄운다.** 한 JVM에서 N을 바꾸면 앞 설정이 JIT 프로파일을 오염시킨다 — EC2 측정에서 "arm마다 재기동"으로 지켜온 원칙과 같다. 기존 [`SigningBenchmarkTest`](../../../src/test/java/backend/yourtrip/global/benchmark/SigningBenchmarkTest.java)의 `measure()`는 단일 스레드·단일 JVM 전제라 **선례로 쓰지 않았고**, 태그 격리와 전용 태스크 관례만 차용했다.
- **App EC2에는 javac가 없다.** 로컬에서 `./gradlew contextSwitchBenchmarkJar`로 의존성 없는 독립 실행 jar(7.7KB)을 만들어 scp한다. 앱을 멈춘 상태에서 돌리므로 RDS·ElastiCache·k6가 필요 없고, Phase 1과 같은 세션에 얹으면 **추가 인프라 비용이 0**이다.

---

## 4. Phase 0 로컬 게이트 — EC2를 켜기 전에 통과시킨 것

이 저장소의 [Phase 0 관례](../cache-effect-measurement/phase0-local-gate.md)를 따랐다.

| # | 항목 | 결과 |
|---|---|---|
| 1 | 하네스가 긁는 지표가 **그 이름 그대로** `/actuator/prometheus`에 나오는가 | **통과** — [`LoadtestMetricsExposureTest`](../../../src/test/java/backend/yourtrip/global/config/LoadtestMetricsExposureTest.java)로 자동화. `jvm_gc_pause_seconds_{count,sum}`·`jvm_gc_memory_allocated_bytes_total`·`jvm_compilation_time_ms_total`·`jvm_threads_live_threads` 전부 확인 |
| 2 | 새 컬럼이 실제 리눅스에서 채워지는가 | **통과** — WSL에서 `utime`이 초당 100 jiffies(=1 vCPU)로 증가하고 커널 원본과 일치(438 vs 436), `minflt` 정확히 일치. 괄호·공백이 든 `comm`도 안전하게 파싱 |
| 3 | pcount·자발/비자발이 0이 아닌 값으로 나오는가 | **통과** — 118스레드 프로세스에서 자발 240,957 / 비자발 1,415(sleep 기반 스레드의 예상 형상). 스레드 그룹 분류 16개 케이스 회귀 테스트 통과 |
| 4 | 집계기가 파생값을 내는가 | **통과** — T200 VU200을 재현한 합성 픽스처로 `req_cpu_ms = 0.7469`(문서값 0.747), `redis_first_ms = 15.269`(문서값 15.269), `/proc` 직독값과 `process_cpu_usage` 기반값의 **교차검증 0.0%** |
| 5 | 샘플러 fork 제거 효과 | **통과** — 740.4ms → 36.5ms (§3-2) |

**게이트에서 실제로 걸러낸 것 두 가지.**

1. **벤치마크가 전환 횟수를 0으로 셌다** — 111,420 pass에 전환 126. `join()` 뒤에 `/proc/self/task/*`를 읽어서 **스레드가 이미 사라진 뒤**였다. 스레드가 살아 있을 때 찍도록 고쳤고, 전환/일감이 1.0 근처가 아니면 경고를 내는 가드를 넣었다(현재 0.985~0.994).
2. **`/actuator/prometheus`가 테스트에서 404였다** — Spring Boot가 테스트 컨텍스트에 `management.defaults.metrics.export.enabled=false`를 심어 익스포터를 끄기 때문이다(운영과 무관한 의도적 기본값). `@AutoConfigureObservability`가 필요하다. `@SpringBootTest(properties=...)`로 값을 되돌리는 것으로는 안 된다 — 그 프로퍼티 소스가 인라인 테스트 프로퍼티보다 앞에 붙어 이긴다.

> 게이트가 없었다면 둘 다 **EC2를 켜고 부하를 건 뒤에** 발견했을 것이고, 2번은 "지표가 원래 안 나오는구나"로 오진할 여지도 있었다.

---

## 5. 실측 결과

> 아직 측정하지 않았다. Phase 1(EC2 본 측정) → Phase 2(캘리브레이션) 후 여기에 채운다.
>
> 채울 것: arm × VU 표(요청당 user/sys, 요청당 전환·자발·비자발, GC/요청), P1~P5 판정, 26%의 최종 배분, 캘리브레이션 스윕 표, `perf`의 `<not supported>` 실측 출력, `/sys/kernel/debug/sched/` 값과 커널 버전.

## 참고 문서

- [README.md](README.md) — 사이징 원칙·arm 설계·판정 기준
- [ec2-measurement.md](ec2-measurement.md) — #88 실측. 이 문서가 그 "한계" 절을 잇는다
- [../cache-effect-measurement/redis-io-bottleneck.md](../cache-effect-measurement/redis-io-bottleneck.md) — 런큐 대기로 병목을 특정한 선례(schedstat 샘플러의 출처)
- [../../guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) — 실행 절차·하네스

### 외부 출처

| 출처 | 이 문서에서 쓴 내용 |
|---|---|
| [Li, Ding, Shen, *Quantifying the Cost of Context Switch*, ExpCS 2007](https://www.usenix.org/legacy/events/expcs07/papers/2-li.pdf) | 직접/간접 비용 분리 방법 — c1(메모리 접근 없음)과 c2(워킹셋 S)를 재고 간접 = c2 − c1 |
| [Brendan Gregg, *The PMCs of EC2*](https://www.brendangregg.com/blog/2017-05-04/the-pmcs-of-ec2.html) · [aws/aperf#384](https://github.com/aws/aperf/issues/384) | t3에서 PMU가 노출되지 않아 `cache-misses`를 못 잰다는 근거 |
