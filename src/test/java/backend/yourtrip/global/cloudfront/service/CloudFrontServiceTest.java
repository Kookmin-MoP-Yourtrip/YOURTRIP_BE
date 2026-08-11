package backend.yourtrip.global.cloudfront.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.cloudfront.service.CloudFrontService.CourseSignature;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;

/**
 * RSA-2048 → ECDSA P-256 전환(TASK-CLOUDFRONT.md 참고) 검증용. openssl로 실제
 * terraform/README.md 절차와 동일하게 키를 생성해, SDK가 두 키 타입 모두 실제로 서명
 * 가능한지 확인한다(회귀 시 RSA 케이스가 먼저 잡아준다).
 *
 * <p>1단계에서 canned policy → custom policy(코스 단위 와일드카드)로 바꿨는데, SDK는 키
 * 타입에 따라 SHA1withRSA/SHA1withECDSA로 분기하므로 <b>두 키 타입 케이스를 그대로
 * 유지해야</b> 그 분기가 custom policy 경로에서도 살아있는지 확인할 수 있다.
 */
class CloudFrontServiceTest {

    @TempDir
    private Path tempDir;

    private CloudFrontService cloudFrontService;

    @BeforeEach
    void setUp() {
        assumeTrue(isOpensslAvailable(), "이 테스트는 로컬 openssl 실행 파일이 필요하다");

        cloudFrontService = new CloudFrontService(CloudFrontUtilities.create(), null);
        ReflectionTestUtils.setField(cloudFrontService, "domain", "d111111abcdef8.cloudfront.net");
        ReflectionTestUtils.setField(cloudFrontService, "keyPairId", "K3TESTKEYPAIRID");
        ReflectionTestUtils.setField(cloudFrontService, "signedUrlTtlMinutes", 60L);
    }

    @Test
    @DisplayName("ECDSA P-256 개인키(terraform/README.md 절차로 생성)로 Signed URL을 정상 발급한다")
    void getSignedUrl_WithEcdsaPrivateKey_ReturnsValidSignedUrl() throws Exception {
        Path privateKeyPath = tempDir.resolve("ecdsa_private_key.pem");
        Path publicKeyPath = tempDir.resolve("ecdsa_public_key.pem");
        // openssl ecparam -genkey는 SEC1("BEGIN EC PRIVATE KEY") 형식으로 출력하는데, AWS SDK의
        // Pem 파서가 이 헤더를 인식하지 못해 NPE가 난다(PemObjectType.ordinal() on null, 이 테스트로
        // 실제 확인됨). genpkey는 PKCS8("BEGIN PRIVATE KEY") 형식으로 출력해 정상 로드된다.
        runOpenssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-out", privateKeyPath.toString());
        runOpenssl("ec", "-in", privateKeyPath.toString(), "-pubout", "-out", publicKeyPath.toString());

        assertSignedUrlIsIssued(privateKeyPath);
    }

    @Test
    @DisplayName("RSA-2048 개인키(전환 전 방식)로도 Signed URL을 정상 발급한다 - 회귀 방지")
    void getSignedUrl_WithRsaPrivateKey_ReturnsValidSignedUrl() throws Exception {
        Path privateKeyPath = tempDir.resolve("rsa_private_key.pem");
        Path publicKeyPath = tempDir.resolve("rsa_public_key.pem");
        runOpenssl("genrsa", "-out", privateKeyPath.toString(), "2048");
        runOpenssl("rsa", "-pubout", "-in", privateKeyPath.toString(), "-out", publicKeyPath.toString());

        assertSignedUrlIsIssued(privateKeyPath);
    }

    @Test
    @DisplayName("정책의 Resource가 코스 단위 와일드카드로 들어간다 - 코스당 서명 1회의 근거")
    void signCourseScope_EmbedsCourseScopedWildcardInPolicy() throws Exception {
        initEcdsaKey();

        String policy = decodePolicy(cloudFrontService.signCourseScope(42L));

        // 이 단언이 "이미지마다 서명하지 않아도 되는 이유" 자체다 — Resource가 특정 파일이
        // 아니라 코스 폴더 전체를 가리키므로, 같은 서명을 그 폴더의 어떤 key에도 붙일 수 있다.
        assertThat(policy)
            .contains("\"Resource\":\"https://d111111abcdef8.cloudfront.net/private/42/*\"")
            .contains("DateLessThan");
    }

