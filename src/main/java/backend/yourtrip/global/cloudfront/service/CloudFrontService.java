package backend.yourtrip.global.cloudfront.service;

import backend.yourtrip.global.common.MediaKeys;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.CloudFrontErrorCode;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.cloudfront.CloudFrontClient;
import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.model.CannedSignerRequest;
import software.amazon.awssdk.services.cloudfront.model.CloudFrontException;
import software.amazon.awssdk.services.cloudfront.model.CreateInvalidationRequest;
import software.amazon.awssdk.services.cloudfront.model.CustomSignerRequest;
import software.amazon.awssdk.services.cloudfront.model.InvalidationBatch;
import software.amazon.awssdk.services.cloudfront.model.Paths;
import software.amazon.awssdk.services.cloudfront.url.SignedUrl;

@Slf4j
@Service
@RequiredArgsConstructor
public class CloudFrontService {

    private final CloudFrontUtilities cloudFrontUtilities;
    private final CloudFrontClient cloudFrontClient;

    @Value("${cloudfront.domain}")
    private String domain;
    @Value("${cloudfront.key-pair-id}")
    private String keyPairId;
    @Value("${cloudfront.private-key-path}")
    private String privateKeyPath;
    @Value("${cloudfront.distribution-id}")
    private String distributionId;
    @Value("${cloudfront.signed-url-ttl-minutes}")
    private long signedUrlTtlMinutes;

    // Signed URL 서명(ECDSA P-256)에 매 호출마다 필요한 PrivateKey. 과거에는 서명 호출마다
    // Path.of(privateKeyPath)로 PEM 파일을 디스크에서 다시 읽고 파싱했는데, 이 반복 비용이
    // mycourse 상세조회 성능 회귀의 주요 원인 중 하나였다(TASK-CLOUDFRONT.md 참고). 앱 시작 시
    // 1회만 파싱해 캐싱하고, 이후 모든 서명 호출에서 재사용한다. PrivateKey는 불변 객체라
    // 여러 스레드가 동시에 읽어도 안전하다.
    // 서명 알고리즘 자체는 RSA-2048에서 ECDSA P-256으로 전환했다(TASK-CLOUDFRONT.md 참고) — CloudFront
    // trusted key group이 지원하는 두 키 타입 중 서명 연산이 훨씬 가벼운 쪽이다. SDK가 PrivateKey의
    // 알고리즘(RSA/EC)을 자동 감지해 서명 방식(SHA1withRSA/SHA1withECDSA)을 고르므로, 아래 로직은
    // 키 타입과 무관하게 동일하게 동작한다(AWS SDK 2.40.12+, aws/aws-sdk-java-v2 PR #6627 참고).
    private volatile PrivateKey cachedPrivateKey;

    // 공개 콘텐츠(uploadcourse/feed/프로필 등 "private/"로 시작하지 않는 key) — 서명 없이
    // 문자열만 조합한다. CloudFront 배포의 default cache behavior가 서명을 요구하지 않는다.
    public String getPublicUrl(String key) {
        return "https://" + domain + "/" + key;
    }

    // PEM 파싱만을 위해 CannedSignerRequest를 빌려 쓴다(서명 자체는 custom policy로 한다) —
    // SDK가 PEM→PrivateKey 변환을 별도 public API로 노출하지 않아서다.
    @PostConstruct
    void initPrivateKey() {
        try {
            this.cachedPrivateKey = CannedSignerRequest.builder()
                .privateKey(Path.of(privateKeyPath))
                .build()
                .privateKey();
        } catch (Exception e) {
            throw new IllegalStateException("CloudFront private key 로드 실패 (path=" + privateKeyPath + ")", e);
        }
    }

