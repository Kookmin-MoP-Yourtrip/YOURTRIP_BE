package backend.yourtrip.global.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * test/presigned-url-bottleneck 전용: "presign CPU가 병목"이라는 원 가설을 판정하려면
 * 서명 1회 비용의 자릿수를 알아야 한다 — 이론 최대 TPS = (코어수 × 1초) ÷ (이미지수 × 1회 비용)을
 * 계산해 실측 TPS와 대조하는 것이 Phase 5 분석의 핵심 축이다(계획 문서 Phase 2-4/5-2 참고).
 *
 * JMH가 아니라 워밍업 후 반복 측정하는 수동 루프다 — 여기서 필요한 건 정밀한 ns가 아니라
 * "presign 1회가 마이크로초 단위인지 밀리초 단위인지"라는 자릿수이므로 JMH의 정밀도(격리된
 * JVM, forking, 통계적 오차범위)까지는 필요 없다고 판단했다. 이 한계는 결과 문서에도 명시한다.
 *
 * 일반 빌드(`./gradlew test`)에서는 실행되지 않는다 — build.gradle의
 * `test { useJUnitPlatform { excludeTags 'benchmark' } }` 참고.
 */
@Tag("benchmark")
class SigningBenchmarkTest {

    private static final int WARMUP_ITERATIONS = 2_000;
    private static final int MEASURED_ITERATIONS = 10_000;

    @Test
    @DisplayName("S3 presign(SigV4 HMAC) 1회 비용을 측정하고 이론 최대 TPS를 계산한다")
    void benchmarkS3Presign() {
        AwsBasicCredentials creds = AwsBasicCredentials.create("AKIADUMMYACCESSKEYX", "dummySecretKeyDummySecretKeyDummySecretKey");
        S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(creds))
            .build();

