# 계측 하네스 — 어떻게 쟀고, 그 계측을 믿어도 되는가

> [#97](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/97)의 결론([cpu-cost-decomposition.md](cpu-cost-decomposition.md))을 내기 위해 만든 계측의 설계·구현·검증 기록이다.
>
> **본편과 나눈 이유**: 결론을 읽는 사람과 계측을 다시 하려는 사람이 다르다. 본편은 **"26%가 무엇이었나"**에 답하고, 이 문서는 **"그걸 어떻게 쟀고 그 계측을 믿어도 되나"**에 답한다.
>
> 하네스의 **사용법**은 [guide/ec2-rds-loadtest.md §5-1](../../guide/ec2-rds-loadtest.md)에 있다. 이 문서는 **왜 그렇게 바꿨는가**를 남긴다.

## 1. 무엇을 새로 수집하는가

| 지표 | 출처 | 왜 |
|---|---|---|
| `ctxt`, `procs_blocked` | `/proc/stat` | 시스템 전역 전환 총량 — 이슈 요구 항목 |
| `jvm_utime`, `jvm_stime` | `/proc/<pid>/stat` 14·15번 | **주 지표.** 요청당 유저 모드 명령어 수는 arm과 무관하게 같으므로(같은 코드·같은 히트 경로·요청당 Redis 명령 2.0회), 유저 시간의 차이는 "일이 늘어난 것"이 아니라 **"같은 일이 느려진 것"** = 캐시/TLB다. 전환 경로·스케줄러·시스템콜은 커널 시간에 잡힌다 |
| `jvm_minflt`, `jvm_majflt` | `/proc/<pid>/stat` 10·12번 | TLB/메모리 압박 |
| pcount | `/proc/<pid>/task/*/schedstat` 3번째 | 스레드 그룹별 전환 횟수 |
| `voluntary`/`nonvoluntary_ctxt_switches` | `/proc/<pid>/task/*/status` | 자발(블로킹) 대 비자발(선점) 분해 |
| `jvm_gc_pause_seconds_*`, `jvm_gc_memory_allocated_bytes_total`, `jvm_compilation_time_ms_total` | `/actuator/prometheus` | GC·JIT 차감. **앱 변경 없음** — 폴러가 이미 전량 저장해 왔고 집계만 추가했다 |

> **`ctxt`가 주 지표가 아닌 이유.** 이슈는 `/proc/stat`의 `ctxt`를 지목했지만, 전환 횟수는 **가설을 기각하는 용도**이지 26%를 배분하지 못한다. 배분을 해주는 것은 유저/커널 시간 분리다. t3는 PMU가 없어 `perf stat -e cache-misses`로 IPC를 직접 못 재므로(§3), 이 분리가 그 대체재다.

## 2. 샘플러의 편향을 제거했다 — 기존 문서의 caveat를 지우는 수정

[ec2-measurement.md](ec2-measurement.md)는 "샘플러가 `/proc/<pid>/task/`를 두 번 훑는데 스레드가 많을수록 그 시간이 길어져 분모(벽시계)가 늘어난다 — T200 11,876ms vs T8 10,354ms, 최대 15%p"라고 인정하고 **"arm 간 수 %p 차이는 읽지 않는 것이 맞다"**고 못 박았다.

원인은 코드에 있었다. 스냅샷이 **스레드마다 `cat`과 `grep` 프로세스를 띄우고** 있었고(스레드당 fork 2회, T200이면 스냅샷당 400여 회), 게다가 t0 스냅샷을 tid마다 `grep`으로 훑어 O(N²)였다. `cat` → 셸 내장 `read`, `grep` → 연관 배열로 바꿔 **fork를 0으로** 만들었다.

WSL 리눅스에서 137스레드 프로세스로 측정한 전후:

| | 스냅샷 1회 소요 (5회 평균) |
|---|---|
| 기존(fork 방식) | **740.4 ms** |
| 신규(awk 1회) | **36.5 ms** |

**20배**다. 60초 창 기준으로 순회가 차지하는 비중이 2.5%(그리고 arm 의존)에서 0.12%로 내려간다. 요청당 전환 횟수를 정규화하려면 이 편향이 치명적이라 선행 조건이었다.

부수로 **벽시계를 `date`(CLOCK_REALTIME)에서 `/proc/uptime`(단조 증가)으로** 바꿨다. 검증 중 8초 창이 7.57초로 계측되는 것을 실제로 관측했다 — 창 도중 시계가 스텝되면 분모가 통째로 어긋난다.

## 3. `perf`는 쓰지 않는다 — 실측으로 확인

이슈 체크리스트 1번의 답은 **"쓸 수 없고, 쓸 필요도 없다"**이다.

- 하드웨어 이벤트(`cache-misses`)는 t3에서 PMU가 노출되지 않아 측정 불가다.
- 소프트웨어 이벤트(`context-switches`)는 되지만 `/proc/stat`의 `ctxt`와 같은 소스라 새 정보가 없다.
- App EC2에 `perf`를 설치하는 것은 [headless JRE만 깐다](../../../terraform/loadtest/templates/app-user-data.sh.tpl)는 명시적 결정과 충돌한다.

**주장으로 남기지 않고 실측했다.** 측정 대상이 아닌 k6 EC2(같은 t3 계열)에 임시 설치해 확인하고 제거했다.

```
$ sudo perf stat -e cycles,instructions,cache-misses,cache-references true
     <not supported>      cycles
     <not supported>      instructions
     <not supported>      cache-misses
     <not supported>      cache-references

$ sudo perf stat -e context-switches,cpu-migrations,page-faults true
                   0      context-switches
                   0      cpu-migrations
                  51      page-faults
```

**하드웨어 이벤트는 전부 `<not supported>`다.** 근거는 [Brendan Gregg, The PMCs of EC2](https://www.brendangregg.com/blog/2017-05-04/the-pmcs-of-ec2.html)와 [aws/aperf#384](https://github.com/aws/aperf/issues/384)에 있지만, 이제 인용이 아니라 이 박스의 실측이다.

## 4. 전환 1회 비용 캘리브레이션 벤치마크

[`ContextSwitchCostBenchmark`](../../../src/test/java/backend/yourtrip/global/benchmark/ContextSwitchCostBenchmark.java) + [`run-switch-benchmark.sh`](../../../scripts/loadtest/run-switch-benchmark.sh).

방법은 [Li·Ding·Shen, *Quantifying the Cost of Context Switch* (ExpCS 2007)](https://www.usenix.org/legacy/events/expcs07/papers/2-li.pdf)를 따른다 — 메모리 접근이 없을 때의 비용 `c1`(직접)과 크기 S의 워킹셋을 훑을 때의 `c2`를 재고 **간접 = c2 − c1**.

원 논문은 파이프로 통신하는 두 프로세스를 썼지만, 재려는 대상이 "스레드가 많을 때 무슨 일이 생기는가"이므로 **스레드 쌍 N/2개**로 일반화했다(쌍마다 토큰이 따로 돌아 항상 N/2개가 runnable — T200 arm의 형상). 한 스레드의 한 차례(**일감 1개**)는 이렇다.

```
park에서 깨어남
  → 자기 배열(S바이트)을 64바이트 간격으로 한 번 훑음   ← S=0이면 이 줄이 없음
  → 파트너에게 토큰 넘기고 unpark → 다시 park
```

CPU 시간은 `/proc/self/stat`의 utime+stime, 전환 횟수는 `/proc/self/task/*/schedstat`의 3번째 필드에서 읽어 **CPU 시간 ÷ 전환 횟수**로 1회당 비용을 낸다. 부하테스트 하네스와 **같은 출처·같은 단위**를 쓴 이유는 두 측정을 나눗셈으로 이어붙이기 위해서다.

`N ∈ {2, 8, 32, 200} × S ∈ {0, 4KB, 32KB, 256KB, 1MB}`를 스윕하며, **S는 L1d 32KB·L2 1MB 경계를 걸치도록** 골랐다.

설계 제약 두 가지를 지켰다.

- **N마다 JVM을 새로 띄운다.** 한 JVM에서 N을 바꾸면 앞 설정이 JIT 프로파일을 오염시킨다 — EC2 측정에서 "arm마다 재기동"으로 지켜온 원칙과 같다. 기존 [`SigningBenchmarkTest`](../../../src/test/java/backend/yourtrip/global/benchmark/SigningBenchmarkTest.java)의 `measure()`는 단일 스레드·단일 JVM 전제라 **선례로 쓰지 않았고**, 태그 격리와 전용 태스크 관례만 차용했다.
- **App EC2에는 javac가 없다.** 로컬에서 `./gradlew contextSwitchBenchmarkJar`로 의존성 없는 독립 실행 jar(7.7KB)을 만들어 scp한다. 앱을 멈춘 상태에서 돌리므로 RDS·ElastiCache·k6가 필요 없고, 본 측정과 같은 세션에 얹으면 **추가 인프라 비용이 0**이다.

**정합성 가드**: 설계상 `전환 ÷ 일감`이 1.0이어야 한다. 실측은 전 조합에서 0.97~1.00이었다. 이 가드를 넣은 경위는 §6에 있다.

## 5. 측정 조건

- **arm 4개: T200 · T64 · T32 · T8.** T64는 용량-반응의 중간점, **T8은 역방향 대조군**이다(runnable은 T32와 같은데 요청당 CPU가 더 높다).
- **VU 2레벨: 200(본) · 50(음성 대조군).** VU 5·20은 arm 간 차이가 노이즈 안이라 뺐다.
- **2회 반복, 2회차 역순.** 같은 JAR·같은 부팅 세션.
- 부하는 `popular-cold.js` `constant-vus`, `THEME_MODE=all`, `FLUSHALL` 없음(워밍 히트 경로) — #88과 같은 조건이어야 재현·대조할 수 있다.
- **재기동 직후 첫 고부하 run은 버린다**(JIT 예열). 낮은 VU 레벨을 먼저 도는 것이 추가 예열을 겸한다.
- schedstat 창은 60초(레벨 구간 한가운데), 예열은 60초로 올렸다.

**판정 기준(측정 전에 못 박았다)**

1. **주 지표**: 요청당 유저 시간 / 커널 시간의 arm 간 차이.
2. **부 지표**: 요청당 전환 횟수(그룹별 = tomcat / lettuce / gc / jit / other), 자발 대 비자발 비율.
3. **차감**: GC CPU/요청, JIT 컴파일 시간, 마이너 페이지 폴트/요청.
4. **스핀 배제**: 유저 시간 증가가 지역성이 아니라 스핀-후-파킹일 수 있다. 스핀은 반드시 park으로 끝나므로 **자발 전환/요청이 유의하게 늘면 스핀 신호**로 읽고 지역성 귀속에서 뺀다.
5. **결론 규칙**: `낭비 = 커널 몫 + GC/JIT 몫 + 스핀 몫 + 잔여`. **잔여를 지역성으로 귀속하되, 잔여가 전체의 50% 미만이면 "지역성이 지배적"이라고 쓰지 않는다.**

## 6. Phase 0 로컬 게이트 — EC2를 켜기 전에 통과시킨 것

이 저장소의 [Phase 0 관례](../cache-effect-measurement/phase0-local-gate.md)를 따랐다.

| # | 항목 | 결과 |
|---|---|---|
| 1 | 하네스가 긁는 지표가 **그 이름 그대로** `/actuator/prometheus`에 나오는가 | **통과** — [`LoadtestMetricsExposureTest`](../../../src/test/java/backend/yourtrip/global/config/LoadtestMetricsExposureTest.java)로 자동화 |
| 2 | 새 컬럼이 실제 리눅스에서 채워지는가 | **통과** — WSL에서 `utime`이 초당 100 jiffies(=1 vCPU)로 증가하고 커널 원본과 일치(438 vs 436). 괄호·공백이 든 `comm`도 안전하게 파싱 |
| 3 | pcount·자발/비자발이 0이 아닌 값으로 나오는가 | **통과** — 118스레드 프로세스에서 자발 240,957 / 비자발 1,415(sleep 기반 스레드의 예상 형상). 스레드 그룹 분류 16개 케이스 회귀 테스트 통과 |
| 4 | 집계기가 파생값을 내는가 | **통과** — T200 VU200을 재현한 합성 픽스처로 `req_cpu_ms = 0.7469`(문서값 0.747), `/proc` 직독값과 `process_cpu_usage` 기반값의 **교차검증 0.0%** |
| 5 | 샘플러 fork 제거 효과 | **통과** — 740.4ms → 36.5ms (§2) |

**게이트에서 실제로 걸러낸 것 두 가지.**

1. **벤치마크가 전환 횟수를 0으로 셌다** — 111,420 일감에 전환 126. `join()` 뒤에 `/proc/self/task/*`를 읽어서 **스레드가 이미 사라진 뒤**였다. 스레드가 살아 있을 때 찍도록 고쳤고, 전환/일감이 1.0 근처가 아니면 경고를 내는 가드를 넣었다.
2. **`/actuator/prometheus`가 테스트에서 404였다** — Spring Boot가 테스트 컨텍스트에 `management.defaults.metrics.export.enabled=false`를 심어 익스포터를 끄기 때문이다(운영과 무관한 의도적 기본값). `@AutoConfigureObservability`가 필요하다. `@SpringBootTest(properties=...)`로 값을 되돌리는 것으로는 안 된다 — 그 프로퍼티 소스가 인라인 테스트 프로퍼티보다 앞에 붙어 이긴다.

> 게이트가 없었다면 둘 다 **EC2를 켜고 부하를 건 뒤에** 발견했을 것이고, 2번은 "지표가 원래 안 나오는구나"로 오진할 여지도 있었다.

**측정 직전에 걸린 것 두 가지**도 함께 남긴다.

- **`default` arm이 200이 아니라 32로 뜬다.** #88이 `application-prod.yml`에 `max: 32`를 넣은 뒤로 "키 제거 = Tomcat 기본값 200"이 성립하지 않는다. 배치 드라이버가 T200을 `default`로 넘기고 있어 첫 arm에서 죽었을 것이다.
- **워머가 빈 랭킹을 캐시한다.** 시딩 전에 뜬 앱의 `PopularCourseCacheWarmer`가 빈 랭킹을 30분 TTL로 캐시해, 시딩 후 재기동해도 `/popular`이 빈 배열을 반환했다([redis-io-bottleneck.md](../cache-effect-measurement/redis-io-bottleneck.md)에 기록된 사고와 같다). `FLUSHALL` 후 재기동해 5건이 나오는 것을 확인하고 측정에 들어갔다.

## 참고 문서

- [cpu-cost-decomposition.md](cpu-cost-decomposition.md) — 이 계측으로 낸 결론
- [../../guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) §5-1 — 하네스 사용법
- [../cache-effect-measurement/redis-io-bottleneck.md](../cache-effect-measurement/redis-io-bottleneck.md) — schedstat 샘플러의 출처
