package backend.yourtrip.global.benchmark;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * 컨텍스트 스위칭 1회의 비용을 "직접(커널 경로)"과 "간접(캐시 재적재)"으로 갈라 재는 벤치마크다.
 *
 * <p>이슈 #97의 4번 항목 — #88에서 Tomcat {@code maxThreads}를 200에서 32로 줄이자 요청당 CPU가
 * 26% 줄었는데(0.747에서 0.555 vCPU-ms), 그 26%가 전환 오버헤드인지 캐시 지역성인지 나누지 못했다.
 * 전환 1회의 비용을 알아야 26% 중 몫을 계산할 수 있는데, 그 값은 하드웨어마다 다르므로 문헌에서
 * 가져오면 안 된다. 측정 대상과 같은 박스에서 직접 잰다.
 *
 * <p>방법은 Li, Ding, Shen의 <i>Quantifying the Cost of Context Switch</i>(ExpCS 2007)를 따른다 —
 * 메모리를 건드리지 않을 때의 전환 비용 c1(직접)과 크기 S의 워킹셋을 훑을 때의 비용 c2를 각각 재고,
 * <b>간접 비용 = c2 빼기 c1</b>로 본다. 원 논문은 파이프로 통신하는 두 프로세스를 썼지만, 여기서
 * 재려는 대상은 "스레드가 많을 때 무슨 일이 생기는가"이므로 <b>스레드 쌍 N/2개</b>로 일반화했다.
 * 쌍마다 토큰이 따로 돌기 때문에 항상 N/2개가 runnable이고, 그것이 T200 arm의 형상이다.
 *
 * <p>측정 단위는 "일감 1개" = 자기 워킹셋을 캐시라인 간격으로 한 번 훑는 것이다. 일감 하나마다
 * park/unpark 한 번이 끼므로, <b>일감당 CPU 시간</b>을 N에 따라 비교하면 경합이 붙인 비용이 나온다.
 * S를 키우며 그 비용이 함께 커지면 캐시, S와 무관하게 평평하면 전환 경로다.
 *
 * <p><b>N마다 JVM을 새로 띄워야 한다.</b> 한 JVM 안에서 N을 바꿔가며 연속으로 재면 앞 설정이 JIT
 * 프로파일을 오염시킨다 — 이 저장소가 EC2 측정에서 "arm마다 재기동"으로 지켜온 원칙과 같다.
 * 그래서 이 클래스는 인자로 받은 (N, S) 한 조합만 재고 끝낸다. 스윕은
 * {@code scripts/loadtest/run-switch-benchmark.sh}가 돈다.
 *
 * <p>CPU 시간과 전환 횟수는 JMX가 아니라 {@code /proc/self}에서 직접 읽는다. 부하테스트 하네스가
 * 앱을 재는 것과 <b>같은 출처, 같은 단위</b>여야 두 측정을 나눗셈으로 이어붙일 수 있기 때문이다.
 *
 * <pre>
 *   java -Xmx1200m -cp context-switch-benchmark.jar
 *        backend.yourtrip.global.benchmark.ContextSwitchCostBenchmark [threads] [bytes] [seconds]
 * </pre>
 */
public final class ContextSwitchCostBenchmark {

    /** USER_HZ — /proc의 utime/stime 단위. x86 리눅스에서 100으로 고정이다. */
    private static final long USER_HZ = 100L;
    private static final int CACHE_LINE_BYTES = 64;
    private static final int LONGS_PER_LINE = CACHE_LINE_BYTES / Long.BYTES;
    private static final int WARMUP_SECONDS = 3;

    private static volatile boolean stop;

    private ContextSwitchCostBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        int threads = args.length > 0 ? Integer.parseInt(args[0]) : 2;
        long wsBytes = args.length > 1 ? Long.parseLong(args[1]) : 0L;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 10;
        if (threads < 2 || threads % 2 != 0) {
            throw new IllegalArgumentException("threads는 2 이상의 짝수여야 한다(쌍으로 핑퐁한다): " + threads);
        }

