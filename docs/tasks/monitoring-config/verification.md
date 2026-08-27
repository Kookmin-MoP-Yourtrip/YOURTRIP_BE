# 운영 모니터링 검증 — RSS로 109MB 차이가 실제로는 13MB였다

> [#121](https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/121)의 실측·판정 기록이다. 설계와 판정 기준은 [README.md](README.md)에 있다.
>
> **결론만 먼저** — **채택(Q1-B)**. 앱 정지 상태의 OS 실측이 **364.3MB**로, 예산식이 `-Xmx768m`을 보장하는 한계선 **476MB** 아래다(여유 111.7MB). 그리고 **CloudWatch Agent를 그대로 뒀어도 345.9MB**여서 300MB 기각선은 어차피 발동해 있었다 — **`-Xmx768m`은 Alloy 때문이 아니다.**
>
> **가장 중요한 발견** — 같은 박스·같은 시각에 나란히 재니 두 에이전트의 `ps` RSS 차이는 **109.0MB**였는데, **시스템이 실제로 잃는 양(`MemAvailable` 감소)의 차이는 12.9MB**였다. 나머지 96MB는 매핑된 바이너리(파일 백업 페이지)라 커널이 언제든 회수한다. **종전 P2는 이 96MB를 예산에 더하고 있었다.**
>
> **확정하지 못한 것** — 에이전트를 하나도 켜지 않은 순수 OS가 이미 **300.2MB**로 기각선 위였다. 이 값이 부하테스트 박스의 321MB(CWA 포함)와 어긋나는데, **왜 이 박스의 OS 기저가 더 무거운지는 규명하지 않았다.**

## 측정 환경

| 항목 | 값 |
|---|---|
| 인스턴스 | `i-07ee2c0025a6d5f2d`, **t3.small**, ap-northeast-2a |
| AMI | `ami-0729121845edb4108` — AL2023 2023.12.20260817.0, kernel 6.1 |
| `MemTotal` | **1,959,220 kB** — 부하테스트 박스(1,959,216 kB)와 4kB 차이. §2의 321MB와 **직접 비교 가능** |
| 스왑 | **없음** (`SwapTotal: 0`) — 익명 페이지는 회수 불가, 파일 백업 페이지만 회수된다 |
| Alloy | `1.18.1-1` (RPM, `var.alloy_version`으로 핀) — 사전 등록한 축소안 3개가 적용된 상태 |
| CloudWatch Agent | `1.300069.1-1.amzn2023` — **`616a023^`의 user-data와 글자 그대로 같은 설정**(`mem_used_percent` 하나, 30초, `YourtripProd`) |
| 앱 | `-Xmx768m -Xss512k`, `SPRING_PROFILES_ACTIVE=prod` |
| 측정일 | 2026-08-27 |

**ASG 프로세스를 정지시켜 놓고 쟀다.** Phase 2가 앱을 20분 넘게 내리므로 그대로 두면 ALB 헬스체크 실패 → ASG가 측정 중인 박스를 교체한다. 부하테스트 박스에는 ASG가 없어 §2 측정 때는 없던 함정이다. 끝나고 6개를 전부 되돌렸다(`suspended processes: 0` 확인).

> **CloudWatch Agent는 게시 권한 없이 돌았다.** `CloudWatchAgentServerPolicy`가 이미 제거돼 있어 `PutMetricData`가 실패한다. 영향을 산술로 배제했다 — 지표 1개를 30초 간격으로 걷으므로 기본 버퍼 상한(10,000 포인트)에 닿으려면 **83시간**이 걸린다. 그리고 그 근거를 실측으로 확인했다: 45분 창에서 기울기 **+720 kB/h**(사전 등록 기준 1MB/h 미만)이고, 기동 10분 뒤부터는 **134,128 kB에서 한 바이트도 움직이지 않았다.**

## 판정 결과

사전 등록한 P1~P12를 전부 통과했다. **다만 P2는 기준 자체를 갈아 끼웠다** — 아래 "P2를 다시 세운 이유" 참고.

| # | 항목 | 결과 |
|---|---|---|
| P1 | Alloy 생존 | ✅ 부팅 2시간 뒤 `active`, `NRestarts=0`, OOM 0건, cloud-init에 `Alloy 구성 실패` 0건 |
| P2 | 상주 메모리 | ✅ **아래 별도** (프록시 폐기 후 Q1-B 채택) |
| P3 | 앱 지표 도달·정확 | ✅ `sum(jvm_memory_max_bytes{area="heap"} > 0)` = **805306368** 정확히 일치 |
| P4 | 호스트 지표 도달 | ✅ `node_memory_MemAvailable_bytes` 조회됨. `1 − MemAvailable/MemTotal` = **53.92%**, 같은 시각 `/proc/meminfo` 직접 계산 **53.77%** (스크레이프 60초 시차) |
| P5 | journald **전체** | ✅ `yourtrip-app.service` + **OS 유닛 27종** — `cloud-init`, `cloud-final`, `sshd`, `amazon-ssm-agent`, `systemd-networkd`, `dracut-cmdline` 등 |
| P6 | journal 지원 컴파일 확인 | ✅ 메트릭 존재 + 카운터 증가 + **본문이 Loki까지 도달** (아래 곁가지 참고) |
| P7 | 활성 시리즈 | ✅ **717개** (`yourtrip-app` 329 / `yourtrip-host` 388). 기준 3,000의 24%, **free 10,000의 7.2%** — 여유율 92.8% |
| P8 | 전송 실패 0 | ✅ `wal_samples_appended` 증가(21,082 → 22,128 / 30초), `failed` `dropped` `retried` `loki_dropped` **전부 0** |
| P9 | actuator 외부 차단 유지 | ✅ ALB 경유 `/actuator` `/actuator/prometheus` `/actuator/health` **전부 403**, 같은 시각 `localhost:8080`은 **200** |
| P10 | CloudWatch Agent 부재 | ✅ `pgrep` 0건, `rpm -q` 미설치, 역할에 `AmazonSSMManagedInstanceCore` **하나뿐** |
| P11 | 앱 무영향 | ✅ `-Xmx768m -Xss512k`, 프로필 `prod`, `NRestarts=0`, ALB 타깃 `healthy` |
| P12 | 철거 완결성 + 파라미터 생존 | ✅ **39개 전부 destroy**, 고아 0건. SSM `/yourtrip/prod/*` **15개 전원 생존**(그중 `grafana/` 5개) |

**P9가 이 설계의 요점을 보여준다.** 같은 순간에 ALB 경유는 403이고 localhost는 200이다. Alloy가 차단을 **우회**하는 것이 아니라 애초에 **그 경로를 타지 않는다**는 뜻이다.

## P2를 다시 세운 이유

P2의 원래 기준은 **"Alloy RSS ≤ 109.6MB"** 였고 측정값은 245.4MB, 사전 등록한 축소안 3개를 순서대로 적용한 뒤에도 245.4MB(**시리즈 −47%, 메모리 −1.2%**)였다. 규칙대로면 최종 기각(R1)이다.

그런데 그 109.6MB는 이렇게 나온 수다.

```
321.0 (OS 실측)  −  130.6 (CWA RSS)  =  190.4 (CWA 없는 잔여)
190.4 + X ≤ 300   →   X ≤ 109.6
```

**321.0도 130.6도 부하테스트 박스 값이고, Alloy만 운영 박스에서 쟀다.** 게다가 `ps` RSS를 "그 프로세스가 시스템에서 가져가는 양"으로 취급했다. 두 전제 모두 검증된 적이 없었다.

**그래서 프록시를 버리고 원 지표를 직접 쟀다.** 판정선도 새로 만들지 않고 예산식에서 산술로 도출했다(`1244 − 768 = 476`). 이 판단은 CloudWatch Agent를 다시 띄우기 **전에** 커밋했다(`273dec8`) — 근거 전문은 [README.md](README.md#️-p2의-판정-프록시를-폐기한다--계측기를-바꾼-것이지-규칙을-무른-것이-아니다)에 있다.

### 결과적으로 두 전제가 다 틀렸다는 것이 확인됐다

**(가) 박스가 달랐다** — 이 박스는 에이전트를 하나도 안 켜도 OS가 **300.2MB**다. 뺄셈이 가정한 190.4MB와 110MB가 어긋난다.

**(나) RSS는 귀속량이 아니다** — 아래 표가 그 증거다.

## Phase 1 — 두 에이전트를 나란히 (앱 가동, 동시 45분)

**기동 시각 차이 1초.** 순차로 재면 커널 상태·페이지캐시·경과시간이 달라져 또 다른 불공정 비교가 되므로 동시에 띄웠다.

| 지표 | Alloy | CloudWatch Agent | 차이 |
|---|---|---|---|
| `VmRSS` | **240.0 MB** | **131.0 MB** | +109.0 |
| `Pss` | 239.9 MB | 132.2 MB | +107.7 |
| `RssFile` (회수 **가능**) | 183.7 MB | 85.8 MB | +97.9 |
| **`RssAnon`** (스왑 없어 회수 **불가**) | **56.3 MB** | **45.2 MB** | **+11.1** |
| **`Private_Dirty`** | **56.8 MB** | **45.6 MB** | **+11.2** |
| `Private_Clean` | 182.1 MB | 86.6 MB | +95.5 |
| Threads | 9 | 8 | +1 |

**CloudWatch Agent가 131.0MB로 나왔다.** 부하테스트 박스의 130.6MB를 **0.4MB 오차로 재현**했다 — 그 숫자 자체는 틀리지 않았다. 틀린 것은 그 숫자를 쓴 방식이다.

**두 에이전트 모두 RssFile 비중이 크다**(Alloy 76.5%, CWA 65.5%). 그러니 RSS 대 RSS 비교는 성격이 같은 것끼리의 비교였다는 점에서 완전히 무의미하진 않았다. **틀린 것은 그 절대값을 예산식의 `OS` 항에 그대로 더한 것이다.**

### 회수가 실제로 일어나는 것을 관측했다

CloudWatch Agent를 함께 띄우자 **Alloy의 RSS가 저절로 줄었다.**

| Alloy 상태 | `VmRSS` | `RssFile` | `RssAnon` |
|---|---|---|---|
| 단독 (기동 2시간) | 246.4 MB | 188.1 MB | 58.3 MB |
| CWA와 동시 (45분) | 240.0 MB | 183.7 MB | 56.3 MB |
| **차이** | **−6.4** | **−4.4** | −2.0 |

메모리 압력이 늘자 파일 백업 페이지가 먼저 빠졌다. **"`RssFile`은 회수 가능하다"가 문서에서 읽은 이론이 아니라 이 박스에서 직접 본 사실이라는 뜻이다.**

### 45분 시점은 정체 상태였다

| t | Alloy (kB) | CWA (kB) |
|---|---|---|
| +301 | 244,920 | 133,768 |
| +601 | 245,260 | **134,128** |
| +1202 | 246,372 | **134,128** |
| +2103 | 245,528 | **134,128** |

기동 5분 뒤부터 Alloy는 ±0.7MB 안에서 흔들릴 뿐이고 **CWA는 t+601 이후 한 바이트도 움직이지 않았다.** "같은 시점 비교"가 정당하다는 근거다. 사전 등록한 1초×300초 폴링의 최댓값은 **Alloy 240.4MB / CWA 131.0MB**로 위 표와 일치한다.

## Phase 2 — 예산식의 `OS` 항 (앱 정지, 4상태)

**[memory-map.md](../jvm-heap-sizing/memory-map.md) §2와 같은 방법이다** — 앱을 완전히 정지시킨 상태의 `MemTotal − MemAvailable`. 각 상태는 전환 후 60초 안정화 + 240초 샘플링(5초 간격)의 **중앙값**이다(§2는 단일 스냅샷이었다).

| 상태 | 구성 | **`OS`(중앙값)** | min | max | 에이전트 RSS | F0 대비 |
|---|---|---|---|---|---|---|
| **F0** | 없음 | **300.2 MB** | 283.8 | 309.1 | — | — |
| **F1** | Alloy만 | **364.3 MB** | 351.4 | 364.3 | 247.0 MB | **+64.1** |
| **F2** | CWA만 | **345.9 MB** | 337.6 | 346.1 | 135.0 MB | **+45.7** |
| **F3** | Alloy만 (반복) | **353.3 MB** | 344.5 | 361.4 | 243.3 MB | +53.1 |

**히스테리시스 검사 통과** — F1과 F3의 차이가 11.0MB(3.0%)로 사전 기준 5% 이내다. 차이의 방향도 설명된다: 측정이 진행되며 `Cached`가 883,644 → 890,924 kB로 자라 `MemAvailable`이 함께 늘었다. `drop_caches`를 쓰지 않은 것은 §2가 쓰지 않았기 때문이고, 그 대가를 F3이 정량화했다.

### `MemAvailable` 델타가 `RssAnon`과 맞아떨어진다

| | `RssAnon` | `MemAvailable` 델타 (F*−F0) | 차이 |
|---|---|---|---|
| Alloy | 57.8 MB | +64.1 / +53.1 MB | ±6 |
| CloudWatch Agent | 45.0 MB | **+45.7 MB** | **0.7** |

**서로 다른 두 경로가 서로를 교차검증한다.** 커널이 파일 백업 페이지를 `MemAvailable`에 넣어 세기 때문에, 시스템이 실제로 잃는 양은 RSS가 아니라 익명 페이지에 가깝다. CloudWatch Agent는 0.7MB 안에서 일치했다.

**이것이 이번 측정의 핵심이다.** RSS로 보면 Alloy가 109MB 무겁지만, 박스가 실제로 잃는 양의 차이는 그 **8분의 1**이다.

### Q1 — 판정: **Q1-B, 채택**

```
OS_alloy = 364.3 MB   (F1, 보수적으로 큰 쪽)
```

| 선 | 값 | 판정 |
|---|---|---|
| 300 MB (README §5 기각선) | 초과 | `-Xmx768m` 근거 **유지** |
| **476 MB (예산식 한계)** | **111.7 MB 여유** | **`-Xmx768m` 보장됨 → 채택** |

**보수 보정도 통과한다.** 사전 등록한 보정식 `OS + (RSS[앱 가동] − RSS[앱 정지])`의 보정항이 **음수**(240.4 − 247.0 = −6.6MB)로 나왔다 — 앱이 멈추면 메모리 압력이 사라져 Alloy의 파일 페이지가 회수되지 않고 오히려 커진다. 즉 **F1 값 자체가 이미 보수적이다.**

### Q2 — 비교: Alloy의 대가는 **+12.9 MB**

```
Δ = OS_alloy − OS_cwa = 358.8 − 345.9 = +12.9 MB      (F1만 쓰면 +18.4 MB)
```

이 13MB로 얻는 것은 이렇다.

| | CloudWatch Agent | Alloy |
|---|---|---|
| 지표 | `mem_used_percent` **1개** | **717 시리즈** (JVM·HTTP·풀·호스트 전체) |
| 로그 | 없음 | journald **전체** (28 유닛) |
| 비용 | 시리즈당 월 $0.30 | **$0** |

### 그리고 `-Xmx768m`은 Alloy 때문이 아니다

**Q2가 원인 귀속에 쓰이도록 설계한 것이 여기서 값을 했다.**

```
F0 (에이전트 없음)   300.2 MB   ← 이미 기각선(300) 위
F2 (CWA 그대로)      345.9 MB   ← 되돌려도 초과
F1 (Alloy)           364.3 MB
```

**에이전트를 하나도 켜지 않아도 300MB 선은 발동한다.** 즉 CloudWatch Agent를 걷어낸 것이 `-Xmx`를 묶은 원인이 아니고, 되돌려도 풀리지 않는다. 예산식으로 확인해도 같다.

| 구성 | `OS` | 힙 여지 (`1244 − OS`) | 64MB 내림 |
|---|---|---|---|
| 에이전트 없음 | 300.2 | 943.8 | 896 MB |
| CWA | 345.9 | 898.1 | 896 MB |
| **Alloy** | **364.3** | **879.7** | **832 MB** |

세 구성 모두 768보다 큰 여지를 주지만, **300MB 기각선이 사후 완화 금지 원칙에 따라 그대로 발동해 있으므로 채택값은 `-Xmx768m`이다.**

> **`-Xmx1024m`은 애초에 불가능했다.** 예산식이 1024를 보장하려면 `OS ≤ 220MB`여야 하는데, **에이전트 없는 F0가 300.2MB다.** "CloudWatch Agent를 걷어내면 힙을 올릴 수 있다"는 [README.md](README.md)의 기대(P2-A)는 **어느 에이전트를 쓰든 성립하지 않았다.** 후속 이슈로 분리해 둔 `-Xmx` 재판정은 이 사실을 출발점으로 삼아야 한다.

## P12 상세 — 철거와 생존

**측정을 위해 CLI로 한 조작이 terraform 형상을 하나도 건드리지 않았다.** `plan -destroy`가 `0 to add, 0 to change, 39 to destroy`를 냈다. ASG 프로세스 suspend/resume, 인스턴스에 CloudWatch Agent 설치·제거는 **실행 상태 조작이지 형상 변경이 아니다**([CLAUDE.md](../../../CLAUDE.md)의 "인프라 변경은 반드시 terraform을 거친다")는 원칙이 drift 0으로 실증됐다.

| 확인 | 결과 |
|---|---|
| ALB / 타깃 그룹 / ASG / RDS / ElastiCache / EIP / NAT | **전부 0** |
| SG · Launch Template · DB Subnet Group · Cache Subnet Group · RDS 스냅샷 | **전부 0** |
| `yourtrip-prod-app-ec2-role` | 삭제됨 |
| SSM `/yourtrip/prod/*` | **15개 생존** (`env/` 8, `grafana/` 5, `artifact_key`, `cloudfront_private_key`) |
| `prod-permanent` 자산 (호스티드존·ACM·S3 4개·CloudFront·OIDC 역할) | **그대로 생존** |

**재apply 때 Grafana Cloud 접속 정보를 다시 넣을 필요가 없다**는 주장이 이것으로 실증됐다. 시크릿을 terraform 리소스로 만들지 않았기 때문이다 — 만들었다면 destroy와 함께 사라지고 tfstate에 평문으로도 남았을 것이다.

> **이번 작업과 무관한 리소스가 하나 남아 있다.** 기본 VPC의 `capstone`(t3.micro, **stopped**, 2026-07-14 생성)과 그 20GB EBS다. `terraform/prod`가 만든 VPC와 다른 곳에 있고 생성 시점도 6주 앞서므로 이 작업의 고아가 아니다. 다만 **정지 상태여도 EBS는 과금된다**(월 약 $1.6).

## 마주친 문제

구축 과정에서 넷을 만났다. 셋은 apply/부팅을 실제로 깨뜨렸고, 하나는 **아무 오류 없이 조용히 틀린 값을 만들고 있었다.**

### 1. user_data 16KB 상한 — 한국어 주석이 넘겼다

Alloy 설치 섹션과 `config.alloy` 주입을 추가하자 apply가 그 자리에서 깨졌다.

```
InvalidUserData.Malformed: User data is limited to 16384 bytes.
```

합계 **28,041바이트**였다. 원인은 분량 자체가 아니라 **인코딩**이다 — 이 저장소는 주석을 한국어로 쓰는데 UTF-8에서 한글은 **글자당 3바이트**다. 영어 주석이었다면 같은 설명이 3분의 1이었다.

주석을 지워서 줄이는 선택지는 택하지 않았다. 그 주석들이 이 저장소가 남기려는 것 자체이기 때문이다. 대신 **`base64encode`를 `base64gzip`으로 바꿨다** — cloud-init이 매직 넘버를 보고 알아서 푼다. **10,320바이트**가 되어 여유가 37% 남았다. 다만 이건 상한을 미룬 것이지 없앤 것이 아니다.

### 2. Grafana RPM 저장소 — 공식 문서대로 하면 설치가 실패한다

부팅은 됐는데 Alloy가 없었다.

```
Failed to download metadata for repo 'grafana':
  repomd.xml GPG signature verification error: Bad GPG signature
Ignoring repositories: grafana
Error: Unable to find a match: alloy-1.18.1-1
```

Grafana 공식 설치 문서가 `repo_gpgcheck=1`을 적고 있는데 **AL2023에서는 이 검증이 실패한다.** 더 나쁜 것은 dnf가 **저장소를 조용히 무시하고 넘어간 뒤** 다음 줄에서 "패키지를 못 찾겠다"로 실패한다는 점이다 — 진짜 원인이 한 줄 위에 있다.

```ini
repo_gpgcheck=0   # repomd.xml(저장소 메타데이터) 서명 — AL2023에서 실패한다
gpgcheck=1        # 패키지 자체의 서명 — 이건 유지한다
```

**둘은 다른 것을 검증한다.** 앞의 것만 끄므로 설치되는 RPM의 진위 확인은 그대로 남는다.

### 3. `job_name`이 조용히 무시됐다

Alloy는 정상이고 오류도 없는데, 호스트 지표가 `job="yourtrip-host"`가 아니라 **`job="integrations/unix"`** 로 들어왔다. `prometheus.exporter.unix`가 내보내는 target에 이미 `job` 라벨이 붙어 있고, **그것이 `prometheus.scrape`의 `job_name`을 이긴다.**

```alloy
discovery.relabel "host" {
  targets = prometheus.exporter.unix.host.targets
  rule { target_label = "job";      replacement = "yourtrip-host" }
  rule { target_label = "instance"; replacement = constants.hostname }
}
```

`instance`를 덮어쓰려고 이미 `discovery.relabel`을 두고 있었으므로 규칙 하나를 더한 것으로 끝났다. **`job_name`만 적어 두면 맞을 것이라고 믿으면 안 된다** — 대시보드가 빈 화면으로 나온 뒤에야 알게 된다.

### 4. 조용히 틀린 값을 재고 있었다 — P2의 판정 프록시

앞의 셋과 성격이 다르다. **apply도 부팅도 성공했고 숫자도 나왔는데, 그 숫자가 답하는 질문이 우리가 묻던 질문이 아니었다.**

| 결함 | 내용 |
|---|---|
| **다른 박스** | 뺄셈에 쓴 321.0MB와 130.6MB가 **둘 다 부하테스트 박스** 값인데 Alloy만 운영 박스에서 쟀다. 실제로 이 박스의 OS 기저는 300.2MB로 뺄셈이 가정한 190.4MB와 110MB 어긋났다 |
| **다른 것을 셈** | RSS 109MB 차이의 **90%가 회수 가능한 파일 백업 페이지**였다. 시스템이 실제로 잃는 차이는 12.9MB다 |

**오류 메시지가 없다는 점이 이 문제를 가장 위험하게 만든다.** 앞의 세 문제는 실패가 눈에 보였지만, 이건 그럴듯한 숫자가 나와서 그대로 결론이 될 뻔했다. 지적이 없었으면 R1(최종 기각)으로 갔을 것이고, **되돌린 CloudWatch Agent가 사실은 더 나은 선택도 아니었다.**

### 곁가지 — 한가한 박스에서는 P6이 판정되지 않는다

P6은 `loki_source_journal_target_lines_total`이 "존재하고 30초 간격 2회 관측에서 증가"할 것을 요구한다. 그런데 트래픽이 없는 박스는 **journald에 새 줄이 안 생겨서** 카운터가 그대로다(268 → 268).

기준을 완화하지 않고 **계측 쪽을 고쳤다** — `logger`로 5줄을 만들고 다시 셌다(281 → 287). 그리고 그 5줄이 **Loki까지 도달한 것**을 본문 검색으로 확인했다. P6의 취지가 "stub 빌드와 실제 구현을 가르는 것"이므로, 박스가 한가한 것은 판정 대상이 아니라 계측 조건의 문제였다.

## 한계

- **왜 이 박스의 OS 기저가 300.2MB인지 규명하지 않았다.** 부하테스트 박스는 CloudWatch Agent를 **포함해서** 321MB였는데 이 박스는 에이전트 없이 300.2MB, CWA를 켜면 345.9MB다. 프로세스 표를 보면 `systemd-journald`가 부하테스트 박스에서 68.7MB였고 여기서는 12.8MB인데, 그 차이만으로는 설명되지 않는다. **예산식의 `OS` 항이 박스마다 50MB씩 흔들린다면 그 자체가 별도로 볼 문제다.**
- **Alloy를 45분까지만 관찰했다.** 곡선이 5분 이후 평평했으므로 정체 상태로 보지만, remote_write WAL은 2시간마다 잘린다. **그보다 긴 주기의 거동은 재지 않았다.**
- **부하 없는 상태의 값이다.** DNS를 끈 채 검증했으므로 실트래픽이 없다. 앱이 바쁠 때 Alloy의 스크레이프·직렬화 부하가 얼마나 늘어나는지는 재지 않았다.
- **`Private_Clean`이 압력 아래서 어디까지 회수되는지는 보지 않았다.** CWA를 함께 띄웠을 때 4.4MB가 빠지는 것을 관측했을 뿐이고, 실제 OOM 직전까지 몰아본 것이 아니다. **"회수 가능하다"와 "전부 회수된다"는 다르다.**
- **대시보드를 Grafana Cloud에 실제로 import해 보지는 않았다.** 40개 쿼리를 API로 던져 39개가 데이터를 반환하는 것까지 확인했지만(빈 하나는 5xx율 — 트래픽이 없어 정상), 패널 렌더링은 사람이 확인해야 한다.
- **로그 볼륨을 추정하지 않았다.** free 50GB 대비 여유가 크다고 보지만 실측하지 않았다.

## 참고 문서

| 문서 | 이 문서에서 쓴 내용 |
|---|---|
| [README.md](README.md) | 설계, 후보 비교, 사전 등록 판정 기준, P2 프록시 폐기 근거 |
| [jvm-heap-sizing/memory-map.md](../jvm-heap-sizing/memory-map.md) | OS+에이전트 321MB, CloudWatch Agent 130.6MB, 측정 방법(§2)과 예산식(§6) |
| [jvm-heap-sizing/README.md](../jvm-heap-sizing/README.md) | `-Xmx` 예산 산정식과 300MB 기각선 |
| [prod-infra-iac/verification.md](../prod-infra-iac/verification.md) | ALB `/actuator` 403 차단의 원 검증, 이 문서의 형식 |
| [guide/monitoring.md](../../guide/monitoring.md) | 운영 수집 구조와 확인 절차 |
| [deploy/prod/config.alloy](../../../deploy/prod/config.alloy) | Alloy 설정 정본 |
