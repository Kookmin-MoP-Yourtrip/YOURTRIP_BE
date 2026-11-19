"""Lettuce 이벤트 루프가 공유 연결 수만큼 나뉘어 일했는지 확인한다 — async-variant.md 3-9절 유효성 게이트.

fastapi-round.sh 가 k6 앞뒤로 남긴 스레드별 schedstat(``tid comm 실행ns 대기ns 배정수``)의 차이로
루프마다 실행 시간 몫을 계산한다. 두 시점에 모두 있는 tid 만 쓴다(Netty 는 루프 스레드를 처음 일이 생길 때 띄우므로
k6 앞에 없던 루프는 "부하 전에는 일이 없었다"는 뜻이고, 그 루프의 실행 시간은 after 값 전체로 센다).

게이트 (3-9절):
  공유 연결 2개: 실행 시간 상위 두 루프 중 작은 쪽이 합계의 30% 이상
  공유 연결 8개: 합계의 5% 이상을 쓴 루프가 6개 이상
  공유 연결 1개(대조): 기록만 한다

사용: python lettuce-loop-gate.py <before.txt> <after.txt> <공유 연결 수>
"""
import sys


def read(path):
    rows = {}
    try:
        with open(path, encoding='utf-8') as f:
            for line in f:
                parts = line.split()
                if len(parts) < 5:
                    continue
                tid, comm, run, wait, count = parts[0], parts[1], int(parts[2]), int(parts[3]), int(parts[4])
                rows[tid] = (comm, run, wait, count)
    except FileNotFoundError:
        pass
    return rows


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    before, after, shared = read(sys.argv[1]), read(sys.argv[2]), int(sys.argv[3])
    if not after:
        print('루프 게이트: schedstat 을 읽지 못했다 — 판정 불가')
        return 1
    loops = []
    for tid, (comm, run, wait, count) in after.items():
        b = before.get(tid, (comm, 0, 0, 0))
        loops.append((tid, run - b[1], wait - b[2], count - b[3]))
    loops.sort(key=lambda r: r[1], reverse=True)
    total = sum(r[1] for r in loops) or 1
    shares = [r[1] / total for r in loops]
    for (tid, run, wait, count), share in zip(loops, shares):
        busy = run + wait
        print(f'  루프 tid {tid}: 실행 {run / 1e9:.1f}s ({share:.1%}), 런큐 대기 {wait / 1e9:.1f}s '
              f'(대기 비율 {wait / busy if busy else 0:.1%}), 배정 {count:,}회')

    if shared <= 1:
        verdict, ok = f'기록만(대조) — 일한 루프 {sum(1 for s in shares if s >= 0.05)}개', True
    elif shared == 2:
        second = shares[1] if len(shares) > 1 else 0.0
        ok = second >= 0.30
        verdict = f'상위 두 루프 중 작은 쪽 {second:.1%} (기준 30% 이상)'
    else:
        working = sum(1 for s in shares if s >= 0.05)
        ok = working >= 6
        verdict = f'합계 5% 이상 루프 {working}개 (기준 6개 이상)'
    print(f'루프 게이트: {"통과" if ok else "실패 — 회차 무효"} — {verdict}')
    return 0 if ok else 2


if __name__ == '__main__':
    sys.exit(main())
