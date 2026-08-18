# 측정 환경과 신뢰성 검증

> 캐싱 효과 측정([ec2-measurement.md](ec2-measurement.md))이 **어떤 조건에서 이뤄졌고 그 결과를 믿을 수 있는지**를 다루는 문서다. 결과 자체는 그쪽에, 시나리오별 원본 표는 [scenarios.md](scenarios.md)에 있다.
>
> 계획 §8이 요구한 검증 8가지 중 사후 확인이 필요했던 **7(단계 경계 정합성)·8(크레딧 고갈)** 을 여기에 정리했다. 둘 다 통과다.

## 측정 환경

| | 값 |
|---|---|
| App EC2 | **t3.small** (`i-06bc413840b95ca09`) |
| RDS | db.t3.micro, PostgreSQL **16.13** |
| ElastiCache | cache.t3.micro, Redis 7.1 |
| 부하 생성기 | k6 EC2 t3.micro (`i-0553d4c359e3caaa8`), k6 **v2.0.0** |
| 네트워크 | App·k6·RDS는 `ap-northeast-2a`, **ElastiCache만 `ap-northeast-2c`**(아래 참고). k6→App은 **프라이빗 IP**(`10.42.1.159`) |
| HikariCP | 풀 크기 **10** (기본값, 미설정) |
| Tomcat | maxThreads **200** (기본값, 미설정) |
| Spring 프로필 | **`prod`** — 매 run 기동 로그로 검증 |
| Hibernate 통계 | **상시 ON** (`generate_statistics: true`) |
| 시드 | `seed-benchmark.sql` + `seed-popular.sql` — upload_course 3,000건, `view_count = 3000 - id` |
| 코드 기준점 | 단일 커밋·단일 JAR. arm은 프로퍼티 2개 조합으로만 전환 |

**변수 통제**: 세 arm은 같은 JAR을 쓰고 `yourtrip.benchmark.upload-course-cache`(enabled/disabled)와 `.upload-course-tx`(separated/wrapped)만 바뀐다. 커밋 checkout 방식은 그 사이에 낀 CloudFront 전환·presign 개선이 함께 딸려와 "바뀐 변수가 하나"를 지킬 수 없어 택하지 않았다.

| arm | 캐시 | 트랜잭션 경계 | 대응하는 실제 코드 상태 |
|---|---|---|---|
| **A0** | 없음 | 메서드 전체 | 캐싱 도입 이전 |
| **A1** | 있음 | 메서드 전체 | `7e74d0d`/`604d3a4` ~ 트랜잭션 분리 직전 |
| **A2** | 있음 | Reader만 | **현재 운영 (기본값)** |

**전환 절차**는 `scripts/loadtest/switch-arm.sh`와 동일한 순서를 자동화했다 — 프로퍼티 교체 → 재기동 → **활성 프로필 검증** → 재시딩(`DB_DDL_AUTO=create`라 재기동마다 스키마가 DROP/CREATE된다) → `FLUSHALL` → 랭킹 8키 워밍(캐시 arm만).

