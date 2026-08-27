package backend.yourtrip.global.benchmark;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 실호출 벤치마크의 실행 파라미터와 API 키를 읽는다.
 *
 * <p>Spring 컨텍스트를 띄우지 않으므로 spring-dotenv 가 동작하지 않는다 — 레포 루트
 * {@code .env}를 직접 파싱하고, <b>실제 OS 환경변수가 있으면 그것을 우선한다</b>
 * (spring-dotenv 와 같은 우선순위).
 *
 * <p>{@code test} 프로필을 쓰지 않는 이유도 같은 자리에 적어 둔다. 그 프로필은 {@code .env} 없이
 * 자급하도록 설계됐고 API 키가 전부 더미다(CI 가 시크릿 없이 서 있는 전제). 실호출 측정은
 * 진짜 키를 필요로 하므로 프로필을 쓰면 그 설계를 깨야 한다.
 */
public final class BenchmarkEnv {

    private BenchmarkEnv() {
    }

    /**
     * 실행 파라미터를 시스템 프로퍼티 → 환경변수 → 기본값 순으로 읽는다.
     *
     * <p>Gradle의 {@code Test} task는 {@code -D}로 준 시스템 프로퍼티를 테스트 JVM에 자동 전달하지
     * 않지만(전달하려면 build.gradle에 {@code systemProperties} 설정이 필요하다) 환경변수는 자식
     * 프로세스에 상속된다. build.gradle을 건드리지 않고 조절할 수 있도록 둘 다 지원한다.
     */
    public static long setting(String systemProperty, String envVar, long defaultValue) {
        String raw = System.getProperty(systemProperty);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envVar);
        }
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /** {@link #setting}의 문자열 버전. 측정 축(모델·출력 강제 방식) 선택에 쓴다. */
    public static String text(String systemProperty, String envVar, String defaultValue) {
        String raw = System.getProperty(systemProperty);
        if (raw == null || raw.isBlank()) {
            raw = System.getenv(envVar);
        }
        return (raw == null || raw.isBlank()) ? defaultValue : raw.trim();
    }

    /** Spring 컨텍스트가 없어 spring-dotenv 가 동작하지 않으므로 .env 를 직접 읽는다. */
    public static Map<String, String> loadDotEnv(Path path) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        if (!Files.exists(path)) {
            return env;
        }
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = trimmed.substring(0, eq).trim();
            String value = trimmed.substring(eq + 1).trim();
            if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
                value = value.substring(1, value.length() - 1);
            }
            if (!value.isEmpty()) {
                env.put(key, value);
            }
        }
        return env;
    }

    /** 실제 OS 환경변수가 있으면 그것을 우선한다 — spring-dotenv 와 동일한 우선순위. */
    public static String resolve(Map<String, String> dotEnv, String key) {
        String fromOs = System.getenv(key);
        if (fromOs != null && !fromOs.isBlank()) {
            return fromOs;
        }
        return dotEnv.get(key);
    }

    public static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