    @Test
    @DisplayName("코스가 다르면 정책 Resource도 달라져 서명 스코프가 코스 경계에서 잘린다")
    void signCourseScope_ScopesPolicyPerCourse() throws Exception {
        initEcdsaKey();

        assertThat(decodePolicy(cloudFrontService.signCourseScope(1L)))
            .contains("/private/1/*")
            .doesNotContain("/private/2/*");
        assertThat(decodePolicy(cloudFrontService.signCourseScope(2L)))
            .contains("/private/2/*");
    }

    @Test
    @DisplayName("서명 1건의 쿼리스트링을 같은 코스의 여러 key에 붙여 URL을 조립한다")
    void courseSignature_ReusesSameQueryStringAcrossKeys() throws Exception {
        initEcdsaKey();

        CourseSignature signature = cloudFrontService.signCourseScope(42L);
        String first = signature.signedUrlFor("private/42/a.jpg");
        String second = signature.signedUrlFor("private/42/b.jpg");

        assertThat(first).startsWith("https://d111111abcdef8.cloudfront.net/private/42/a.jpg?");
        assertThat(second).startsWith("https://d111111abcdef8.cloudfront.net/private/42/b.jpg?");
        // 경로만 다르고 서명 파라미터는 완전히 동일해야 한다
        assertThat(queryStringOf(first)).isEqualTo(queryStringOf(second));
    }

    private void initEcdsaKey() throws Exception {
        Path privateKeyPath = tempDir.resolve("scope_private_key.pem");
        runOpenssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-out", privateKeyPath.toString());
        ReflectionTestUtils.setField(cloudFrontService, "privateKeyPath", privateKeyPath.toString());
        ReflectionTestUtils.invokeMethod(cloudFrontService, "initPrivateKey");
    }

    /**
     * CloudFront는 정책 JSON을 base64로 인코딩한 뒤 URL-safe 치환('+'→'-', '='→'_', '/'→'~')을
     * 적용한다. 검증하려면 그 치환을 되돌려야 한다.
     */
    private String decodePolicy(CourseSignature signature) {
        String encoded = Arrays.stream(signature.queryString().split("&"))
            .filter(param -> param.startsWith("Policy="))
            .map(param -> param.substring("Policy=".length()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("쿼리스트링에 Policy 파라미터가 없다: " + signature));

        String base64 = encoded.replace('-', '+').replace('_', '=').replace('~', '/');
        return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
    }

    private String queryStringOf(String url) {
        return url.substring(url.indexOf('?') + 1);
    }

    private void assertSignedUrlIsIssued(Path privateKeyPath) {
        ReflectionTestUtils.setField(cloudFrontService, "privateKeyPath", privateKeyPath.toString());
        ReflectionTestUtils.invokeMethod(cloudFrontService, "initPrivateKey");

        CourseSignature signature = cloudFrontService.signCourseScope(42L);
        String signedUrl = signature.signedUrlFor("private/42/test.png");

        assertThat(signedUrl)
            .startsWith("https://d111111abcdef8.cloudfront.net/private/42/test.png?")
            .contains("Key-Pair-Id=K3TESTKEYPAIRID")
            .contains("Signature=")
            // canned policy는 만료시각을 Expires 파라미터로 싣지만, custom policy는 만료 조건이
            // Policy JSON 안에 들어가므로 Expires가 없어야 정상이다.
            .contains("Policy=")
            .doesNotContain("Expires=");
    }

    private void runOpenssl(String... args) throws IOException, InterruptedException {
        String[] command = new String[args.length + 1];
        command[0] = "openssl";
        System.arraycopy(args, 0, command, 1, args.length);

        Process process = new ProcessBuilder(command)
            .redirectErrorStream(true)
            .start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes());

        assertThat(finished).as("openssl 명령이 시간 내에 끝나야 한다: %s", output).isTrue();
        assertThat(process.exitValue()).as("openssl 명령이 성공해야 한다: %s", output).isZero();
    }

    private static boolean isOpensslAvailable() {
        try {
            Process process = new ProcessBuilder("openssl", "version").start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
