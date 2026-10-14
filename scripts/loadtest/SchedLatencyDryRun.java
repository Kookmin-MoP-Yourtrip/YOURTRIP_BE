import java.util.concurrent.locks.LockSupport;

/**
 * trace-sched-latency.bt / sample-sched-latency.sh 계측기를 실측 전에 검증하기 위한 합성 워크로드.
 *
 * App EC2만 단독으로 켜면 RDS·Redis가 없어 앱이 뜨지 않고, 그러면 lettuce 스레드도 없다. 그래서
 * 같은 이름의 스레드를 직접 만든다 — JVM은 Java 스레드 이름을 커널 comm(15자 절단)에 반영하므로
 * 계측기 입장에서는 실제 앱과 구분되지 않는다.
 *
 *   lettuce-epollEventLoop-4-1 : 짧게 잠들고(응답 대기 흉내) 짧게 일한다 — 측정 대상
 *   lettuce-epollEventLoop-4-2 : 거의 잔다 — 공유 커넥션에서 노는 두 번째 이벤트루프 흉내
 *   http-nio-8080-exec-N       : 1ms 일하는 경합 상대(워커 수 = 인자) — 깨어나는 방식은 모드가 정한다
 *   GC Thread#0 / C2 CompilerThread0 : 5ms마다 50μs 일한다 — 선점자 그룹 분류(gc·jit) 확인용
 *
 * 모드(#207 — 선점자를 누가 깨웠는가의 집계를 정답을 아는 조건에서 검증한다):
 *   indep : 워커가 1ms 일하고 parkNanos(0.5ms)로 쉰다 — 워커를 깨우는 것은 타이머(hrtimer 인터럽트)다.
 *           lettuce를 밀어낸 워커의 waker가 lettuce로 집계되면 인터럽트 오귀속이다([tomcat, lettuce] ≈ 0이어야 한다).
 *           #207 이전 게이트(G3)와 같은 워크로드다.
 *   wake  : 워커는 신호가 올 때까지 잔다. lettuce가 일할 때마다 다음 워커를 unpark한다 — 응답을 처리해
 *           그 응답을 기다리던 워커를 깨우는 실제 앱의 흉내다([tomcat, lettuce]가 지배적이어야 한다).
 *
 * 워커 수만 바꿔 두 번 돌리면 lettuce 대기가 달라져야 한다. 알려진 차이를 계측기가 못 잡으면
 * 실측의 "차이 없음"도 믿을 수 없다(lettuce-dispatch-latency.md 게이트 G3).
 *
 * 사용:  로컬에서 javac --release 21 -d <dir> SchedLatencyDryRun.java 로 컴파일해 .class를 보내고
 *        App EC2에서 java -cp <dir> SchedLatencyDryRun <workers> <seconds> [indep|wake]
 *        (App EC2는 headless JRE만 있어 `java File.java` 소스 실행에 필요한 jdk.compiler가 없다 —
 *         측정 대상 박스에 개발 도구를 설치하지 않는다는 원칙 그대로 둔다)
 */
public class SchedLatencyDryRun {

    static volatile boolean running = true;
    static volatile long sink;

    static void spinMicros(long micros) {
        long end = System.nanoTime() + micros * 1_000;
        long x = 0;
        while (System.nanoTime() < end) {
            x++;
        }
        sink += x;
    }

    static Thread named(String name, Runnable body) {
        // 이름은 start() 전에 정해야 커널 comm에 반영된다
        Thread t = new Thread(body, name);
        t.setDaemon(true);
        return t;
    }

    public static void main(String[] args) throws InterruptedException {
        int workers = Integer.parseInt(args[0]);
        int seconds = Integer.parseInt(args[1]);
        String mode = args.length > 2 ? args[2] : "indep";
        if (!mode.equals("indep") && !mode.equals("wake")) {
            throw new IllegalArgumentException("mode must be indep or wake: " + mode);
        }
        boolean wake = mode.equals("wake");

        Thread[] pool = new Thread[workers];
        for (int i = 0; i < workers; i++) {
            pool[i] = named("http-nio-8080-exec-" + (i + 1), wake
                    ? () -> {
                        while (running) {
                            // 신호(unpark)가 올 때까지 잔다 — 이미 받은 신호가 있으면 바로 돌아온다
                            LockSupport.park();
                            spinMicros(1_000);
                        }
                    }
                    : () -> {
                        while (running) {
                            spinMicros(1_000);
                            LockSupport.parkNanos(500_000);
                        }
                    });
        }

        named("lettuce-epollEventLoop-4-1", () -> {
            int next = 0;
            while (running) {
                LockSupport.parkNanos(200_000);
                spinMicros(30);
                if (wake) {
                    // 응답 하나를 처리하면 그 응답을 기다리던 워커 하나가 깨어난다
                    LockSupport.unpark(pool[next]);
                    next = (next + 1) % pool.length;
                }
            }
        }).start();
        named("lettuce-epollEventLoop-4-2", () -> {
            while (running) {
                LockSupport.parkNanos(100_000_000);
            }
        }).start();
        for (Thread t : pool) {
            t.start();
        }
        for (String name : new String[] {"GC Thread#0", "C2 CompilerThread0"}) {
            named(name, () -> {
                while (running) {
                    spinMicros(50);
                    LockSupport.parkNanos(5_000_000);
                }
            }).start();
        }

        System.out.println("pid=" + ProcessHandle.current().pid() + " workers=" + workers + " mode=" + mode);
        Thread.sleep(seconds * 1_000L);
        running = false;
    }
}
