# 운영 배포 설정

운영 서버(`yourtrip.cloud`)의 구성 중 **저장소가 근거와 함께 들고 있어야 하는 것**을 모아 둔 곳이다. JVM 기동 옵션과 systemd 유닛은 인스턴스가 부팅할 때 그대로 주입받고, instance refresh 조건은 terraform과 CD가 함께 읽는다.

## 왜 이 디렉터리가 필요한가

`-Xmx`·`-Xss` 같은 JVM 옵션은 **`java` 프로세스의 명령줄 인자**다. JVM이 이미 그 값으로 뜬 뒤에야 Spring이 `application-prod.yml`을 읽으므로, **Spring 설정 파일에는 담을 수가 없다.** `server.tomcat.threads.max: 32`는 yml에 넣을 수 있지만 `-Xmx`는 원리상 불가능하다.

그래서 "`java`를 실행하는 지점"에 있어야 하는데, 이 저장소에 그런 곳이 **부하테스트용 하나뿐이었다**(`terraform/loadtest/templates/app-user-data.sh.tpl`). 운영 서버는 Terraform 관리 밖이라 그 템플릿과 무관하고, 결과적으로 **운영이 어떤 힙 상한으로 도는지 저장소가 알 수 없는 상태**였다. #101에서 t3.small 기준으로 재산정한 값도 운영에는 닿지 못했다.

이 디렉터리가 그 자리를 메운다.

## 파일

| 파일 | 역할 |
|---|---|
| [jvm-opts.env](jvm-opts.env) | **JVM 옵션의 정본.** 값과 산정 근거가 함께 있다. systemd `EnvironmentFile`로 읽힌다 |
| [yourtrip-app.service](yourtrip-app.service) | systemd 유닛. 부하테스트에서 검증된 구조를 옮긴 것이다 |
| [instance-refresh-preferences.json](instance-refresh-preferences.json) | **인스턴스 교체 조건의 정본.** terraform과 CD 워크플로가 **같은 파일을 읽는다** — 아래 참고 |
| [config.alloy](config.alloy) | **Grafana Alloy 설정의 정본.** 앱 지표(`localhost:8080/actuator/prometheus`)·호스트 지표·journald 로그를 Grafana Cloud로 보낸다. user-data가 `/etc/alloy/config.alloy`로 주입한다 |

**JVM 옵션을 별도 파일로 나눈 이유**는 `/opt/app/.env`가 DB 비밀번호·API 키를 담고 있어 git 밖에 있어야 하기 때문이다. JVM 옵션은 비밀이 아니라 **근거를 남겨야 하는 설정**이므로 반대로 저장소가 들고 있어야 한다. systemd는 `EnvironmentFile=`을 여러 줄 쓸 수 있어 둘을 나란히 읽을 수 있다.

## 현재 값

```
JVM_OPTS=-Xmx768m -Xss512k
```

`-Xmx768m`은 **t3.small(vCPU 2 / 2GB) 기준 실측값**이다. 산정 근거는 `jvm-opts.env`의 주석과 [docs/tasks/jvm-heap-sizing/](../../docs/tasks/jvm-heap-sizing/README.md)에 있다.

> **힙을 키운다고 빨라지지 않는다.** 실측에서 `-Xmx`를 448 → 768 → 1024로 올려도 G1이 실제로 커밋한 힙은 227MB 부근에서 움직이지 않았다. 이 값의 목적은 성능이 아니라 **근거의 복원**이다 — 종전 448m은 t3.micro(1GB) 전제로 잡힌 값인데다 JVM 기본값(480MB)의 93%라 사실상 아무 통제도 하지 않고 있었다.

## 적용

**정상 경로는 `terraform apply`다.** user-data가 이 디렉터리의 파일을 `file()`로 읽어 인스턴스에 넣으므로, 파일을 고치고 apply하면 Launch Template이 새 버전이 되고 ASG가 롤링으로 교체한다. 먼저 띄우고 나중에 죽이므로 **서비스 중단이 없다.**

