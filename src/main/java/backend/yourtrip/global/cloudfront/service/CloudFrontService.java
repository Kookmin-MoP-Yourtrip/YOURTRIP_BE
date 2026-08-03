package backend.yourtrip.global.cloudfront.service;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.CloudFrontErrorCode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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

    // 공개 콘텐츠(uploadcourse/feed/프로필 등 "private/"로 시작하지 않는 key) — 서명 없이
    // 문자열만 조합한다. CloudFront 배포의 default cache behavior가 서명을 요구하지 않는다.
    public String getPublicUrl(String key) {
        return "https://" + domain + "/" + key;
    }

    // 비공개 콘텐츠(mycourse 장소 이미지, key가 "private/"로 시작) 전용. CloudFront 배포의
    // "private/*" ordered cache behavior가 트러스트 키 그룹 서명을 요구한다.
    public String getSignedUrl(String key) {
        try {
            CannedSignerRequest request = CannedSignerRequest.builder()
                .resourceUrl(getPublicUrl(key))
                .privateKey(Path.of(privateKeyPath))
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
