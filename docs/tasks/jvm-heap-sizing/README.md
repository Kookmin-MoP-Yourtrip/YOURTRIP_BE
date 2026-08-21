# JVM 힙 재산정 — `-Xmx448m`은 어느 환경 기준도 아닌 값이 됐다

> [#101](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/101)의 기록이다. 이 문서는 **측정 전에 쓴 설계**이고, 실측 결과는 자매 문서에 남긴다.
>
> **결론만 먼저 (설계 시점)** — `-Xmx448m`의 근거 주석은 "1GB 중 … maxThreads=200 스레드 스택 여유분"인데, App EC2는 이미 t3.small(2GB)이고 prod 프로필의 `maxThreads`는 #88 이후 32다. **두 전제가 모두 낡았다.** 게다가 t3.small에서 JVM 기본 `MaxHeapSize`는 물리 메모리의 1/4 ≈ 478MB라, **448m은 사실상 기본값을 재확인하고 있을 뿐**일 가능성이 높다.
>
> **이 작업은 성능 개선이 아니라 전제 정정이다.** 채택 조건은 "새 값이 회귀를 일으키지 않는다"이고, GC 감소는 부수 효과로만 확인한다. **"차이 없음"도 정상 결과**이며 그대로 기록한다.
>
> **확정하지 못한 것** — 힙 밖 네이티브(스레드 스택·GC 자료구조·컴파일러 아레나·심볼)의 실사용량. 기존 하네스가 프로세스 RSS를 수집하지 않아 사후 소급이 안 된다. 이번에 계측을 추가해 잰다.

## 1. 무엇이 낡았나

`terraform/loadtest/templates/app-user-data.sh.tpl`

```
# -Xmx448m: 1GB 중 OS(~150~200MB)+메타스페이스+Tomcat maxThreads=200 스레드 스택
# 여유분을 남기고 힙 상한을 명시적으로 통제한다.
ExecStart=/usr/bin/java -Xmx448m -Xss512k -jar /opt/app/app.jar
```

| 주석의 전제 | 실제 | 어긋난 시점 |
|---|---|---|
| 박스가 1GB(t3.micro) | **t3.small 2GB** | 1GB에서 부하테스트가 메모리 부족으로 중단돼 올렸다(`connection-pool-bottleneck/stage0/production/callerruns-verification.md`) |
| `maxThreads=200` | **32** | #88이 `application-prod.yml`에 `server.tomcat.threads.max: 32`를 넣었다 |

이 낡은 서술은 실제로 오판을 낳았다 — #98 후속 논의에서 "배포 타겟이 t3.micro"라는 전제로 판단이 진행됐다.

## 2. 착수 전에 확인된 사실

기존 실측 세션의 원자료(`results/d1`·`results/gcchk`, 2026-08-18~19)를 이번에 추가한 메모리 열로 **다시 집계**해 얻었다. `.prom`은 폴러가 전량 저장해 왔으므로 앱 변경도 재측정도 없이 집계만 추가하면 나오는 값이다.

| 지표 | T8 | T32 | T64 | T200 |
|---|---|---|---|---|
| `heap_max_mb` (`-Xmx`) | 448.0 | 448.0 | 448.0 | 448.0 |
| heap **used** 최대 | 206.4~209.2 | 210.2~235.2 | 210.6~214.6 | 213.3~256.5 |
| heap **committed** 최대 | 234~237 | 237~277 | 237 | 240~312 |
| 논힙 committed (CCS 제외) | 190.1~192.7 | 191.2~192.6 | 189.5~193.4 | 190.4~194.1 |
| └ 메타스페이스 | 140.2~140.6 | 140.3~141.7 | 140.1~141.8 | 140.3~142.0 |
| └ 코드 캐시 | 41.0~42.5 | 40.4~42.4 | 35.9~42.8 | 40.8~42.7 |
| Compressed Class Space | 18.3 | 18.3~18.6 | 18.3~18.6 | 18.3~18.6 |
| direct buffer | 8.2 | 8.4 | 8.5~8.6 | 8.5~9.7 |
| **JVM 보고 합계** | 434.9~437.2 | 436.6~478.0 | 435.0~439.0 | 438.9~512.8 |
| live threads | 30 | 54 | 74~86 | 222 |

<sub>18개 run 전부 `pid_changed=false`라 창 안에서 재기동이 걸리지 않았다. `heap_max_mb`가 전 run 448.0으로 일정해 `-Xmx` 플래그가 실제로 먹고 있음이 확인된다.</sub>

여기서 나오는 관찰 세 가지.

**(가) 힙이 모자란 상황이 아니다.** `-Xmx448m`인데 heap used가 어느 arm에서도 257MB를 넘지 않았다. 힙을 키우는 근거는 "앱이 더 필요해서"가 될 수 없다.

**(나) 논힙은 arm과 무관하게 일정하다.** 스레드가 30개든 222개든 논힙 committed는 **190~194MB**로 거의 변하지 않는다(메타스페이스 ~141MB가 지배하고, 그건 로드된 클래스 수가 정하므로 부하와 무관하다). 예산을 잡을 때 이 값은 **상수로 취급해도 된다.**

> **Compressed Class Space를 논힙 합계에서 뺐다.** CCS는 Metaspace 풀에 **포함**돼 있어 그냥 더하면 이중계상이다. JDK 21에서 NMT로 직접 확인했다 — `Metadata committed 9,306,112 + Class space committed 1,376,256 = 10,682,368`이 MXBean의 `Metaspace committed`와 정확히 일치했다. 빼지 않으면 힙 밖 예산이 약 18MB 부풀려진다.

**(다) 힙 밖에서 아직 모르는 부분이 남는다.** JVM이 스스로 보고하는 몫(힙 committed + 논힙 + direct)은 T32에서 437~478MB다. 그런데 **프로세스 RSS를 재지 않아** 스레드 스택 실주거·GC 자료구조·컴파일러 아레나·심볼·malloc 아레나가 얼마인지 알 수 없다. 이번 측정의 핵심이 이 잔여를 채우는 것이다.

## 3. 계측 추가

| 층 | 무엇을 | 어떻게 | 오버헤드 |
|---|---|---|---|
| **A. 상시(모든 run)** | RSS·vsize·MemTotal/Free/Available + JVM 영역별 committed/used | `sample-host.sh` 1초 샘플 + **이미 저장 중인** `.prom` | **없음** — RSS는 이미 읽는 `/proc/<pid>/stat`에서 필드 하나를 더 꺼내고, meminfo는 셸 내장 `read` 3줄이다. 샘플러에 fork를 더하지 않는다 |
| **B. 프로파일(별도 run)** | NMT 영역별 귀속 | `-XX:NativeMemoryTracking=summary` + `jcmd VM.native_memory` | **5~10% — 그래서 A와 분리한다** |

**B를 A에 섞지 않는 이유**: NMT는 malloc마다 헤더를 붙여 **네이티브 사용량 자체를 늘리는데, 그게 바로 재려는 값**이다. NMT run의 RSS 절대값은 부풀려져 있으므로 **영역별 귀속 비율에만 쓰고, 절대 총량은 A층의 `rss_max_mb`를 쓴다.**

파생 지표:

```
jvm_known_mb    = heap committed + 논힙 committed(CCS 제외) + direct
native_other_mb = rss_max_mb − jvm_known_mb      ← 이번에 새로 아는 값
mem_headroom_mb = MemTotal − rss_max_mb
```

## 4. 힙 arm을 바꾸는 방법

힙 플래그는 `.env`가 아니라 **user_data가 쓴 systemd 유닛의 `ExecStart`에 하드코딩**돼 있어 Tomcat arm처럼 바꿀 수 없다. 그렇다고 템플릿을 고쳐 `terraform apply`를 하면 `ec2_app.tf`의 `user_data_replace_on_change = true` 때문에 **App EC2가 교체되고 scp로 올린 `app.jar`·CloudFront 개인키가 사라진다.**

그래서 `switch-heap-arm.sh`가 **systemd 드롭인**으로 `ExecStart`만 갈아끼우고, 값은 `.env`의 `JVM_OPTS`에 둔다. 형상이 아니라 실행 상태만 바꾸는 조작이라 state drift가 생기지 않는다 — `terraform/loadtest/README.md`의 `upload-course-caching` 선례("user_data를 적용하지 않고 `.env`만 직접 고쳤다")와 같은 경로다.

```
switch-heap-arm.sh set <heap>  →  switch-thread-arm.sh <max> <snc>  →  switch-heap-arm.sh verify <heap>
   (드롭인·JVM_OPTS만, 재기동 X)      (재기동 1회 + health + thread 검증)        (힙 적용 검증)
```

**적용 검증을 두 갈래로 하는 이유**: `JVM_OPTS`가 비면 `$JVM_OPTS`가 빈 문자열로 사라져 JVM이 조용히 ergonomics 기본(2GB의 25% ≈ 494MB)으로 뜬다. **448m과 비슷해 눈으로 구분되지 않으므로** 기계로 확인해야 한다.

1. `/proc/<pid>/cmdline`에 `-Xmx<heap>m`이 실제로 전달됐는가
2. `jvm_memory_max_bytes{area="heap"}`의 **양수 합**이 기대값의 2% 안인가 (G1이 region 크기 배수로 반올림한다)

2번이 서 있는 항등식 — "양수 풀 max의 합 = `Runtime.maxMemory()`" — 은 `LoadtestMetricsExposureTest`가 잠근다. GC가 바뀌어 풀 구성이 달라지면 이 검증이 통째로 무의미해지기 때문에 **EC2를 켜기 전에** 확인한다.

## 5. `-Xmx` 예산

```
-Xmx ≤ MemTotal − OS·에이전트 − 논힙 − direct − 스레드 스택 − 잔여 네이티브 − 안전 여유
```

| 항목 | 근거 | 예산 |
|---|---|---|
| `MemTotal` | 측정 시 `free -m`으로 확정 | ~1,950 MB |
| OS + CloudWatch/SSM 에이전트 + sshd | **앱 정지 상태**의 `MemTotal − MemAvailable`로 확정 | 220 MB (가정) |
| 논힙 committed | 실측 190~194MB에 여유 | 240 MB |
| direct buffer | 실측 8~10MB에 3배 여유 | 32 MB |
| 스레드 스택 | T32에서 live 54개 × 512k = 27MB | 32 MB |
| **잔여 네이티브** | **미측정 — 이번에 잰다** | 128 MB (가정) |
| 안전 여유 + 페이지 캐시 | `MemTotal`의 10% | 195 MB |
| **잔여 = 힙 상한** | 1,950 − 847 | **1,103 MB** |

**주 후보 `-Xmx1024m`** (1GiB 정수, 공식값보다 79MB 보수적), **보수 후보 `-Xmx768m`.**

측정 후 확정 규칙(사전 등록):

```
Xmx_final = 64MB 단위 내림(
  MemTotal − OS실측 − 논힙실측×1.2 − direct실측×3
           − 스택실측×1.2 − 잔여네이티브실측×1.3 − 0.10×MemTotal )
```

잔여 네이티브 실측이 192MB를 넘거나 OS 실측이 300MB를 넘으면 **1024 → 768로 내린다.**

### 고정 `-Xmx`를 유지한다 (`MaxRAMPercentage`로 바꾸지 않는다)

| 축 | 판단 |
|---|---|
| 이식성 | 퍼센트가 유리한 유일한 축인데, **부하테스트 EC2와 운영 EC2가 둘 다 t3.small로 같아졌다.** 이 축의 이득이 사라졌다 |
| 재현성 | 이 저장소는 세션 간 절대값 비교를 금지할 만큼 조건 고정에 민감하다. 퍼센트는 AMI·커널 예약량이 바뀌면 `MemTotal`을 따라 힙이 조용히 달라져 문서에 적은 값과 실제가 어긋난다 |
| 검증 | 고정값은 `jvm_memory_max_bytes` 합과 **정확히 일치**해야 하므로 §4의 검증이 등식이 된다. 퍼센트면 느슨한 부등식이 된다 |

다만 주석에 퍼센트 환산을 병기한다(`-Xmx1024m` ≈ `MaxRAMPercentage=52.4`, 2GB 기준) — 컨테이너로 옮길 때의 매핑을 미리 남긴다.

### `-Xss512k`는 이번 A/B에서 건드리지 않는다

얻는 것은 T32 기준 54 × 512k = **27MB**뿐인데(200스레드 시절엔 100MB였다), 비용은 깊은 Hibernate 콜스택에서의 `StackOverflowError` 위험이다 — 템플릿 주석이 스스로 인정하는 리스크다. 2GB에서 27MB를 얻자고 상관없는 실패 모드를 떠안는 건 남는 장사가 아니므로 **기본값 복귀가 원칙적으로 옳다.**

**다만 A/B에서 함께 바꾸지 않는다** — 변수를 하나로 유지해야 힙 효과를 읽는다. A/B 뒤에 별도 확인 arm으로 (가) RSS 증가가 예측치(+27MB) 안인지, (나) TPS·p95가 노이즈 안인지만 확인하고, 확인되면 별도 커밋으로 뺀다. 확인이 안 되면 유지하고 "확정하지 못한 것"에 남긴다.

## 6. 측정 설계

| | 값 | 이유 |
|---|---|---|
| arm | **`T32H448` / `T32H768` / `T32H1024`** | 2점 비교는 "1024가 우연히 좋았다"를 배제하지 못한다. 3점이면 **용량–반응의 방향**을 본다 |
| maxThreads | **32 고정** | 이슈가 지정한 arm이자 prod 프로필의 실제 값. 변수는 힙 하나여야 한다 |
| VU 레벨 | `5 20 50 200` × 90초 | 관례. 낮은 레벨이 예열을 겸한다 |
| 반복 | 2회, 2회차 역순 | 관례. 시간 표류가 특정 arm에만 얹히지 않게 |
| schedstat | 60초 창 | 힙을 키우면 **gc 그룹의 CPU·런큐 시간**이 줄어야 한다 — 그게 기전이라 그룹 분해가 직접 증거다 |

```bash
ARMS="T32H448 T32H768 T32H1024" REPS=2 LEVELS="5 20 50 200" LEVEL_SEC=90 WARMUP_SEC=60 SCHEDSTAT_SEC=60 OUT=./results/h1 bash scripts/loadtest/run-batch.sh
```

소요는 부하 36분 + arm 전환 6회로 **약 45~50분**이다.

**과거 기준선(TPS 2,921 / p95 82.9ms)은 합격 기준으로 쓰지 않는다.** 그 값은 Xeon 8259CL 세션의 것이고, EC2는 stop→start마다 다른 물리 호스트에 배치된다(`cpu-cost-decomposition.md`). 판정은 **같은 세션 내 A/B**로만 한다.

## 7. 사전 등록 예측 (측정 전에 못 박는다)

핵심은 **힙 상한을 올리면 G1이 힙을 실제로 더 커밋하는가**이다. 여기서 서로 배타적인 두 가설이 갈린다.

> **G1은 `-Xmx`까지 무조건 자라지 않는다.** GC 오버헤드가 목표치를 넘을 때만 힙을 늘린다(`GCTimeRatio` 기본 12 → 목표 ≈ 7.7%). 그런데 **실측 GC 일시정지 점유는 벽시계의 0.79~1.16%로 목표의 1/7도 안 된다.** G1 입장에서는 힙을 키울 이유가 없다.

| 가설 | 내용 | 판별자 |
|---|---|---|
| **H1 — G1 자율 사이징이 지배한다** | `-Xmx`를 올려도 heap committed는 237~280MB 부근에 머문다 → GC 빈도·TPS·p95 모두 불변 | `heap_committed_max_mb`가 arm 간 ±40MB 안 |
| **H2 — 힙 상한이 제약이었다** | `-Xmx`를 올리면 committed가 따라 오른다 → GC 빈도 감소 | `heap_committed_max_mb`가 arm에 비례해 증가 |

**설계자의 예측은 H1이다.** 근거는 위 GC 오버헤드 수치이고, 보조 근거는 같은 `-Xmx448m` 안에서도 committed가 237MB(GC 2.16회/s)와 277MB(GC 1.62회/s)로 갈렸다는 관측이다 — **G1이 상한이 아니라 자기 휴리스틱으로 크기를 정하고 있다는 신호다.**

| # | 예측 | 기각 조건 |
|---|---|---|
| **P1** | heap committed가 arm 간 ±40MB 안(H1) | 1024 arm의 committed가 448 arm보다 100MB 이상 크면 H2 채택 |
| **P2** | young GC 빈도가 arm 간 ±25% 안 | 1024 arm에서 25% 넘게 줄면 P2 기각(= H2의 증거) |
| **P3** | **TPS·p95가 ±2% 안** | 어느 쪽으로든 2% 초과 |
| **P4** | `native_other_mb`가 arm 간 ±20MB 안 | 초과하면 GC 자료구조가 힙에 비례해 자란다는 뜻 → 예산 재계산 |
| **P5** | RSS 증가분 ≤ heap committed 증가분 | 초과하면 네이티브 부작용을 따로 조사 |

**P3이 주 지표다.** 목표가 성능 개선이 아니라 값의 정정이므로 **"회귀가 없다"가 채택 조건**이고, P1·P2는 기전의 확인이다. **H1이 맞다면 "힙 상한을 올려도 아무 일도 일어나지 않는다"가 결론이고, 그것이야말로 이 변경이 안전하다는 증거다.**

## 8. 사전 등록 판정 기준

**채택 — 전부 만족**

| # | 조건 |
|---|---|
| C1 | `errors_5xx = 0`, `k6_fail_rate = 0`, `pid_changed = false`, 커널 OOM 로그 0건, `VmSwap = 0` |
| C2 | VU200 2회 모두 `mem_headroom_mb ≥ 350` **그리고** `mem_avail_min_mb ≥ 350` |
| C3 | VU200 TPS가 `T32H448` 대비 **−2% 이내**(2% 넘게 떨어지지 않음) |
| C4 | VU200 p95가 `T32H448` 대비 **+5% 이내** |
| C5 | 전 run `cpu_xcheck_pct`의 절댓값이 5 이하 — 벗어나면 창 정렬이 어긋난 것이므로 판정 자체가 무효 |
| C6 | `heap_max_mb`가 arm 표기와 일치, `config_max_threads = 32` |

**기각 — 하나라도**

| # | 조건 | 조치 |
|---|---|---|
| R1 | `mem_headroom_mb < 200` 또는 `mem_avail_min_mb < 200` | 한 단계 내림(1024 → 768). 768도 걸리면 448 유지 |
| R2 | VU200 TPS 2회 평균이 −2%보다 나쁨, 또는 p95 +5% 초과 | 힙 확대가 해롭다는 결론 → **448 유지하고 그 사실을 기록** |
| R3 | 5xx 또는 k6 실패 발생 | 즉시 기각 |
| R4 | C5 위반 | 그 run 폐기 후 재측정. 반복되면 세션 전체를 의심 |

**"개선했다"고 쓸 수 있는 조건** (채택과 별개 — 없어도 채택 가능)

- 요청당 GC(ms)가 **2회 모두** −20% 이상이고 방향이 일치
- TPS 개선 주장은 **2회 모두 +3% 이상**이고 방향이 일치. 미만이면 **"차이 없음"으로 쓴다**
- 448 → 768 → 1024가 **단조 방향성**을 보이면 근거가 강해진다. 비단조면 노이즈로 읽는다

## 9. 한계

- **NMT run과 비교 run이 분리돼 있다.** NMT의 5~10% 오버헤드가 재려는 값 자체를 늘리기 때문이다. NMT의 절대 총량은 쓰지 않고 영역별 귀속 비율만 쓴다.
- **과거 세션(#88 8259CL, #97 8175M)과 절대값을 비교하지 않는다.** t3는 CPU 세대가 섞인 fleet에서 돌고 stop→start마다 물리 호스트가 바뀐다.
- **`-Xms`를 건드리지 않는다.** `-Xms`를 `-Xmx`와 같게 두면 리사이즈가 사라져 RSS가 예측 가능해지지만, committed 거동 자체를 바꿔 A/B를 오염시킨다. 범위 밖이다.
- **`-XX:MaxDirectMemorySize`의 기본값은 `-Xmx`와 같다.** 448m → 1024m이면 다이렉트 천장도 함께 오른다. 실측 사용량이 8~10MB라 당장 문제는 없지만 최악 footprint가 커지는 건 사실이라 `direct_buffer_mb`로 감시한다.
- **운영 반영은 수동이다.** 설계 시점에는 JVM 옵션이 정의된 곳이 부하테스트 user_data 템플릿 한 곳뿐이라 이번 재산정이 운영에 닿지 못하는 상태였다. 그래서 [deploy/prod/](../../../deploy/prod/README.md)에 자리를 만들어 값과 근거를 버전관리하게 했다. 다만 **거기 있는 파일이 운영 서버로 자동으로 나가지는 않는다** — CI/배포 파이프라인이 없어 scp와 재기동은 여전히 사람이 한다. 그리고 그 유닛 파일은 부하테스트에서 검증된 구조를 옮긴 것이지 운영 서버의 실제 구성을 확인하고 쓴 것이 아니다.
- **1초 폴링 해상도의 최대값이다.** 그 사이의 순간 첨두는 놓친다.

## 10. 참고 문서

| 문서 | 이 문서에서 쓴 내용 |
|---|---|
| [tomcat-thread-sizing/ec2-measurement.md](../tomcat-thread-sizing/ec2-measurement.md) | `maxThreads` 200 → 32 결정과 과거 기준선(참고값) |
| [tomcat-thread-sizing/cpu-cost-decomposition.md](../tomcat-thread-sizing/cpu-cost-decomposition.md) | GC가 `G1 Young Generation`이라는 확인, 세션 간 절대값 비교 금지 원칙 |
| [tomcat-thread-sizing/measurement-harness.md](../tomcat-thread-sizing/measurement-harness.md) | 하네스 구조와 샘플러에 fork를 더하지 않는다는 원칙 |
| [connection-pool-bottleneck/stage0/production/callerruns-verification.md](../connection-pool-bottleneck/stage0/production/callerruns-verification.md) | t3.micro(1GB)에서 메모리 부족으로 t3.small로 올린 경위 |
| [terraform/loadtest/README.md](../../../terraform/loadtest/README.md) | `user_data_replace_on_change`로 인한 교체 위험과 `upload-course-caching`의 우회 선례 |
| [guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) | 부하테스트 실행 절차와 측정 하네스 |
