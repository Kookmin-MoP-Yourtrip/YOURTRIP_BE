package backend.yourtrip.global.benchmark;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.jwt.JwtTokenProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 로컬 부하 측정용 액세스 토큰을 발급해 파일로 쓴다 (#194).
 *
 * <p><b>왜 로그인 API 가 아닌가</b> — 로컬 시드 사용자({@code data.sql})는 비밀번호가 해시로만 남아 있고,
 * 회원가입은 실제 이메일 인증을 거쳐야 한다. 측정은 인증을 재려는 것이 아니라 AI 코스 생성을 재려는
 * 것이므로, 앱 자신의 {@link JwtTokenProvider}와 {@code .env}의 비밀키로 시드 사용자의 토큰을 만든다.
 * 서버가 검증하는 방식과 똑같은 토큰이다.
 *
 * <p><b>값을 출력하지 않는다.</b> gitignore 대상인 {@code results/} 아래 파일에만 쓴다. 토큰은 1시간짜리라
 * 측정마다 새로 발급한다(측정 1회 약 14분).
 *
 * <p>{@code DB_DDL_AUTO=create}라 서버를 띄울 때마다 시드가 다시 들어가므로 user1 의 id 는 1 로 고정이다.
 *
 * <pre>{@code
 * LOADTEST_TOKEN_FILE=results/ai-arrival-194/jwt.txt \
 *     ./gradlew benchmarkTest --tests '*LoadTestTokenIssuer*' --rerun
 * }</pre>
 */
@Tag("benchmark")
@DisplayName("로컬 부하 측정용 액세스 토큰 발급 (#194)")
class LoadTestTokenIssuer {

    private static final long SEED_USER_ID = 1L;
    private static final String SEED_USER_EMAIL = "user1@yourtrip.com";

    @Test
    @DisplayName("시드 사용자의 액세스 토큰을 파일로 쓴다")
    void issue() throws IOException {
        Map<String, String> dotEnv = BenchmarkEnv.loadDotEnv(Path.of(".env"));
        String secret = BenchmarkEnv.resolve(dotEnv, "JWT_SECRET");
        assumeTrue(secret != null, "JWT_SECRET 이 있어야 발급할 수 있다");

        Path out = Path.of(BenchmarkEnv.text("loadtest.token.file", "LOADTEST_TOKEN_FILE",
            "results/loadtest-jwt.txt"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        String token = new JwtTokenProvider(secret).createAccessToken(SEED_USER_ID, SEED_USER_EMAIL);
        Files.writeString(out, token, StandardCharsets.UTF_8);
        System.out.printf("[토큰] user %d 의 액세스 토큰을 %s 에 썼다(%d자)%n", SEED_USER_ID,
            out.toAbsolutePath(), token.length());
    }
}
