package backend.yourtrip.global.benchmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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

        Runnable op = () -> cloudFrontService.getSignedUrl("private/benchmark.jpg");

        report("CloudFront Signed URL (ECDSA P-256)", measure(op));
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

        // TASK-4.md/TASK-CLOUDFRONT.md에서 실제 시드에 쓰인 코스당 이미지 개수 기준
        // 이론 최대 TPS를 함께 계산해, Phase 4 실측 TPS와 바로 대조할 수 있게 한다.
        for (int imagesPerRequest : new int[] {10, 24, 105}) {
            double theoreticalMaxTps = cores * 1_000_000_000.0 / ((double) nsPerOp * imagesPerRequest);
            System.out.printf("  코스당 이미지 %3d장일 때 이론 최대 TPS: %.1f%n", imagesPerRequest, theoreticalMaxTps);
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
