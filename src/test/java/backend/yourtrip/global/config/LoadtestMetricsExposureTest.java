package backend.yourtrip.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 부하테스트 하네스({@code scripts/loadtest/aggregate.py})가 이름으로 긁어가는 지표들이 실제로
 * {@code /actuator/prometheus}에 그 이름으로 나오는지 잠근다.
 *
 * <p>이 테스트가 필요한 이유는 이 저장소가 정확히 이 지점에서 두 번 데였기 때문이다.
 * <ul>
 *   <li>{@code hibernate.*}가 의존성 누락으로 조용히 사라져 있었다({@link HibernateMetricsRegistrationTest}).</li>
 *   <li>{@code lettuce_command_*}는 <b>처음부터 노출되고 있었는데</b> 하네스의 폴링이 15개 지표
 *       화이트리스트로 걸러내고 있었다. 그래서 병목 규명이 한참 늦어졌다
 *       ({@code docs/tasks/cache-effect-measurement/redis-io-bottleneck.md}).</li>
 * </ul>
 * 그때 남긴 재발 방지 규칙이 "다음 측정에서는 먼저 {@code /actuator/prometheus}에 그 지표가 실제로
 * 나오는지 확인한다"였다. 이슈 #97이 요청당 CPU를 user/sys로 가르면서 GC/JIT 몫을 차감해야 하는데,
 * 그 차감이 {@code jvm_gc_*}에 통째로 얹혀 있으므로 <b>EC2 비용을 쓰기 전에</b> 여기서 잠근다.
 *
 * <p>Micrometer 이름이 아니라 <b>Prometheus 노출명</b>으로 검사하는 것이 핵심이다. 집계기는
 * {@code jvm.gc.pause}가 아니라 {@code jvm_gc_pause_seconds_sum}이라는 문자열을 찾기 때문에,
 * 접미사 규칙(단위 붙임, 카운터의 {@code _total})이 어긋나면 지표가 있어도 집계에서 빠진다.
 *
 * <p>레지스트리 빈을 주입받는 대신 <b>실제로 HTTP로 엔드포인트를 긁는다.</b> 하네스
 * ({@code poll-metrics.sh})가 하는 일이 정확히 그것이고, 이 경로에는 빈 등록 말고도
 * {@code management.endpoints.web.exposure.include}와 {@code SecurityConfig}의 permitAll이
 * 함께 걸려 있어서 — 셋 중 어느 하나만 어긋나도 지표가 조용히 사라진다.
 *
 * <p><b>{@code @AutoConfigureObservability}가 없으면 이 엔드포인트는 테스트에서 404다.</b>
 * Spring Boot가 테스트 컨텍스트에 {@code management.defaults.metrics.export.enabled=false}를
 * 심어 익스포터를 통째로 끄기 때문이다 — 테스트가 실제 모니터링 백엔드로 지표를 밀지 않게 하려는
 * 의도적 기본값이라 운영과는 무관하다. 운영 설정이 깨진 것으로 착각하기 쉬워 적어 둔다.
 * {@code @SpringBootTest(properties = ...)}로 그 값을 되돌리는 것으로는 안 된다 — 그 프로퍼티
 * 소스가 인라인 테스트 프로퍼티보다 앞에 붙어서 이깁니다. 조건 평가 보고서({@code -Ddebug})에는
 * 이렇게 나온다:
 * <pre>
 *   PrometheusMetricsExportAutoConfiguration:
 *      Did not match:
 *         - @ConditionalOnEnabledMetricsExport management.defaults.metrics.export.enabled
 *           is considered false
 * </pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@ActiveProfiles("test")
class LoadtestMetricsExposureTest {

    /**
     * aggregate.py가 이름으로 찾는 지표들. 왼쪽 문자열이 바뀌면 집계기도 함께 고쳐야 한다.
     * GC 계열은 #97에서 추가했다 — 요청당 유저 CPU의 차이가 캐시 지역성이 아니라 GC일 가능성을
     * 배제하려면 이 값들이 있어야 한다.
     */
    private static final List<String> REQUIRED = List.of(
        "process_cpu_usage",
        "system_cpu_usage",
        "jvm_gc_memory_allocated_bytes_total",
        "jvm_threads_live_threads",
        "jvm_compilation_time_ms_total",
        // #101 — -Xmx를 t3.small(2GB) 기준으로 재산정하려면 "힙 밖에 얼마나 쓰는가"를 알아야 한다.
        // 집계기가 이 넷으로 힙/논힙/다이렉트를 분해하고, RSS에서 그 합을 빼 잔여 네이티브를 낸다.
        "jvm_memory_used_bytes",
        "jvm_memory_committed_bytes",
        "jvm_memory_max_bytes",
        "jvm_buffer_memory_used_bytes"
    );

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("하네스가 긁어가는 지표가 Prometheus 노출명 그대로 나온다")
    void requiredMetricsAreExposedUnderTheNamesTheHarnessUses() {
        Set<String> exposed = exposedNames();
        assertThat(exposed)
            .as("없는 지표가 있으면 aggregate.py가 그 열을 조용히 비운다. 노출된 jvm_*: %s",
                exposed.stream().filter(n -> n.startsWith("jvm_")).sorted().collect(Collectors.toList()))
            .containsAll(REQUIRED);
    }

