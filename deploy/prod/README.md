# 운영 배포 설정 — JVM 기동 옵션

운영 서버(`yourtrip.site`)의 **JVM 기동 옵션을 버전관리하는 곳**이다.

## 왜 이 디렉터리가 필요한가

`-Xmx`·`-Xss` 같은 JVM 옵션은 **`java` 프로세스의 명령줄 인자**다. JVM이 이미 그 값으로 뜬 뒤에야 Spring이 `application-prod.yml`을 읽으므로, **Spring 설정 파일에는 담을 수가 없다.** `server.tomcat.threads.max: 32`는 yml에 넣을 수 있지만 `-Xmx`는 원리상 불가능하다.

그래서 "`java`를 실행하는 지점"에 있어야 하는데, 이 저장소에 그런 곳이 **부하테스트용 하나뿐이었다**(`terraform/loadtest/templates/app-user-data.sh.tpl`). 운영 서버는 Terraform 관리 밖이라 그 템플릿과 무관하고, 결과적으로 **운영이 어떤 힙 상한으로 도는지 저장소가 알 수 없는 상태**였다. #101에서 t3.small 기준으로 재산정한 값도 운영에는 닿지 못했다.

이 디렉터리가 그 자리를 메운다.

## 파일

| 파일 | 역할 |
|---|---|
| [jvm-opts.env](jvm-opts.env) | **JVM 옵션의 정본.** 값과 산정 근거가 함께 있다. systemd `EnvironmentFile`로 읽힌다 |
| [yourtrip-app.service](yourtrip-app.service) | systemd 유닛. 부하테스트에서 검증된 구조를 옮긴 것이다 |

**JVM 옵션을 별도 파일로 나눈 이유**는 `/opt/app/.env`가 DB 비밀번호·API 키를 담고 있어 git 밖에 있어야 하기 때문이다. JVM 옵션은 비밀이 아니라 **근거를 남겨야 하는 설정**이므로 반대로 저장소가 들고 있어야 한다. systemd는 `EnvironmentFile=`을 여러 줄 쓸 수 있어 둘을 나란히 읽을 수 있다.

## 현재 값

```
JVM_OPTS=-Xmx768m -Xss512k
```

`-Xmx768m`은 **t3.small(vCPU 2 / 2GB) 기준 실측값**이다. 산정 근거는 `jvm-opts.env`의 주석과 [docs/tasks/jvm-heap-sizing/](../../docs/tasks/jvm-heap-sizing/README.md)에 있다.

> **힙을 키운다고 빨라지지 않는다.** 실측에서 `-Xmx`를 448 → 768 → 1024로 올려도 G1이 실제로 커밋한 힙은 227MB 부근에서 움직이지 않았다. 이 값의 목적은 성능이 아니라 **근거의 복원**이다 — 종전 448m은 t3.micro(1GB) 전제로 잡힌 값인데다 JVM 기본값(480MB)의 93%라 사실상 아무 통제도 하지 않고 있었다.

## 적용

**앱을 재기동하므로 서비스 중단이 발생한다.** 트래픽이 적은 시간에 한다.

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

## 스펙을 바꾸면 이 값은 무효다

`-Xmx768m`은 **t3.small(2GB) 전용**이다. 인스턴스를 바꿨다면 그대로 쓰지 않는다.

- **더 작은 스펙(t3.micro 1GB 등)**: 힙 768MB + 논힙 190MB + 네이티브 165MB로 물리 메모리를 넘겨 **OOM이 난다.** 게다가 메모리가 server-class 문턱(1,792MB) 아래로 내려가 **GC가 G1에서 SerialGC로 바뀌므로**, 예산의 "G1 자료구조 43MB" 항목부터 다시 재야 한다.
- **더 큰 스펙**: OOM 위험은 없지만 힙이 물리 메모리 대비 지나치게 보수적이 된다. [예산 산정식](../../docs/tasks/jvm-heap-sizing/README.md)으로 다시 계산한다.

## 한계

- **이 유닛 파일은 운영 서버의 실제 구성을 확인하고 쓴 것이 아니다.** 부하테스트 환경에서 검증된 구조를 옮긴 것이고, 운영이 systemd로 돈다는 전제 위에 있다. 실제 서비스명·경로가 다르면 그 부분만 맞춰 쓴다.
- **`-Xmx768m`의 근거가 되는 실측은 부하테스트 환경에서 이뤄졌다.** 같은 t3.small이지만 운영 트래픽 패턴(AI 코스 생성 등 할당이 큰 경로)은 재지 않았다. 힙 밖 항목 중 메타스페이스·심볼은 로드되는 클래스 수에 따라 더 자랄 수 있다.
- **배포 자동화는 여기 없다.** JAR 빌드·전달·롤백은 여전히 수동이다. 이 디렉터리는 "JVM 옵션이 어디 있는가"만 해결한다.

## 참고 문서

| 문서 | 내용 |
|---|---|
| [docs/tasks/jvm-heap-sizing/](../../docs/tasks/jvm-heap-sizing/README.md) | 설계·예산 산정식·사전 등록 판정 기준 |
| [docs/tasks/jvm-heap-sizing/memory-map.md](../../docs/tasks/jvm-heap-sizing/memory-map.md) | 힙 밖 165MB의 NMT 분해, GC 판단 |
| [docs/tasks/jvm-heap-sizing/ab-measurement.md](../../docs/tasks/jvm-heap-sizing/ab-measurement.md) | 448/768/1024 A/B 실측과 채택 판정 |
| [docs/guide/profile.md](../../docs/guide/profile.md) | 배포 서버 프로필 적용·확인 절차 |
| [.env.example](../../.env.example) | 앱이 필요로 하는 환경변수의 정본 |
