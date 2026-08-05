package backend.yourtrip.global.cloudfront.service;

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

    // Signed URL 서명(ECDSA P-256)에 매 호출마다 필요한 PrivateKey. 과거에는 getSignedUrl() 호출마다
    // Path.of(privateKeyPath)로 PEM 파일을 디스크에서 다시 읽고 파싱했는데, 이 반복 비용이
    // mycourse 상세조회 성능 회귀의 주요 원인 중 하나였다(TASK-CLOUDFRONT.md 참고). 앱 시작 시
    // 1회만 파싱해 캐싱하고, 이후 모든 서명 호출(병렬 호출 포함)에서 재사용한다. PrivateKey는
    // 불변 객체라 여러 스레드가 동시에 읽어도 안전하다.
    // 서명 알고리즘 자체는 RSA-2048에서 ECDSA P-256으로 전환했다(TASK-CLOUDFRONT.md 참고) — CloudFront
    // trusted key group이 지원하는 두 키 타입 중 서명 연산이 훨씬 가벼운 쪽이다. CannedSignerRequest가
    // PrivateKey의 알고리즘(RSA/EC)을 자동 감지해 서명 방식을 고르므로, 이 아래 로직은 키 타입과
    // 무관하게 동일하게 동작한다(AWS SDK 2.40.12+, aws/aws-sdk-java-v2 PR #6627 참고).
    private volatile PrivateKey cachedPrivateKey;

    // 공개 콘텐츠(uploadcourse/feed/프로필 등 "private/"로 시작하지 않는 key) — 서명 없이
    // 문자열만 조합한다. CloudFront 배포의 default cache behavior가 서명을 요구하지 않는다.
    public String getPublicUrl(String key) {
        return "https://" + domain + "/" + key;
    }

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

    // 비공개 콘텐츠(mycourse 장소 이미지, key가 "private/"로 시작) 전용. CloudFront 배포의
    // "private/*" ordered cache behavior가 트러스트 키 그룹 서명을 요구한다.
    public String getSignedUrl(String key) {
        try {
            CannedSignerRequest request = CannedSignerRequest.builder()
                .resourceUrl(getPublicUrl(key))
                .privateKey(cachedPrivateKey)
                .keyPairId(keyPairId)
                .expirationDate(Instant.now().plus(Duration.ofMinutes(signedUrlTtlMinutes)))
                .build();

            SignedUrl signedUrl = cloudFrontUtilities.getSignedUrlWithCannedPolicy(request);
            return signedUrl.url();
        } catch (Exception e) {
            throw new BusinessException(CloudFrontErrorCode.FAIL_GENERATE_SIGNED_URL);
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
