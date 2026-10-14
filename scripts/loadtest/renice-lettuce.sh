#!/usr/bin/env bash
# App EC2에서 lettuce 이벤트루프 스레드에만 nice를 건다(#210, docs/tasks/tomcat-thread-sizing/lettuce-priority.md).
#
# 스레드 단위로 거는 이유: 리눅스의 setpriority(PRIO_PROCESS, tid)는 그 스레드 하나에만 적용된다.
# JVM의 Thread.setPriority는 기본 설정(ThreadPriorityPolicy=0)에서 무시되고, systemd의 Nice=는
# 프로세스 전체에 걸려 "lettuce만 우대"라는 실험 의도와 다르다.
#
# 대상은 이벤트루프 전부(lettuce-epollEv* / lettuce-nioEven*)다. 2개가 있고 공유 커넥션이라 하나만
# 일하는데, 어느 쪽이 일할지는 기동 전에 알 수 없다. lettuce-timer 등은 대상이 아니다
# (sample-schedstat.sh의 그룹 정의와 같다).
#
# 반드시 arm 예열 **뒤에** 부른다 — 재기동마다 tid가 바뀌고, netty 이벤트루프 스레드는 첫 작업이
# 들어올 때 생긴다. 대상이 0개면 실패한다(조용히 nice 없는 run을 nice arm이라고 기록하지 않게).
#
# 적용 후 /proc/<pid>/task/<tid>/stat의 nice 필드를 다시 읽어 검증한다(게이트 N1·N2).
# stat의 2번 필드(comm)는 괄호로 감싸져 있고 공백을 포함할 수 있으므로, 마지막 ')' 뒤를 잘라
# 거기서부터 센다 — ')' 뒤 첫 필드가 state(3번)이고 nice는 19번이라 17번째가 된다.
#
# 사용:  sudo renice-lettuce.sh <nice> [service-or-pid=yourtrip-app]
#   nice 0을 주면 기준 arm 검증(N2)만 하는 셈이 된다 — 재기동 직후라 이미 0이어야 한다.
set -euo pipefail

NICE="${1:?nice value (e.g. -10)}"
TARGET="${2:-yourtrip-app}"

case "$TARGET" in
  ''|*[!0-9]*) PID=$(systemctl show -p MainPID --value "$TARGET") ;;
  *) PID="$TARGET" ;;
esac
[ -n "$PID" ] && [ "$PID" != "0" ] && [ -d "/proc/$PID" ] || { echo "target $TARGET not running" >&2; exit 1; }

nice_of() { local s; s=$(< "/proc/$PID/task/$1/stat"); s="${s##*) }"; set -- $s; echo "${17}"; }

n=0; bad=0
for d in /proc/"$PID"/task/*; do
  tid="${d##*/}"
  comm=$(< "$d/comm") || continue
  case "$comm" in lettuce-epollEv*|lettuce-nioEven*) ;; *) continue ;; esac
  renice -n "$NICE" -p "$tid" >/dev/null
  got=$(nice_of "$tid")
  printf 'tid=%s %s nice=%s\n' "$tid" "$comm" "$got"
  n=$((n + 1))
  [ "$got" = "$NICE" ] || bad=$((bad + 1))
done

[ "$n" -gt 0 ] || { echo "no lettuce event loop threads under pid $PID — 예열 전에 불렀는가?" >&2; exit 1; }
[ "$bad" -eq 0 ] || { echo "nice not applied to $bad of $n threads" >&2; exit 1; }
echo "ok: $n lettuce event loop threads at nice $NICE (pid $PID)"