    @Test
    @DisplayName("요청이 한 번 들어오면 http_server_requests가 노출된다")
    void httpServerRequestsAppearsAfterFirstRequest() {
        // 이 타이머는 첫 요청이 끝나야 생긴다. 집계기가 TPS와 서버 평균을 전부 여기서 뽑으므로
        // 이름이 어긋나면 표 전체가 빈다. 존재하지 않는 경로여도 uri="NOT_FOUND"로 기록된다.
        rest.getForObject("/api/does-not-exist-" + System.nanoTime(), String.class);
        assertThat(exposedNames())
            .contains("http_server_requests_seconds_count", "http_server_requests_seconds_sum");
    }

    @Test
    @DisplayName("리눅스에서는 system_load_average_1m이 노출된다")
    void systemLoadAverageIsExposedOnLinux() {
        // OperatingSystemMXBean.getSystemLoadAverage()는 Windows에서 -1을 돌려주고,
        // Micrometer는 음수면 게이지를 아예 등록하지 않는다. 개발 머신이 Windows라 여기서는
        // 건너뛰지만, 측정 대상인 App EC2는 Amazon Linux 2023이라 실제로는 노출된다
        // (하네스는 이 값을 /proc/loadavg에서도 따로 받으므로 이중으로 확보돼 있다).
        assumeTrue(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"),
            "Windows에서는 시스템 load average를 얻을 수 없다");
        assertThat(exposedNames()).contains("system_load_average_1m");
    }

    @Test
    @DisplayName("GC 일시정지 지표는 GC가 한 번 일어난 뒤 노출된다")
    void gcPauseMetricAppearsAfterCollection() throws InterruptedException {
        // jvm.gc.pause 타이머는 GC 알림을 받은 시점에 생성된다(bindTo 시점이 아니다).
        // 부하테스트 중에는 반드시 GC가 돌지만 갓 뜬 테스트 JVM에는 아직 없을 수 있어 유발한다.
        System.gc();
        Set<String> exposed = Set.of();
        for (int i = 0; i < 50 && !exposed.contains("jvm_gc_pause_seconds_count"); i++) {
            Thread.sleep(100);            // 알림 리스너가 비동기라 잠깐 기다린다
            exposed = exposedNames();
        }
        assertThat(exposed)
            .as("jvm_gc_pause_seconds_*가 없으면 #97의 GC 차감이 불가능하다")
            .contains("jvm_gc_pause_seconds_count", "jvm_gc_pause_seconds_sum");
    }

    @Test
    @DisplayName("GC 이름 라벨로 이 JVM의 GC 종류를 판별할 수 있다")
    void gcLabelIdentifiesCollector() {
        System.gc();
        String scrape = scrape();
        // 이 라벨이 G1인지 Serial인지를 가른다. -XX 플래그가 하나도 없어 ergonomics가 정한다
        // (t3.micro는 메모리 1792MB 문턱 미달로 SerialGC, t3.small은 G1). 인스턴스 타입을
        // t3.micro -> t3.small로 올린 시점에 조용히 바뀌었고, 그 사실은 #97 실측에서
        // gc="G1 Young Generation" 라벨로 확인됐다
        // (docs/tasks/tomcat-thread-sizing/cpu-cost-decomposition.md).
        assertThat(scrape)
            .as("gc 라벨이 없으면 실측 문서에 GC 종류를 기록할 수 없다")
            .containsPattern("jvm_gc_(pause|memory)[^\\n]*gc=\"[^\"]+\"");
    }

    @Test
    @DisplayName("메모리 풀 지표가 area/id 라벨을 달고 나온다")
    void memoryPoolsCarryAreaAndIdLabels() {
        // 집계기의 힙/논힙 합산은 지표 **이름**이 아니라 area·id **라벨 값**에 의존한다.
        // 이름만 잠그면 라벨 표기가 바뀌었을 때 열이 조용히 0이 되고, 그 상태로도 표는
        // 멀쩡해 보인다 — lettuce_command_*를 놓쳤던 것과 같은 실패 방식이다.
        String scrape = scrape();
        assertThat(scrape).as("area=heap 라벨이 없으면 힙 합산이 0이 된다")
            .contains("jvm_memory_used_bytes{area=" + Q + "heap" + Q);
        assertThat(scrape).as("area=nonheap 라벨이 없으면 논힙 합산이 0이 된다")
            .contains("area=" + Q + "nonheap" + Q);
        assertThat(scrape).as("메타스페이스는 논힙의 대부분을 차지해 따로 뽑는다")
            .contains("id=" + Q + "Metaspace" + Q);
        assertThat(scrape).as("코드 캐시는 id가 CodeHeap으로 시작하는 풀들의 합이다")
            .contains("id=" + Q + "CodeHeap");
        assertThat(scrape).as("다이렉트 버퍼는 메모리 예산의 한 항목이다")
            .contains("jvm_buffer_memory_used_bytes{id=" + Q + "direct" + Q);
    }