    /**
     * 한 코스의 비공개 이미지 전체에 대한 서명을 <b>1회</b>만 수행한다.
     *
     * <p>기존에는 이미지 한 장마다 canned policy로 개별 서명했다(요청당 최대 수십 회).
     * custom policy의 {@code Resource}는 와일드카드를 지원하므로, {@code private/{courseId}/*}
     * 하나로 서명하고 그 결과 쿼리스트링을 그 코스의 모든 이미지 URL에 재사용할 수 있다.
     * CloudFront가 "서명 진위 검증"과 "요청 경로가 Resource 패턴에 매칭되는지"를 분리해서
     * 검사하기 때문에 가능한 방식이다(실배포 PoC로 확인 — stage1/design-and-poc.md).
     *
     * <p>{@code resourceUrl}과 {@code resourceUrlPattern}을 모두 지정하는 이유: SDK는 정책의
     * Resource로 {@code resourceUrlPattern}을 쓰지만(없으면 resourceUrl로 대체), 반환 URL은
     * {@code resourceUrl}을 {@code URI.create()}로 파싱해 조립한다. 즉 resourceUrl은 null일 수
     * 없다. 어차피 반환 URL 자체는 버리고 쿼리스트링만 취하므로 스코프 베이스를 그대로 넘긴다.
     *
     * <p>{@code activeDate}/{@code ipRange}는 쓰지 않는다 — 모바일 클라이언트는 셀룰러↔WiFi
     * 전환으로 출발 IP가 바뀌어 ipRange 제약이 정상 요청을 차단한다.
     */
    public CourseSignature signCourseScope(Long courseId) {
        try {
            String scopeBase = getPublicUrl(MediaKeys.privatePrefix(courseId));

            CustomSignerRequest request = CustomSignerRequest.builder()
                .resourceUrl(scopeBase)
                .resourceUrlPattern(scopeBase + "*")
                .privateKey(cachedPrivateKey)
                .keyPairId(keyPairId)
                .expirationDate(Instant.now().plus(Duration.ofMinutes(signedUrlTtlMinutes)))
                .build();

            SignedUrl signedUrl = cloudFrontUtilities.getSignedUrlWithCustomPolicy(request);
            return CourseSignature.of(domain, signedUrl.url());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(CloudFrontErrorCode.FAIL_GENERATE_SIGNED_URL, e);
        }
    }

    /**
     * 코스 스코프 서명 1건의 결과. 이 쿼리스트링({@code Policy}/{@code Signature}/
     * {@code Key-Pair-Id})은 같은 코스의 어떤 이미지 URL에도 그대로 붙일 수 있다.
     *
     * <p>쿼리스트링 문자열을 그대로 노출하지 않고 값 객체로 감싼 이유는, 호출부마다
     * {@code url + "?" + qs}를 직접 조립하면 도메인 조립이 중복되고 {@code ?}/{@code &}를
     * 틀리기 쉬워서다.
     */
    public record CourseSignature(String domain, String queryString) {

        static CourseSignature of(String domain, String signedUrl) {
            int queryStart = signedUrl.indexOf('?');
            if (queryStart < 0) {
                throw new BusinessException(CloudFrontErrorCode.FAIL_GENERATE_SIGNED_URL);
            }
            return new CourseSignature(domain, signedUrl.substring(queryStart + 1));
        }

        /**
         * 이 서명의 스코프 안에 있는 key에만 유효하다. 스코프 밖 key에 붙이면 CloudFront가
         * 403을 반환한다(경로가 정책의 Resource 패턴에 매칭되지 않기 때문).
         */
        public String signedUrlFor(String s3Key) {
            return "https://" + domain + "/" + s3Key + "?" + queryString;
        }
    }

    // S3Service.deleteFile()이 객체 삭제 후 호출한다. 삭제 자체는 이미 끝난 뒤라 invalidation
    // 실패가 삭제를 막을 이유가 없으므로 여기서 예외를 삼키고 WARN 로그만 남긴다(fail-open).
    // 실패해도 해당 key의 Cache-Control TTL이 지나면 결국 자연 소멸한다(안전망 존재).
    public void invalidate(String key) {
        try {
            CreateInvalidationRequest request = CreateInvalidationRequest.builder()
                .distributionId(distributionId)
                .invalidationBatch(InvalidationBatch.builder()
                    .paths(Paths.builder()
                        .quantity(1)
                        .items("/" + key)
                        .build())
                    .callerReference(UUID.randomUUID().toString())
                    .build())
                .build();

            cloudFrontClient.createInvalidation(request);
        } catch (CloudFrontException e) {
            log.warn("CloudFront invalidation 실패 (key={})", key, e);
        }
    }
}
