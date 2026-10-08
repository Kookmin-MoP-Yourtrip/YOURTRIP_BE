#!/usr/bin/env python3
"""장애 격리 주실험(#197) — 회차 시작 전 운영 Redis 의 캐시 키만 지운다.

설계: docs/tasks/ai-bulkhead-loadtest/README.md 5-6절
  - 지우는 것: Spring Cache 의 캐시 세 개(global/config/RedisConfig.java). 키 형식은 기본 접두사 "<캐시이름>::"
  - 지우지 않는 것: 미동기화 조회수 증분(view_count:*), 이메일 인증 상태, 분산 락. 그래서 FLUSHALL 을 쓰지 않는다
  - KEYS 는 Redis 를 막으므로 SCAN 으로 훑고 UNLINK(비동기 해제)로 지운다

앱 인스턴스 안에서 root 로 돈다 — ElastiCache 는 VPC 안에서만 닿고, 접속 정보가 /opt/app/.env(root 전용)에 있다.
인스턴스에 redis-cli 가 없어 표준 라이브러리 소켓으로 RESP 를 직접 말한다(ElastiCache 는 전송 암호화를 켜지 않았다).

사용: sudo python3 bulkhead-cache-evict.py [--env /opt/app/.env] [--dry-run]
"""
import argparse
import socket
import sys

CACHE_PATTERNS = ['popularCourses::*', 'courseDetail::*', 'courseListItem::*']


def read_env(path):
    env = {}
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if line and not line.startswith('#') and '=' in line:
                k, v = line.split('=', 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    return env


class Resp:
    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=10)
        self.buf = self.sock.makefile('rb')

    def call(self, *args):
        parts = [f'*{len(args)}\r\n'.encode()]
        for a in args:
            b = a.encode() if isinstance(a, str) else a
            parts.append(f'${len(b)}\r\n'.encode() + b + b'\r\n')
        self.sock.sendall(b''.join(parts))
        return self._read()

    def _read(self):
        line = self.buf.readline()
        if not line:
            raise ConnectionError('Redis 연결이 끊겼다')
        kind, rest = line[:1], line[1:-2]
        if kind == b'+':
            return rest.decode()
        if kind == b'-':
            raise RuntimeError(f'Redis 오류: {rest.decode()}')
        if kind == b':':
            return int(rest)
        if kind == b'$':
            n = int(rest)
            if n < 0:
                return None
            data = self.buf.read(n + 2)[:-2]
            return data
        if kind == b'*':
            n = int(rest)
            return None if n < 0 else [self._read() for _ in range(n)]
        raise RuntimeError(f'알 수 없는 응답: {line!r}')


_delete_cmd = 'UNLINK'


def delete(r, keys):
    """UNLINK(4.0+, 해제를 백그라운드로 미룬다)를 쓰고, 모르는 서버면 DEL 로 내려간다. 캐시 값은 작은
    문자열이라 DEL 로 지워도 Redis 를 오래 막지 않는다."""
    global _delete_cmd
    try:
        return r.call(_delete_cmd, *keys)
    except RuntimeError as e:
        if _delete_cmd == 'UNLINK' and 'unknown command' in str(e).lower():
            _delete_cmd = 'DEL'
            return r.call('DEL', *keys)
        raise


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--env', default='/opt/app/.env')
    ap.add_argument('--dry-run', action='store_true')
    # 도구 검증용(로컬 Redis 의 빈 DB 에서 시험할 때). 운영은 앱과 같은 0번이다.
    ap.add_argument('--db', type=int, default=0, help=argparse.SUPPRESS)
    args = ap.parse_args()

    env = read_env(args.env)
    r = Resp(env['REDIS_HOST'], int(env.get('REDIS_PORT', '6379')))
    if r.call('PING') != 'PONG':
        sys.exit('PING 실패')
    if args.db:
        r.call('SELECT', str(args.db))

    total = 0
    for pattern in CACHE_PATTERNS:
        cursor, deleted = '0', 0
        while True:
            cursor, keys = r.call('SCAN', cursor, 'MATCH', pattern, 'COUNT', '1000')
            cursor = cursor.decode() if isinstance(cursor, bytes) else str(cursor)
            if keys:
                deleted += len(keys) if args.dry_run else delete(r, keys)
            if cursor == '0':
                break
        print(f'{pattern}: {"지울 대상" if args.dry_run else "삭제"} {deleted}개')
        total += deleted
    # 확인: 패턴별로 다시 훑어 남은 키가 없어야 한다(dry-run 제외).
    if not args.dry_run:
        for pattern in CACHE_PATTERNS:
            cursor, left = '0', 0
            while True:
                cursor, keys = r.call('SCAN', cursor, 'MATCH', pattern, 'COUNT', '1000')
                cursor = cursor.decode() if isinstance(cursor, bytes) else str(cursor)
                left += len(keys or [])
                if cursor == '0':
                    break
            if left:
                sys.exit(f'{pattern}: 지운 뒤에도 {left}개가 남았다 — 회차를 시작하지 않는다')
    print(f'합계 {total}개. 나머지 키(조회수 증분·인증·락)는 건드리지 않았다')


if __name__ == '__main__':
    main()