    @Test
    @DisplayName("힙 상한은 양수 풀 max의 합과 같다")
    void heapMaxEqualsSumOfPositivePoolMaxes() {
        // switch-heap-arm.sh의 verify가 정확히 이 항등식 위에 서 있다 — -Xmx가 실제로
        // 적용됐는지를 jvm_memory_max_bytes{area="heap"}의 양수 합으로 판정한다.
        // G1은 Eden/Survivor의 max를 -1로 내보내고 Old Gen에만 실제 상한을 싣기 때문에
        // "양수만" 더해야 한다. GC가 바뀌어 풀 구성이 달라지면 이 등식이 깨지고,
        // 그러면 힙 arm 검증이 통째로 무의미해지므로 EC2를 켜기 전에 여기서 잠근다.
        double sum = sumPositive("jvm_memory_max_bytes", "area=" + Q + "heap" + Q);
        assertThat(sum)
            .as("힙 풀 max의 양수 합이 Runtime.maxMemory()와 달라지면 switch-heap-arm.sh가 오판한다")
            .isCloseTo(Runtime.getRuntime().maxMemory(),
                org.assertj.core.data.Offset.offset(Runtime.getRuntime().maxMemory() * 0.01));
    }

    @Test
    @DisplayName("Compressed Class Space는 Metaspace 풀에 포함된다")
    void compressedClassSpaceIsContainedInMetaspace() {
        // 집계기는 논힙 합계에서 CCS를 뺀다. CCS가 Metaspace 풀에 포함돼 있어 그냥 더하면
        // 이중계상이기 때문이다 — JDK 21에서 NMT로 확인했다: Metadata committed 9,306,112 +
        // Class space committed 1,376,256 = 10,682,368 이 MXBean의 Metaspace committed와
        // 정확히 일치했다. MXBean만으로는 포함관계를 직접 증명할 수 없으므로, 여기서는
        // 포함의 필요조건(Metaspace >= CCS)과 두 풀의 존재를 잠근다. 이 조건이 깨지면
        // 포함관계 전제가 바뀐 것이므로 aggregate.py의 뺄셈을 다시 검토해야 한다.
        double metaspace = sumPositive("jvm_memory_committed_bytes", "id=" + Q + "Metaspace" + Q);
        double ccs = sumPositive("jvm_memory_committed_bytes",
            "id=" + Q + "Compressed Class Space" + Q);
        assertThat(ccs).as("CCS 풀이 없으면 뺄셈 자체가 불필요하다 — 전제가 바뀐 것이다")
            .isGreaterThan(0);
        assertThat(metaspace)
            .as("Metaspace가 CCS보다 작으면 포함관계가 성립하지 않는다")
            .isGreaterThanOrEqualTo(ccs);
    }

    /** 큰따옴표. Prometheus 라벨을 문자열로 조립할 때 이스케이프를 피하려고 상수로 둔다. */
    private static final String Q = String.valueOf((char) 34);

    /**
     * {@code /actuator/prometheus}에서 지표 한 종류의 값을 더한다. 라벨 조각으로 걸러내고
     * <b>양수만</b> 더하는 것이 핵심이다 — JVM은 상한이 없는 풀의 max를 -1로 내보낸다.
     * {@code scripts/loadtest/aggregate.py}와 {@code switch-heap-arm.sh}가 쓰는 것과 같은 규칙이다.
     */
    private double sumPositive(String metric, String labelFragment) {
        return scrape().lines()
            .filter(line -> line.startsWith(metric + "{") && line.contains(labelFragment))
            .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
            .filter(v -> v > 0)
            .sum();
    }

    /** 하네스의 {@code poll-metrics.sh}와 같은 방식으로 엔드포인트를 긁는다. */
    private String scrape() {
        String body = rest.getForObject("/actuator/prometheus", String.class);
        assertThat(body).as("/actuator/prometheus가 본문을 내주지 않았다").isNotBlank();
        return body;
    }

    /** {@code /actuator/prometheus}가 내보내는 본문에서 지표 이름만 뽑는다. */
    private Set<String> exposedNames() {
        return Arrays.stream(scrape().split("\n"))
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .map(line -> {
                int brace = line.indexOf('{');
                int space = line.indexOf(' ');
                int end = brace >= 0 && (space < 0 || brace < space) ? brace : space;
                return end > 0 ? line.substring(0, end) : line;
            })
            .collect(Collectors.toSet());
    }
}
