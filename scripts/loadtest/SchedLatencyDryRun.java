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
 *   http-nio-8080-exec-N       : 1ms 일하고 0.5ms 쉰다 — 경합 상대(워커 수 = 인자)
 *
 * 워커 수만 바꿔 두 번 돌리면 lettuce 대기가 달라져야 한다. 알려진 차이를 계측기가 못 잡으면
 * 실측의 "차이 없음"도 믿을 수 없다(lettuce-dispatch-latency.md 게이트 G3).
 *
 * 사용:  로컬에서 javac --release 21 -d <dir> SchedLatencyDryRun.java 로 컴파일해 .class를 보내고
 *        App EC2에서 java -cp <dir> SchedLatencyDryRun <workers> <seconds>
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

        named("lettuce-epollEventLoop-4-1", () -> {
            while (running) {
                LockSupport.parkNanos(200_000);
                spinMicros(30);
            }
        }).start();
        named("lettuce-epollEventLoop-4-2", () -> {
            while (running) {
                LockSupport.parkNanos(100_000_000);
            }
        }).start();
        for (int i = 1; i <= workers; i++) {
            named("http-nio-8080-exec-" + i, () -> {
                while (running) {
                    spinMicros(1_000);
                    LockSupport.parkNanos(500_000);
                }
            }).start();
        }

        System.out.println("pid=" + ProcessHandle.current().pid() + " workers=" + workers);
        Thread.sleep(seconds * 1_000L);
        running = false;
    }
}