        // 워밍업은 같은 (N, S)로 돈다 — 측정할 코드 경로와 다른 프로파일을 심으면 안 된다.
        measure(threads, wsBytes, WARMUP_SECONDS);
        Result r = measure(threads, wsBytes, seconds);
        report(threads, wsBytes, seconds, r);
    }

    private static Result measure(int threads, long wsBytes, int seconds) throws InterruptedException {
        stop = false;
        Peer[] peers = new Peer[threads];
        for (int i = 0; i < threads; i++) {
            peers[i] = new Peer(wsBytes);
        }
        for (int i = 0; i < threads; i += 2) {
            peers[i].partner = peers[i + 1];
            peers[i + 1].partner = peers[i];
            peers[i].myTurn = true;          // 쌍마다 토큰 하나 — 항상 N/2개가 runnable이다
        }

        List<Thread> ts = new ArrayList<>(threads);
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(peers[i], "bench-" + i);
            peers[i].thread = t;
            ts.add(t);
        }

        long[] cpu0 = selfCpuJiffies();
        long sw0 = selfSwitches();
        long nano0 = System.nanoTime();
        ts.forEach(Thread::start);

        Thread.sleep(seconds * 1000L);

        // 스레드가 아직 살아 있을 때 찍어야 한다. /proc/self/task/<tid>는 스레드가 끝나면 사라지므로
        // join 뒤에 읽으면 벤치 스레드의 pcount가 통째로 날아간다(초기 판에서 실제로 겪은 버그 —
        // 111,420 pass에 전환이 126으로 잡혔다). utime/stime은 스레드 종료 시 프로세스에 누적되므로
        // 영향이 없지만, 같은 순간의 값이어야 나눗셈이 성립하므로 함께 찍는다.
        long wallNs = System.nanoTime() - nano0;
        long sw1 = selfSwitches();
        long[] cpu1 = selfCpuJiffies();

        stop = true;
        for (Peer p : peers) {
            LockSupport.unpark(p.thread);
        }
        for (Thread t : ts) {
            t.join(5000);
        }

        long passes = 0;
        long sink = 0;
        for (Peer p : peers) {
            passes += p.passes;
            sink += p.sink;
        }
        return new Result(passes, sink, sw1 - sw0,
                cpu1[0] - cpu0[0], cpu1[1] - cpu0[1], wallNs);
    }

    private static void report(int threads, long wsBytes, int seconds, Result r) {
        long cpuJiffies = r.utime() + r.stime();
        double cpuNs = cpuJiffies * (1_000_000_000.0 / USER_HZ);
        double cpuPerPass = r.passes() > 0 ? cpuNs / r.passes() : Double.NaN;
        double swPerPass = r.passes() > 0 ? (double) r.switches() / r.passes() : Double.NaN;
        double cpuPerSwitch = r.switches() > 0 ? cpuNs / r.switches() : Double.NaN;
        double lines = wsBytes / (double) CACHE_LINE_BYTES;

        System.out.printf("%n=== threads=%d workingSet=%s seconds=%d ===%n",
                threads, human(wsBytes), seconds);
        System.out.printf("  일감(pass)   : %,d (%,.0f/s)%n", r.passes(), r.passes() / (r.wallNs() / 1e9));
        System.out.printf("  CPU          : %,.0f ms (user %,.0f / sys %,.0f) = %.2f vCPU%n",
                cpuNs / 1e6, r.utime() * 1000.0 / USER_HZ, r.stime() * 1000.0 / USER_HZ,
                cpuNs / r.wallNs());
        System.out.printf("  전환(pcount) : %,d (%.2f/pass)%n", r.switches(), swPerPass);
        System.out.printf("  일감당 CPU   : %,.0f ns  (캐시라인 %,.0f개)%n", cpuPerPass, lines);
        System.out.printf("  전환당 CPU   : %,.0f ns%n", cpuPerSwitch);
        // 일감 하나마다 park/unpark가 정확히 한 번 끼므로 전환/일감은 1.0 근처여야 한다.
        // 크게 벗어나면 측정이 깨진 것이다(스레드가 죽은 뒤에 pcount를 읽는 실수를 한 적이 있다).
        if (swPerPass < 0.5 || swPerPass > 2.0) {
            System.out.printf("  [경고] 전환/일감 = %.3f — 1.0 근처가 아니다. 이 결과는 신뢰할 수 없다.%n",
                    swPerPass);
        }
        // 스윕 결과를 스크립트가 그대로 표로 모을 수 있게 한 줄 요약을 따로 낸다.
        System.out.printf("TSV\t%d\t%d\t%d\t%d\t%d\t%d\t%.1f\t%.1f\t%.3f%n",
                threads, wsBytes, r.passes(), r.switches(), r.utime(), r.stime(),
                cpuPerPass, cpuPerSwitch, swPerPass);
        if (r.sink() == Long.MIN_VALUE) {
            System.out.println("unreachable " + r.sink());   // sink를 살려둔다(죽은 코드 제거 방지)
        }
    }

    private static String human(long bytes) {
        if (bytes == 0) {
            return "0(메모리 접근 없음 = 직접 비용 c1)";
        }
        if (bytes >= 1 << 20) {
            return (bytes >> 20) + "MB";
        }
        return (bytes >> 10) + "KB";
    }

    /** {@code /proc/self/stat}의 utime, stime(jiffies). */
    private static long[] selfCpuJiffies() {
        try {
            String line = Files.readString(Path.of("/proc/self/stat"));
            // comm(2번 필드)은 괄호에 싸이고 공백을 포함할 수 있다 — 마지막 ") " 뒤부터 세야 한다.
            // 그 뒤 첫 필드가 state(원래 3번)이므로 원래 N번은 여기서 N-2번이다.
            String rest = line.substring(line.lastIndexOf(") ") + 2);
            String[] f = rest.trim().split("\\s+");
            return new long[]{Long.parseLong(f[11]), Long.parseLong(f[12])};
        } catch (IOException | RuntimeException e) {
            return new long[]{0L, 0L};
        }
    }

    /**
     * 이 프로세스의 모든 스레드가 CPU에 스케줄된 횟수 합
     * ({@code /proc/self/task/}의 각 schedstat 3번째 필드).
     */
    private static long selfSwitches() {
        try (Stream<Path> tasks = Files.list(Path.of("/proc/self/task"))) {
            return tasks.mapToLong(t -> {
                try {
                    String[] f = Files.readString(t.resolve("schedstat")).trim().split("\\s+");
                    return Long.parseLong(f[2]);
                } catch (IOException | RuntimeException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static final class Peer implements Runnable {
        private final long[] ws;
        private Peer partner;
        private Thread thread;
        private volatile boolean myTurn;
        private long passes;
        private long sink;

        private Peer(long wsBytes) {
            this.ws = wsBytes > 0 ? new long[(int) (wsBytes / Long.BYTES)] : null;
        }

        @Override
        public void run() {
            while (!stop) {
                while (!myTurn) {
                    LockSupport.park();
                    if (stop) {
                        return;
                    }
                }
                myTurn = false;
                sink += touch();
                passes++;
                partner.myTurn = true;
                LockSupport.unpark(partner.thread);
            }
        }

        /** 워킹셋을 캐시라인 간격으로 한 번 훑는다. 읽기만 하면 쓰기 되돌림 비용이 빠지므로 더한다. */
        private long touch() {
            long[] w = ws;
            if (w == null) {
                return 0L;
            }
            long s = 0L;
            for (int i = 0; i < w.length; i += LONGS_PER_LINE) {
                w[i]++;
                s += w[i];
            }
            return s;
        }
    }

    private record Result(long passes, long sink, long switches, long utime, long stime, long wallNs) {
    }
}
