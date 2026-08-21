# t3.small 2GB의 JVM 메모리 지도 — 힙 밖 165MB가 무엇인가

> [#101](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/101)의 실측 기록이다. 설계는 [README.md](README.md), 힙 상한 A/B는 [ab-measurement.md](ab-measurement.md)에 있다.
>
> **결론만 먼저** — Actuator가 알려주지 않던 **힙 밖 잔여는 159~171MB**이고, 힙 크기·부하·스레드 수와 거의 무관하게 일정하다. NMT로 분해하니 가장 큰 항목은 **심볼 테이블 54MB**와 **G1의 내부 자료구조 43MB**였다. **스레드 스택은 7.1MB(65스레드, 스레드당 112KB)로 예상보다 훨씬 작다.**
>
> **GC는 G1이다** — 라벨 추론이 아니라 `-XX:+PrintFlagsFinal`로 직접 확인했다. 플래그 없이 뜰 때의 기본 힙 상한은 **480MB**이고, 현행 `-Xmx448m`은 그 93%다.
>
> **확정하지 못한 것** — NMT를 켜면 측정 대상 자체가 커진다(추적 오버헤드 8.4MB + 처리량 28% 하락). 그래서 **항목별 비율만 쓰고 절대 총량은 A/B 배치의 RSS를 쓴다.**

## 1. GC와 기본 힙 — 체크리스트 5번

`-XX` 플래그가 하나도 없어 GC를 ergonomics가 정한다. 지금까지 이 값은 `jvm_gc_pause_seconds`의 `gc` 라벨로 **추론**만 돼 있었는데, 이번에 플래그를 직접 찍어 확정했다.

```
$ java -XX:+PrintFlagsFinal -version | grep -E 'UseG1GC|UseSerialGC|UseParallelGC|MaxHeapSize|MaxRAMPercentage|GCTimeRatio|InitialHeapSize'

UseG1GC            true
UseSerialGC        false
UseParallelGC      false
MaxHeapSize        503316480   (480 MB)
InitialHeapSize    31457280    (30 MB)
MaxRAMPercentage   25.0
GCTimeRatio        12
```

여기서 세 가지가 확정된다.

**(가) G1이다.** t3.micro(1GB)였다면 메모리 1,792MB 문턱 미달로 SerialGC였겠지만, t3.small은 `MemTotal` 1,913MB로 문턱을 넘어 server-class로 판정된다.

**(나) `-Xmx448m`은 기본값의 93%다.** 기본 `MaxHeapSize`가 480MB(= `MemTotal`의 25%)이므로, **명시적으로 448을 준다는 것은 기본값을 32MB 깎는 것에 지나지 않는다.** "1GB 중 여유분을 남기고 통제한다"던 주석의 의도는 t3.micro에서는 의미가 있었겠지만(1GB면 기본값이 약 250MB였을 것이다) t3.small에서는 사실상 무의미해졌다.

**(다) `GCTimeRatio = 12`.** G1이 힙을 넓히는 판단 기준이 `1/(1+12) ≈ 7.7%`라는 뜻이고, 실측 GC 점유(약 1%)가 그 1/7도 안 된다는 [ab-measurement.md](ab-measurement.md)의 논거가 여기서 나온다.

### GC를 명시 지정하지 않는 이유

이번에도 `-XX:+UseG1GC`를 박지 않고 ergonomics에 맡긴다. 근거와 대가를 같이 적어 둔다.

**맡기는 쪽을 택한 이유**는 이 저장소가 "배포 타겟과 같은 스펙에서 그대로 검증한다"를 원칙으로 삼기 때문이다. GC를 명시하면 부하테스트 박스는 항상 G1으로 뜨지만, **운영 박스가 다른 스펙이 됐을 때 그 차이를 측정이 잡아내지 못한다.** 지금은 양쪽 다 t3.small이라 ergonomics가 같은 답을 내므로 맡겨 두는 편이 실제 배포 조건을 더 정직하게 재현한다.

**대가는 조용한 변경이다.** ergonomics의 server-class 판정 문턱은 **메모리 1,792MB**인데 t3.small의 `MemTotal`이 1,913MB로 문턱 바로 위에 있다. 즉 **한 단계만 내려가도(t3.micro 1GB) GC가 예고 없이 SerialGC로 바뀐다.** 실제로 이 저장소는 t3.micro → t3.small 전환 시점에 이 변경을 겪었고, 어느 문서에도 기록이 없어 #97 실측에서야 `gc` 라벨로 확인됐다.

**그래서 명시 대신 감시로 대응한다.**

- 집계기가 `gc_names` 열로 매 run의 GC 종류를 남긴다(`scripts/loadtest/aggregate.py`).
- `LoadtestMetricsExposureTest`가 `gc` 라벨의 존재를 잠근다 — 라벨이 사라지면 GC 종류를 기록할 수 없게 되므로 테스트가 먼저 깨진다.
- 같은 테스트가 "힙 상한 = 양수 풀 max의 합" 항등식도 잠근다. GC가 바뀌어 풀 구성이 달라지면 이 등식이 깨지고, `switch-heap-arm.sh`의 힙 검증이 무의미해지기 전에 로컬 테스트에서 잡힌다.

**인스턴스 타입을 t3.small 미만으로 내린다면 이 판단을 다시 해야 한다.** 그때는 GC가 바뀌므로 `-Xmx` 예산(G1 자료구조 43MB가 SerialGC에서는 달라진다)과 이 문서의 메모리 지도 전체가 무효가 된다.

## 2. 박스 자체의 몫

앱을 완전히 정지시킨 상태에서 쟀다. **`-Xmx` 예산의 출발점**이다.

```
MemTotal                1,959,216 kB  = 1,913 MiB
MemAvailable (앱 정지)   1,629,972 kB  = 1,592 MiB
→ OS + 에이전트 몫                      =   321 MiB
```

| 프로세스 | RSS |
|---|---|
| amazon-cloudwatch-agent | 130.6 MB |
| systemd-journald | 68.7 MB |
| ssm-agent-worker | 27.7 MB |
| amazon-ssm-agent | 18.6 MB |
| systemd 외 | 약 57 MB |

**CloudWatch Agent 하나가 130MB로 최대 항목이다.** 이 에이전트는 `mem_used_percent`를 걷기 위해 켜 둔 것이라 측정 목적에는 필요하지만, 운영 환경에서 이 몫을 그대로 안고 갈 것인지는 별도 판단 사항이다.

## 3. 힙 밖 분해 — 체크리스트 3번

`-XX:NativeMemoryTracking=summary`를 켜고 VU 200 부하 중반(120초 시점)에 `jcmd VM.native_memory summary`를 떴다.

### 3-1. 프로세스 수준

```
VmRSS      630,684 kB = 615.9 MB     RssAnon  603,796 kB = 589.6 MB
VmHWM      645,636 kB = 630.5 MB     RssFile   26,792 kB =  26.2 MB
VmSwap           0 kB               Threads   66
```

**스왑이 0이다** — 이 박스는 스왑 자체가 없으므로 메모리가 모자라면 스왑이 아니라 OOM으로 간다. 여유를 넉넉히 잡아야 하는 이유다.

### 3-2. NMT 항목별 (committed 기준)

| 분류 | committed | 비고 |
|---|---|---|
| **Java Heap** | **257.0 MB** | 이 run의 `-Xmx`는 768m — 상한의 33%만 커밋했다 |
| Metaspace (Metadata) | 123.2 MB | 클래스 메타데이터 본체 |
| Symbol | **54.2 MB** | 심볼 테이블. **힙 밖에서 가장 큰 항목** |
| Code | 44.2 MB | JIT이 만든 기계어(코드 캐시) |
| **GC** | **43.3 MB** | G1의 remembered set·마킹 비트맵 등 내부 자료구조 |
| Class (class space) | 20.7 MB | Compressed Class Space + malloc |
| Shared class space | 12.6 MB | CDS 아카이브 |
| Other | 8.4 MB | |
| *Native Memory Tracking* | *8.4 MB* | **측정 자신의 오버헤드 — 평상시에는 없다** |
| **Thread** | **7.3 MB** | 스택 committed 7.1MB / 65스레드 |
| Internal | 1.6 MB | |
| Compiler | 0.9 MB | |
| 그 외(Module·Safepoint·Sync 등) | 약 0.7 MB | |
| **합계** | **582.3 MB** | NMT가 추적하는 범위 |

`VmRSS` 615.9MB − NMT 추적분 582.3MB = **33.6MB**가 NMT 밖이다. 대부분 `RssFile` 26.2MB(매핑된 JAR·공유 라이브러리)로 설명된다.

### 3-3. 잔여 네이티브 165MB의 정체

A/B 배치가 낸 `native_other_mb`(= RSS − 힙 committed − 논힙 − direct)는 24 run 전체에서 **159.0~171.2MB**였다. NMT 항목을 같은 기준으로 묶으면 이렇게 맞는다.

| 항목 | 크기 |
|---|---|
| Symbol | 54.2 MB |
| GC 내부 자료구조 | 43.3 MB |
| Shared class space | 12.6 MB |
| Other | 8.4 MB |
| **스레드 스택** | **7.1 MB** |
| Internal + Compiler + 기타 | 3.2 MB |
| Class malloc | 2.3 MB |
| NMT 자신의 오버헤드(이 run 한정) | 8.4 MB |
| NMT 밖(`RssFile` 등) | 33.6 MB |
| **합계** | **173.1 MB** |

배치의 실측 범위(159~171MB)와 맞고, 초과분은 NMT 오버헤드 8.4MB로 설명된다. **두 경로가 서로를 교차검증한다.**

### 3-4. Compressed Class Space는 Metaspace에 포함된다 — 측정 박스에서 재확인

집계기가 논힙 합계에서 CCS를 빼는 근거다. NMT가 직접 보여준다.

```
Metadata:     committed = 125,632 kB
Class space:  committed =  18,816 kB
                합계     = 144,448 kB = 141.1 MB   ← MXBean "Metaspace" 풀의 committed와 일치
```

빼지 않으면 힙 밖 예산이 **약 18MB 부풀려진다.**

## 4. `-Xss512k`에 대한 판단

사전 등록한 규칙은 **"NMT 실사용이 스레드당 200KB 이하면 512k 유지, 400KB를 넘으면 기본값 복귀를 제안"** 이었다.

```
Thread (reserved=38,611 kB, committed=7,519 kB)
       (threads #65)
       (stack: reserved=38,400 kB, committed=7,308 kB)
```

**스레드당 실제 커밋은 7,308 ÷ 65 = 112 KB**다. 200KB 이하이므로 규칙에 따라 **`-Xss512k`를 유지한다.**

다만 이 수치는 규칙이 전제한 것과 다른 그림을 보여준다. 스택은 **요구 페이징**이라 예약(512KB)과 무관하게 실제로 만진 만큼만 물리 메모리를 차지한다. 즉 **`-Xss512k`가 실제로 아끼는 RSS는 0에 가깝고**, 줄어드는 것은 가상 주소 공간뿐이다(64비트에서는 사실상 의미가 없다). 반대로 비용은 남는다 — 깊은 Hibernate 콜스택에서의 `StackOverflowError` 위험이고, 템플릿 주석이 스스로 인정하는 리스크다.

**"이득이 0이고 위험은 실재한다"는 것이 이번 실측의 발견이지만, 사전 등록한 규칙을 사후에 뒤집지 않는다.** 이번 범위에서는 유지하고, 제거 여부는 별도 판단으로 남긴다. 200스레드 시절의 근거(최대 100MB 절약)가 32스레드에서 무너졌다는 사실은 기록해 둔다.

## 5. NMT를 A/B와 분리한 것이 옳았다

| | A/B 배치 (NMT 꺼짐) | 이 run (NMT 켜짐) |
|---|---|---|
| TPS (VU200) | 3,605 | **2,586 (−28%)** |
| 추적 오버헤드 | — | committed 8.4MB |

NMT는 할당마다 꼬리표를 붙이므로 **네이티브 사용량 자체를 늘리는데, 그게 바로 재려는 값**이다. 처리량도 28% 떨어졌다. 그래서 이 run의 절대값은 판정에 쓰지 않고 **항목별 귀속 비율에만** 썼고, 절대 총량은 A/B 배치의 `rss_max_mb`를 썼다.

> 이 문서의 목적상 TPS 28% 하락은 문제가 아니다 — 메모리 구성 비율을 알고 싶었을 뿐이다. 다만 **NMT를 켠 채로 성능 수치를 인용하면 안 된다.**

## 6. 예산 — `-Xmx`를 얼마로 둘 수 있는가

```
MemTotal                            1,913 MB
− OS·에이전트 (실측)                    321
− 논힙 191.8 × 1.2                     230
− direct 8.4 × 3                        25
− 힙 밖 잔여 171.2 × 1.3                223    ← 스레드 스택이 여기 포함된다
− 안전 여유 (MemTotal의 10%)            191
─────────────────────────────────────────────
= 힙 상한 여지                          923 MB   → 64MB 내림 896 MB
```

> **사전 등록한 산정식에 오류가 있었다.** 원식은 `… − 스택실측×1.2 − 잔여네이티브실측×1.3 …`로 스레드 스택을 따로 뺐는데, `native_other_mb`의 정의(`RSS − 힙 − 논힙 − direct`)가 이미 스택을 포함한다. 두 번 빼는 셈이라 스택 항을 제거하고 계산했다. 영향은 32MB다.

여지는 896MB지만, [README.md](README.md) §5에 못 박은 기각선 **"OS 실측이 300MB를 넘으면 1024 → 768로 내린다"** 가 발동했다(321MB). **사후에 규칙을 완화하지 않으므로 채택값은 `-Xmx768m`이다.** 896과 768 모두 안전 범위 안이고, 768이 더 보수적이다.

## 7. 한계

- **NMT run은 1회뿐이다.** 항목별 비율의 재현성은 확인하지 못했다. 다만 `native_other_mb`가 24 run에서 ±6MB로 안정적이었으므로 총량 수준의 안정성은 확보돼 있다.
- **심볼 54MB와 GC 43MB의 증감 요인을 규명하지 않았다.** 심볼은 로드된 클래스 수에, GC는 힙 크기에 대체로 비례할 것으로 보이지만 재보지 않았다. 힙을 크게 키우면 GC 자료구조도 함께 커진다는 점은 예산에서 `×1.3` 여유로만 반영했다.
- **`RssFile` 26MB를 세부 분해하지 않았다.** JAR·공유 라이브러리 매핑으로 추정되나 `smaps` 단위로 확인하지는 않았다.
- **CloudWatch Agent 130MB를 측정 목적의 상수로 취급했다.** 운영에서 이 에이전트를 켤지 말지는 이 문서의 범위 밖이고, 끄면 예산이 그만큼 늘어난다.
- **부하는 인기 코스 조회 한 경로다.** 클래스 로딩이 더 많은 경로(AI 코스 생성 등)에서는 Metaspace·Symbol이 더 자랄 수 있다.

## 8. 참고 문서

| 문서 | 이 문서에서 쓴 내용 |
|---|---|
| [README.md](README.md) | 설계, 예산표, 사전 등록 규칙 |
| [ab-measurement.md](ab-measurement.md) | `native_other_mb` 24 run 실측, 측정 환경 상세 |
| [tomcat-thread-sizing/README.md](../tomcat-thread-sizing/README.md) | `-Xss` × 스레드 수 사이징의 원 근거(200스레드 전제) |
| [guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) | 하네스와 메모리 열 읽는 법 |