> 이 스크립트와 `yourtrip.benchmark.*` 토글은 **측정이 끝난 뒤 제거했다.** 재현하려면 `d144126`을 checkout한다 — 자세한 이유는 [README.md](README.md#측정-장치는-측정이-끝난-뒤-제거했다) 참고.

**지표 수집**: App EC2의 `/actuator/prometheus`를 **1초 간격으로 직접 폴링**했다(run당 350~370 샘플). 커넥션 대여 횟수는 `hikaricp_connections_usage_seconds_count`의 증분을 요청 수 증분으로 나눠 구했다 — `active`는 순간값이라 빌렸다 즉시 반납하는 캐시 히트 경로에서는 폴링 간격 사이에 잡히다 만다.

---

## 환경 건전성 검증 (계획 §8의 검증 7·8)

측정을 마친 뒤 CloudWatch를 **사후 조회**해 확인했다. run별 `.window` 파일에 epoch 시작·종료가 남아 있어 가능했다(상세 모니터링 1분 해상도).

| 대상 | 지표 | 전 run 범위 | 판정 |
|---|---|---|---|
| **App EC2** (t3.small) | CPU 평균 | 36.0 ~ 93.2% | — |
| | **`CPUCreditBalance`** | **전 run 0.0** | ⚠️ 아래 참고 |
| | `CPUSurplusCreditBalance` | 2 → 101 (증가) | 상한 576의 18% |
| **k6 EC2** (t3.micro) | CPU 평균 | 4.9 ~ **53.9%** | ✅ 여유 |
| | `CPUCreditBalance` | 219.5 → 160.4 (**0 도달 없음**) | ✅ |
| **ElastiCache** (cache.t3.micro) | `EngineCPUUtilization` 최대 | 0.3 ~ **9.3%** | ✅ 여유 |
| | `CurrConnections` 최대 | 6 ~ 14 | — |
| **RDS** (db.t3.micro) | CPU 평균 | A0 15.4~62.3% / 캐시 arm 3.8~5.6% | ✅ |
| | `CPUCreditBalance` | 160 ~ 288 (0 도달 없음) | ✅ |
| | `DatabaseConnections` 최대 | 10 ~ 11 | 풀 크기와 일치 |

### 검증 8 — 크레딧 고갈이 결과를 오염시키지 않았다

**App EC2의 크레딧은 첫 run부터 0이었다.** 계획이 "크레딧 고갈이 성능 저하로 나타나면 캐싱 효과가 아니라 크레딧 고갈을 측정한 것이 된다"고 경고한 조건 그대로다.

**그러나 두 인스턴스 모두 크레딧 모드가 `unlimited`이라 스로틀링은 없었다.** `unlimited`에서는 잔고가 0이어도 surplus를 빌려 베이스라인 이상을 유지하고 초과분을 과금한다. 실측 CPU가 t3.small 베이스라인(40%)을 훨씬 웃도는 65~93%로 지속된 것이 그 증거이고, `CPUSurplusCreditBalance`가 존재한다는 것 자체가 `unlimited`에서만 나오는 신호다. 최대 101로 스로틀링이 시작되는 상한(576 = 24 credits/h × 24h)의 18%에 그쳤다.

> **다만 이 설정은 terraform에 고정돼 있지 않다.** `credit_specification`이 `.tf` 어디에도 없어 AWS의 T3 기본값(`unlimited`)에 의존한다. 계정 기본값이 바뀌거나 `standard`로 뜨면 **같은 스크립트가 조용히 다른 결과를 낸다.** 명시적으로 고정하는 것이 안전하다.

#### 게스트 OS 층도 확인했다 — CFS quota와 steal time

크레딧 모드는 **하이퍼바이저 층**의 근거다. 게스트 안에서 cgroup CFS quota 같은 별도 스로틀링이 걸렸을 가능성은 그것만으로 배제되지 않아, 부하가 걸린 상태에서 직접 확인했다.

| 확인 | 결과 |
|---|---|
| 컨테이너 여부 | `systemd-detect-virt -c` = **none** — Docker `--cpus`가 낄 자리가 없다 |
| systemd 유닛 | `CPUQuotaPerSecUSec=infinity`, `CPUWeight`·`CPUShares` 미설정 |
| 서비스 cgroup (v2) | `cpu.max` = **`max 100000`** (= 무제한) |
| 상위 `system.slice` | `cpu.max` = **`max 100000`** |
| **스로틀링 카운터** | **`nr_periods 0` / `nr_throttled 0` / `throttled_usec 0`** |

`nr_periods`가 0인 것이 결정적이다 — quota가 설정돼 있으면 스로틀링이 없어도 period는 계속 도는데, 0이라는 것은 **CFS 대역폭 컨트롤러가 활성화된 적조차 없다**는 뜻이다.

**더 직접적인 근거는 steal time이다.** 하이퍼바이저가 vCPU를 조이면 게스트는 그것을 `%steal`로 본다.

```
10초 구간(부하 중):  user 75.84  system 12.53  softirq 5.19  idle 6.04  steal 0.35
부팅 이후 누적(2h24m): steal 0.6753%
```

`standard` 모드에서 크레딧이 마르면 baseline(vCPU당 20%)으로 조여져 **steal이 50~80%대**로 뜬다. 0.35%는 그 근처도 아니고, user+system+softirq = **93.6%가 실제 작업**이다.

**`process_cpu_usage` 자체도 반증이다.** 이 지표는 `OperatingSystemMXBean.getProcessCpuLoad()`로 **프로세스가 소비한 CPU 시간 ÷ (경과시간 × vCPU 수)** 이므로, 스로틀링되면 분자가 줄어 값이 **내려간다.** 관측된 0.96은 "2 vCPU의 96%를 실제로 썼다"는 뜻이고 40% baseline에서는 산술적으로 나올 수 없다.

> **소급 범위의 한계.** `nr_throttled`는 서비스 재시작마다 리셋되므로 위 값은 **측정 시점에 돌던 run만** 덮는다. 정적 설정(`cpu.max`, systemd 유닛)은 전 run에 적용되지만, steal 누적은 **해당 부팅 세션(2시간 24분)** 까지다. 8/16과 8/17 오전 run은 다른 부팅 세션이라 소급 확인이 안 되고, CloudWatch `CPUUtilization`이 65~93%로 baseline(40%)을 지속 상회했다는 간접 증거로 대신한다.
>
> **하네스가 steal을 수집하지 않는다.** `/proc/stat`은 Actuator에 노출되지 않아 별도 경로가 필요하다. 다음 측정에서는 폴링에 넣는 것이 맞다.

### 검증 7 — 단계 경계가 밀리지 않았다

`agg.py`는 VU 단계를 `t0 + 경계`로 슬라이스하는데, `t0`는 k6를 띄우기 직전에 기록되므로 ssh·k6 기동 지연만큼 모든 경계가 밀릴 수 있다. 폴링 시계열에서 요청 수가 실제로 오르기 시작한 시점을 찾아 확인했다.

**25개 run 전부 오차 0~1초(중앙값 0초)** 였다. 단계별 슬라이스는 유효하다.

---


## 운영 중 겪은 사고 (재실행 시 주의)

측정 자동화에서 **배치 두 개가 동시에 실행돼 3개 run을 폐기**했다. 원인이 연쇄적이라 기록해 둔다.

1. **자기 자신을 매칭한 대기 조건.** 배치 완료를 "로그에서 특정 문자열 세기"로 판정했는데 대기 메시지에 그 문자열을 넣어, 자기가 쓴 줄이 자기 조건을 만족시켜 즉시 통과했다.
2. **분리된 프로세스가 죽지 않았다.** 상위 태스크를 중단해도 손자 프로세스가 살아남았고, Git Bash의 `pkill -f`가 Windows 프로세스를 잡지 못해 "정리됨"이라는 잘못된 확인을 받았다. PowerShell로 PID 트리를 종료해야 했다.
3. **동시 arm 전환이 앱을 죽였다.** 두 배치가 각자 `/opt/app/.env`에 같은 키를 append했고, **spring-dotenv가 중복 키에서 `IllegalStateException`을 던져** 앱이 67회 재시작 루프에 빠졌다.

대응으로 (a) 배치에 `mkdir` 기반 원자적 락을 넣어 동시 실행을 차단하고, (b) 체인 구조를 폐기해 단일 배치로 통합했으며, (c) arm 전환마다 `.env` 중복 키를 제거하는 스크립트를 App EC2에 상주시켰다.

**증상이 "앱이 연결을 거부한다"로 나타나 처음에는 부하로 인한 포화로 오인했다.** `systemctl` 상태가 `143`(SIGTERM)인 것을 보고서야 외부에서 재기동시켰음을 알았다 — 크래시와 의도적 재기동을 구분하는 데 종료 코드가 결정적이었다.

### 세션을 나눠 재개할 때 — 퍼블릭 IP 두 종류가 모두 바뀐다

Phase 2를 별도 세션으로 재개하면서 **접속이 전부 막혔다.** 원인이 두 가지가 겹쳐 있었다.

1. **EC2의 퍼블릭 IP가 바뀐다.** stop/start마다 재할당되므로 측정 하네스의 대상 주소를 갱신해야 한다. 프라이빗 IP는 그대로다.
2. **개발 머신의 공인 IP도 바뀐다.** 보안그룹의 SSH·8080 인바운드 규칙이 옛 IP를 가리켜 **SSH와 Prometheus 폴링이 동시에 막혔다.** 이 규칙 3개는 `var.my_ip_cidr`을 쓰는 **terraform 관리 대상**이라 CLI로 직접 고치면 drift가 된다. `terraform.tfvars`를 고치고 `-target`으로 세 규칙만 apply하는 것이 맞다(`3 added, 3 destroyed` — SG 규칙은 불변 리소스라 CIDR 변경이 replace로 표시되며, 인스턴스는 건드리지 않는다).

하네스에서는 **퍼블릭 IP가 두 스크립트에 각각 하드코딩돼 있어** 한쪽만 갱신하면 죽은 IP로 ssh를 시도하는 구조였다. 재개 전에 한 곳으로 모았다.

## 인프라 정리

- ⚠️ **terraform user_data drift가 남아 있다.** dev 머지가 `templates/app-user-data.sh.tpl`에 `SPRING_PROFILES_ACTIVE=prod`를 추가했고 `user_data_replace_on_change = true`라, 다음 `terraform apply` 시 **App 인스턴스가 교체된다**(배포된 JAR·CloudFront 개인키 유실). 이번에도 `/opt/app/.env`를 직접 고쳐 우회했고, 보안그룹 규칙만 `-target`으로 적용했다.
- 측정용 개발 IP 보안그룹 규칙 3개는 아직 회수하지 않았다(현재 `220.65.239.41/32`).
- `terraform.tfstate`·`terraform.tfvars`가 이번 apply로 바뀌었다. **메인 워킹트리 사본과 동기화가 필요하다**([worktree.md](../../guide/worktree.md)) — 규칙 회수까지 끝낸 뒤 한 번에 맞춘다.
- RDS·ElastiCache는 관행대로 유지(정지 불가/제약)하며 **과금이 계속된다.**


## 참고 문서

- [ec2-measurement.md](ec2-measurement.md) — 결론과 판정
- [scenarios.md](scenarios.md) — 시나리오별 원본 표
- [redis-io-bottleneck.md](redis-io-bottleneck.md) — A2 포화 주체 규명
- [guide/ec2-rds-loadtest.md](../../guide/ec2-rds-loadtest.md) — EC2 분리 환경 실행 절차