```bash
terraform -chdir=terraform/prod plan -out=tfplan && terraform -chdir=terraform/prod apply tfplan
```

> **JAR을 바꾸는 것과는 경로가 다르다.** 새 코드 배포는 `dev` 머지로 CD가 처리하며 terraform을 거치지 않는다([docs/guide/cd.md](../../docs/guide/cd.md)). 이 디렉터리의 파일은 **인스턴스의 형상**이라 terraform이, JAR은 **배포 대상**이라 CD가 담당한다. 그래서 여기를 고치면 `apply`가 필요하고, 코드를 고치면 머지만으로 나간다.

### 이미 떠 있는 서버를 손으로 고칠 때

**앱을 재기동하므로 서비스 중단이 발생한다.** 게다가 다음 인스턴스 교체 때 사라지는 임시 변경이다 — 항구적으로 반영하려면 위 절차를 쓴다.

```bash
scp deploy/prod/jvm-opts.env ec2-user@<운영 서버>:/tmp/jvm-opts.env
```

```bash
sudo mv /tmp/jvm-opts.env /opt/app/jvm-opts.env && sudo chown root:root /opt/app/jvm-opts.env
```

유닛이 아직 `$JVM_OPTS`를 읽지 않는다면 유닛도 함께 반영한다. 기존 유닛을 통째로 바꾸는 대신 **`EnvironmentFile`과 `ExecStart` 두 줄만 맞춰도 된다.**

```bash
sudo systemctl daemon-reload && sudo systemctl restart yourtrip-app
```

## 확인 (필수)

**적용됐다고 가정하지 않는다.** `JVM_OPTS`가 비어 있으면 `$JVM_OPTS`가 빈 문자열로 사라져 JVM이 조용히 ergonomics 기본값(t3.small에서 480MB)으로 뜬다. 768m과 눈으로 구분되지 않으므로 기계로 확인해야 한다. 두 갈래로 본다.

**(1) 플래그가 프로세스에 전달됐는가**

```bash
tr '\0' ' ' < /proc/$(systemctl show -p MainPID --value yourtrip-app)/cmdline; echo
```

`/usr/bin/java -Xmx768m -Xss512k -jar /opt/app/app.jar`가 나와야 한다.

**(2) JVM이 그 값을 반영했는가**

```bash
curl -sf http://localhost:8080/actuator/prometheus | awk '/^jvm_memory_max_bytes\{/ && /area="heap"/ { v=$NF+0; if (v>0) s+=v } END { printf "%.0f\n", s }'
```

`805306368`(= 768MB)이 나와야 한다. G1은 Eden/Survivor의 max를 `-1`로 내보내고 Old Gen에만 실제 상한을 싣기 때문에 **양수만 더한다** — 이 항등식(양수 풀 max의 합 = `Runtime.maxMemory()`)은 `LoadtestMetricsExposureTest`가 잠그고 있다.

프로필도 함께 확인한다. 이 한 줄이 빠지면 배포 서버가 조용히 `local`로 떠서 SQL을 전량 로깅한다([profile.md](../../docs/guide/profile.md) §4).

```bash
sudo journalctl -u yourtrip-app -n 200 --no-pager | grep -i profile
```

**(3) Alloy가 실제로 관측하고 있는가**

에이전트가 살아 있는 것과 지표·로그가 실제로 나가는 것은 다르다. 두 가지를 따로 본다.

```bash
systemctl is-active alloy && systemctl show -p NRestarts --value alloy
```

`active`와 `0`이 나와야 한다. 재시작 횟수가 쌓이고 있으면 OOM이거나 설정 오류다.

```bash
curl -s localhost:12345/metrics | grep -c loki_source_journal_target_lines_total
```

**`0`이면 로그를 한 줄도 못 읽고 있다.** `loki.source.journal`은 빌드 태그(`promtail_journal_enabled`) 뒤에 있어서, 태그가 없는 빌드에서는 컴포넌트가 **오류 없이 등록만 되고 아무것도 하지 않는다.** 로그가 안 올라오는데 `systemctl status`는 멀쩡한 상황이 이것이다. 공식 RPM은 태그를 켜므로 정상이면 1 이상이 나온다.

