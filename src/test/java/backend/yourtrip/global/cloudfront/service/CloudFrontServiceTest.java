package backend.yourtrip.global.cloudfront.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;

/**
 * RSA-2048 → ECDSA P-256 전환(TASK-CLOUDFRONT.md 참고) 검증용. openssl로 실제
 * terraform/README.md 절차와 동일하게 키를 생성해, CannedSignerRequest가 두 키 타입
 * 모두 실제로 서명 가능한지 확인한다(회귀 시 RSA 케이스가 먼저 잡아준다).
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

    private void assertSignedUrlIsIssued(Path privateKeyPath) {
        ReflectionTestUtils.setField(cloudFrontService, "privateKeyPath", privateKeyPath.toString());
        ReflectionTestUtils.invokeMethod(cloudFrontService, "initPrivateKey");

        String signedUrl = cloudFrontService.getSignedUrl("private/2026/08/05/test.png");

        assertThat(signedUrl)
            .startsWith("https://d111111abcdef8.cloudfront.net/private/2026/08/05/test.png?")
            .contains("Key-Pair-Id=K3TESTKEYPAIRID")
            .contains("Signature=")
            .contains("Expires=");
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