        Runnable op = () -> {
            GetObjectPresignRequest req = GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofMinutes(15))
                .getObjectRequest(r -> r.bucket("benchmark-bucket").key("uploads/benchmark.jpg"))
                .build();
            presigner.presignGetObject(req);
        };

        report("S3 presign (SigV4 HMAC-SHA256)", measure(op));
        presigner.close();
    }

    @Test
    @DisplayName("CloudFront Signed URL(ECDSA P-256) 1회 비용을 측정하고 이론 최대 TPS를 계산한다")
    void benchmarkCloudFrontEcdsaSign() throws Exception {
        assumeTrue(isOpensslAvailable(), "이 벤치마크는 로컬 openssl 실행 파일이 필요하다");

        Path privateKeyPath = tempKeyFile();
        runOpenssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-out", privateKeyPath.toString());

        CloudFrontService cloudFrontService = new CloudFrontService(CloudFrontUtilities.create(), null);
        ReflectionTestUtils.setField(cloudFrontService, "domain", "d111111abcdef8.cloudfront.net");
        ReflectionTestUtils.setField(cloudFrontService, "keyPairId", "K3BENCHMARKKEYPAIR");
        ReflectionTestUtils.setField(cloudFrontService, "signedUrlTtlMinutes", 60L);
        ReflectionTestUtils.setField(cloudFrontService, "privateKeyPath", privateKeyPath.toString());
        ReflectionTestUtils.invokeMethod(cloudFrontService, "initPrivateKey");

        // 1단계 이후 서명 단위는 "이미지 1장"이 아니라 "코스 1개"다 — 이 호출 1회가 그 코스의
        // 모든 이미지 URL을 커버한다.
        Runnable op = () -> cloudFrontService.signCourseScope(42L);

        report("CloudFront 코스 스코프 서명 (custom policy, ECDSA P-256)", measure(op));
    }

    @Test
    @DisplayName("canned policy와 custom policy의 서명 1회 비용을 직접 비교한다 — 1단계 전환의 비용 항목")
    void benchmarkCannedVsCustomPolicy() throws Exception {
        assumeTrue(isOpensslAvailable(), "이 벤치마크는 로컬 openssl 실행 파일이 필요하다");

        Path privateKeyPath = tempKeyFile();
        runOpenssl("genpkey", "-algorithm", "EC", "-pkeyopt", "ec_paramgen_curve:prime256v1",
            "-out", privateKeyPath.toString());

        CloudFrontUtilities utilities = CloudFrontUtilities.create();
        PrivateKey privateKey = CannedSignerRequest.builder()
            .privateKey(privateKeyPath)
            .build()
            .privateKey();
        String resourceUrl = "https://d111111abcdef8.cloudfront.net/private/42/benchmark.jpg";
        Instant expiration = Instant.now().plus(Duration.ofMinutes(60));

        // 전환 전 방식 — 이미지 하나를 정확히 지목하는 정책
        Runnable canned = () -> utilities.getSignedUrlWithCannedPolicy(CannedSignerRequest.builder()
            .resourceUrl(resourceUrl)
            .privateKey(privateKey)
            .keyPairId("K3BENCHMARKKEYPAIR")
            .expirationDate(expiration)
            .build());

        // 전환 후 방식 — 코스 폴더 전체를 가리키는 와일드카드 정책. 정책 JSON이 길어져
        // SHA1 입력이 커지므로 이론상 조금 더 비쌀 수 있는데, 그 차이가 실제로 유의미한지 본다.
        Runnable custom = () -> utilities.getSignedUrlWithCustomPolicy(CustomSignerRequest.builder()
            .resourceUrl("https://d111111abcdef8.cloudfront.net/private/42/")
            .resourceUrlPattern("https://d111111abcdef8.cloudfront.net/private/42/*")
            .privateKey(privateKey)
            .keyPairId("K3BENCHMARKKEYPAIR")
            .expirationDate(expiration)
            .build());

        long cannedNs = measure(canned);
        long customNs = measure(custom);

        System.out.printf("%n=== canned vs custom policy (서명 1회 비용) ===%n");
        System.out.printf("canned policy: %.2f us/op%n", cannedNs / 1000.0);
        System.out.printf("custom policy: %.2f us/op (canned 대비 %+.1f%%)%n",
            customNs / 1000.0, (customNs - cannedNs) * 100.0 / cannedNs);
        System.out.printf("→ 이미지 10장 기준 요청당 서명 비용: %.2f us → %.2f us (%.1f배 감소)%n",
            cannedNs * 10 / 1000.0, customNs / 1000.0, (cannedNs * 10.0) / customNs);

        assertThat(cannedNs).isPositive();
        assertThat(customNs).isPositive();
    }

    @Test
    @DisplayName("서명 없는 CloudFront public URL(문자열 결합) 1회 비용을 측정한다 — 서명 유무 대조 기준선")
    void benchmarkCloudFrontPublicUrl() {
        CloudFrontService cloudFrontService = new CloudFrontService(CloudFrontUtilities.create(), null);
        ReflectionTestUtils.setField(cloudFrontService, "domain", "d111111abcdef8.cloudfront.net");

        Runnable op = () -> cloudFrontService.getPublicUrl("uploads/benchmark.jpg");

        report("CloudFront public URL (서명 없음, 문자열 결합)", measure(op));
    }

    private long measure(Runnable op) {
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            op.run();
        }
        long start = System.nanoTime();
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            op.run();
        }
        long elapsed = System.nanoTime() - start;
        return elapsed / MEASURED_ITERATIONS; // ns/op
    }

    private void report(String label, long nsPerOp) {
        double usPerOp = nsPerOp / 1000.0;
        int cores = Runtime.getRuntime().availableProcessors();

        System.out.printf("%n=== %s ===%n", label);
        System.out.printf("1회 비용: %.2f us/op (%,d ns/op)%n", usPerOp, nsPerOp);
        System.out.printf("이 머신(%d코어) 기준 이론 최대 서명 처리량: %,d ops/sec (모든 코어를 서명에만 쓸 때)%n",
            cores, (long) (cores * 1_000_000_000.0 / nsPerOp));

        // 1단계(코스당 서명 1회) 이후에는 요청당 서명이 1회로 고정돼, 이론 최대 TPS가
        // 이미지 개수와 무관한 상수가 된다 — 그 사실 자체가 이번 전환의 핵심이다.
        double maxTps = cores * 1_000_000_000.0 / nsPerOp;
        System.out.printf("  요청당 서명 1회 기준 이론 최대 TPS: %.1f (이미지 개수와 무관)%n", maxTps);

        // 전환 전 모델과의 대조 — 예전에는 요청당 이미지 수만큼 서명해서 TPS가 이미지 개수에
        // 반비례했다. TASK-4.md/TASK-CLOUDFRONT.md 시드의 코스당 이미지 개수를 그대로 쓴다.
        System.out.println("  [참고] 전환 전 모델(이미지당 1회 서명)이었다면:");
        for (int imagesPerRequest : new int[] {10, 24, 105}) {
            double legacyMaxTps = maxTps / imagesPerRequest;
            System.out.printf("    코스당 이미지 %3d장 → 이론 최대 TPS %.1f (현재 대비 1/%d)%n",
                imagesPerRequest, legacyMaxTps, imagesPerRequest);
        }

        assertThat(nsPerOp).isPositive();
    }

    @TempDir
    private Path tempDir;

    private Path tempKeyFile() {
        return tempDir.resolve("benchmark_private_key.pem");
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