**설정 파일을 고쳤다면 커밋 전에 확인한다.** `config.alloy`는 terraform이 `templatefile()`로 읽으므로, 달러나 퍼센트 뒤에 중괄호가 오는 표기가 하나라도 있으면 **apply가 그 자리에서 깨진다**(주석 안이라도 마찬가지다).

```bash
grep -n '[$%]{' deploy/prod/config.alloy
```

아무것도 출력되지 않아야 한다.

## 스펙을 바꾸면 이 값은 무효다

`-Xmx768m`은 **t3.small(2GB) 전용**이다. 인스턴스를 바꿨다면 그대로 쓰지 않는다.

- **더 작은 스펙(t3.micro 1GB 등)**: 힙 768MB + 논힙 190MB + 네이티브 165MB로 물리 메모리를 넘겨 **OOM이 난다.** 게다가 메모리가 server-class 문턱(1,792MB) 아래로 내려가 **GC가 G1에서 SerialGC로 바뀌므로**, 예산의 "G1 자료구조 43MB" 항목부터 다시 재야 한다.
- **더 큰 스펙**: OOM 위험은 없지만 힙이 물리 메모리 대비 지나치게 보수적이 된다. [예산 산정식](../../docs/tasks/jvm-heap-sizing/README.md)으로 다시 계산한다.

## 한계

- **`-Xmx768m`의 근거가 되는 실측은 부하테스트 환경에서 이뤄졌다.** 같은 t3.small이지만 운영 트래픽 패턴(AI 코스 생성 등 할당이 큰 경로)은 재지 않았다. 힙 밖 항목 중 메타스페이스·심볼은 로드되는 클래스 수에 따라 더 자랄 수 있다.

> 이전에 여기 적혀 있던 한계 하나는 #120으로 해소됐다 — "빌드와 업로드는 여전히 수동이다"는 더 이상 사실이 아니다. `dev`에 머지하면 CD가 빌드·업로드·교체까지 수행한다([docs/guide/cd.md](../../docs/guide/cd.md)). 그 과정에서 배포할 JAR의 키가 `terraform.tfvars`에서 SSM 파라미터로 옮겨졌으므로, **이 저장소에서 "지금 무엇이 배포돼 있나"는 tfvars가 아니라 `terraform output current_artifact_key`로 확인한다.**

> 또 다른 한계 하나는 #119로 해소됐다 — "이 유닛 파일은 운영 서버의 실제 구성을 확인하고 쓴 것이 아니다"는 더 이상 사실이 아니다. [terraform/prod/](../../terraform/prod/README.md)의 user-data가 이 파일을 `file()`로 읽어 그대로 인스턴스에 넣으므로, **이 파일이 곧 운영 구성이다.** 손으로 복제한 사본이 아니라서 한쪽만 고쳐 어긋날 일도 없다.

## 참고 문서

| 문서 | 내용 |
|---|---|
| [docs/tasks/jvm-heap-sizing/](../../docs/tasks/jvm-heap-sizing/README.md) | 설계·예산 산정식·사전 등록 판정 기준 |
| [docs/tasks/jvm-heap-sizing/memory-map.md](../../docs/tasks/jvm-heap-sizing/memory-map.md) | 힙 밖 165MB의 NMT 분해, GC 판단 |
| [docs/tasks/jvm-heap-sizing/ab-measurement.md](../../docs/tasks/jvm-heap-sizing/ab-measurement.md) | 448/768/1024 A/B 실측과 채택 판정 |
| [docs/guide/profile.md](../../docs/guide/profile.md) | 배포 서버 프로필 적용·확인 절차 |
| [docs/guide/cd.md](../../docs/guide/cd.md) | 배포·롤백 절차. instance-refresh-preferences.json을 CD가 어떻게 쓰는지 |
| [.env.example](../../.env.example) | 앱이 필요로 하는 환경변수의 정본 |
