"""인증 메일 발송 측정(#221) — 스레드 덤프에서 앱 워커가 어디서 기다리는지 센다(판정 M2의 1차 증거).

mail-round.sh 가 10초마다 남긴 <회차>.threads.txt 를 읽어, 덤프마다 http-nio-8080-exec-* 를 넷으로 나눈다.
  커넥션  com.zaxxer.hikari.pool.HikariPool.getConnection 대기
  SMTP    org.eclipse.angus.mail.smtp.SMTPTransport 아래 (서버 응답 대기)
  유휴    작업 대기열(TaskQueue.take·poll)
  기타    그 밖 — 실행 중이거나 다른 대기

시각은 회차 시작(MAILRUN.runStartMs) 기준이며, 메일 도착 시작을 0초로 맞춰 출력한다.

사용:  python mail-threads.py <회차>.threads.txt <회차>.k6.log
"""
import json
import re
import sys

# server.address 를 지정하면 이름에 주소가 들어간다 — http-nio-0.0.0.0-8080-exec-N
WORKER_RE = re.compile(r'^"(http-nio-(?:[^"]*-)?8080-exec-\d+)"')


def classify(stack):
    if 'HikariPool.getConnection' in stack:
        return '커넥션'
    if 'angus.mail.smtp.SMTPTransport' in stack:
        return 'SMTP'
    # 기본 개수(10)를 넘어 늘어난 워커는 take 가 아니라 시간 제한이 있는 poll 로 쉰다.
    if 'TaskQueue.take' in stack or 'TaskQueue.poll' in stack:
        return '유휴'
    return '기타'


def dumps(path):
    ts, workers, cur_name, cur_stack = None, {}, None, []
    with open(path, encoding='utf-8', errors='replace') as f:
        for line in f:
            if line.startswith('# ts='):
                if ts is not None:
                    if cur_name:
                        workers[cur_name] = classify(''.join(cur_stack))
                    yield ts, workers
                ts, workers, cur_name, cur_stack = float(line[5:]), {}, None, []
                continue
            if line.startswith('"'):
                if cur_name:
                    workers[cur_name] = classify(''.join(cur_stack))
                m = WORKER_RE.match(line)
                cur_name, cur_stack = (m.group(1), []) if m else (None, [])
                continue
            if cur_name:
                cur_stack.append(line)
    if ts is not None:
        if cur_name:
            workers[cur_name] = classify(''.join(cur_stack))
        yield ts, workers


def main():
    threads, k6log = sys.argv[1], sys.argv[2]
    run = None
    with open(k6log, encoding='utf-8') as f:
        for line in f:
            m = re.search(r'msg="MAILRUN (.*)" source=', line)
            if m:
                run = json.loads(m.group(1).replace('\\"', '"'))
                break
    mail_start = run['runStartMs'] / 1000 + run['warmupSec'] + run['baselineSec']
    print('| 초 (메일 도착 기준) | 워커 수 | 커넥션 대기 | SMTP 대기 | 유휴 | 기타 |')
    print('|---|---|---|---|---|---|')
    for ts, w in dumps(threads):
        c = {k: 0 for k in ('커넥션', 'SMTP', '유휴', '기타')}
        for v in w.values():
            c[v] += 1
        print(f"| {ts - mail_start:.0f} | {len(w)} | {c['커넥션']} | {c['SMTP']} | {c['유휴']} | {c['기타']} |")


if __name__ == '__main__':
    main()
