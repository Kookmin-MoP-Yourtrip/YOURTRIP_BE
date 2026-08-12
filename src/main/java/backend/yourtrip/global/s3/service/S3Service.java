package backend.yourtrip.global.s3.service;

import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import backend.yourtrip.global.common.MediaKeys;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@Service
@RequiredArgsConstructor
public class S3Service {

    // 공개 콘텐츠(uploadcourse/feed/프로필 등)는 업로드 후 수정 API가 없어 사실상 정적이라
    // 장기 캐싱해도 안전하다(TASK-4.md 재검토 결과). 비공개(mycourse)는 여행 계획 단계에서
    // 추가·삭제가 잦지만, 삭제 시 CloudFrontService.invalidate()가 즉시 캐시를 지우므로
    // TTL 길이 자체가 "삭제 후 잔존 위험"을 방어할 필요는 없다 — 그래도 공개 콘텐츠보다는
    // 짧게 잡아 굳이 오래 들고 있을 이유를 만들지 않는다.
    private static final String CACHE_CONTROL_PUBLIC = "public, max-age=15552000, immutable"; // 6개월
    private static final String CACHE_CONTROL_PRIVATE = "public, max-age=604800, immutable"; // 1주일

    // copy()에서 원본 key의 확장자만으로 Content-Type을 복원하기 위한 역방향 매핑
    // (extensionOf()의 순방향 매핑과 쌍을 이룬다).
    private static final Map<String, String> CONTENT_TYPE_BY_EXTENSION = Map.of(
        ".png", "image/png",
        ".jpg", "image/jpeg",
        ".webp", "image/webp",
        ".mp4", "video/mp4",
        ".mov", "video/quicktime",
        ".webm", "video/webm"
    );

    private final S3Client s3Client;
    private final CloudFrontService cloudFrontService;

    @Value("${s3.max-size-bytes}")
    private long maxSizeBytes;
    @Value("${s3.allowed-content-types}")
    private String allowedContentTypes;
    @Value("${s3.bucket}")
    private String bucket;
    @Value("${s3.region}")
    private String region;

    private volatile Set<String> allowedContentTypeSetCache;

    //허용 타입을 set으로 변환
    private Set<String> allowedContentTypeSet() {
        Set<String> cache = allowedContentTypeSetCache;
        if (cache == null) {
            synchronized (this) {
                cache = allowedContentTypeSetCache;
                if (cache == null) {
                    cache = Stream.of(allowedContentTypes.split(","))
                        .map(String::trim).collect(Collectors.toSet());
                    allowedContentTypeSetCache = cache;
                }
            }
        }
        return cache;
    }

    // 공개 콘텐츠(uploadcourse 썸네일/장소이미지, feed 미디어, 프로필 이미지 등) — CloudFront
    // 배포의 default cache behavior가 서명 없이 서빙한다.
    public UploadResult uploadFile(MultipartFile file) throws IOException {
        return upload(file, MediaKeys.publicPrefix(), CACHE_CONTROL_PUBLIC);
    }

    // 비공개 콘텐츠(mycourse 장소 이미지 전용) — key가 "private/"로 시작해 CloudFront
    // 배포의 "private/*" cache behavior(서명 필수)에 걸린다. courseId를 받는 이유는
    // MediaKeys.privatePrefix() 참고 — 코스 단위 와일드카드 서명의 스코프와 key를 일치시킨다.
    public UploadResult uploadPrivateFile(MultipartFile file, Long courseId) throws IOException {
        return upload(file, MediaKeys.privatePrefix(courseId), CACHE_CONTROL_PRIVATE);
    }

    // keyPrefix는 MediaKeys가 만든 완성된 prefix("uploads/2026-08-11/" 또는 "private/42/")다
    // — 여기서는 파일 식별자만 덧붙인다.
    private UploadResult upload(MultipartFile file, String keyPrefix, String cacheControl)
        throws IOException {
        // 기본 검증
        if (file == null || file.isEmpty()) {
            throw new BusinessException(S3ErrorCode.EMPTY_FILE);
        }
        if (file.getSize() > maxSizeBytes) {
            throw new BusinessException(S3ErrorCode.OVER_SIZE_FILE);
        }

        // Content-Type 화이트리스트
        String contentType = safe(file.getContentType());
        Set<String> allow = allowedContentTypeSet();

        if (!allow.contains(contentType)) {
            throw new BusinessException(S3ErrorCode.NOT_ALLOW_FILE_TYPE);
        }

        // 키 생성
        String ext = extensionOf(contentType);
        String key = keyPrefix + UUID.randomUUID() + ext;

        // 업로드
        PutObjectRequest put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .cacheControl(cacheControl)
            // .serverSideEncryption("AES256") // SSE-S3 (기본 암호화 켰으면 생략 가능)
            .build();

        // InputStream 두 번 썼으니 새로 열기 ??
        try (InputStream is2 = file.getInputStream()) {
            s3Client.putObject(put, RequestBody.fromInputStream(is2, file.getSize()));
        }

        String url =
            "https://" + bucket + ".s3." + region + ".amazonaws.com/" + key;
        return new UploadResult(key, url, file.getOriginalFilename(), contentType, file.getSize());
    }

    private String extensionOf(String contentType) {
        return switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            case "video/mp4" -> ".mp4";
            case "video/quicktime" -> ".mov";
            case "video/webm" -> ".webm";
            default -> ".bin";
        };
    }

    // 업로드(mycourse→uploadcourse)/포크(uploadcourse→mycourse) 시 S3 오브젝트를 실제로
    // 복사해 물리적으로 분리한다. key 문자열만 복사하면 원본과 사본이 같은 오브젝트를 가리켜
    // "공개/비공개" 경계가 무너지기 때문이다(MyCourseServiceImpl.copyMyCourseWithSchedule 참고).
    public String copyToPublic(String privateKey) {
        return copy(privateKey, MediaKeys.publicPrefix(), CACHE_CONTROL_PUBLIC);
    }

    // targetCourseId는 사본이 속하게 될 코스다(원본 코스가 아니다) — 사본의 key가 사본
    // 코스의 서명 스코프에 들어가야 하기 때문. fork 시 새로 채번된 코스 ID를 넘긴다.
    public String copyToPrivate(String publicKey, Long targetCourseId) {
        return copy(publicKey, MediaKeys.privatePrefix(targetCourseId), CACHE_CONTROL_PRIVATE);
    }

    // targetPrefix는 upload()와 동일하게 MediaKeys가 만든 완성된 prefix다.
    private String copy(String sourceKey, String targetPrefix, String cacheControl) {
        int dotIndex = sourceKey.lastIndexOf('.');
        String ext = dotIndex >= 0 ? sourceKey.substring(dotIndex) : ".bin";
        String contentType = CONTENT_TYPE_BY_EXTENSION.getOrDefault(ext, "application/octet-stream");
        String targetKey = targetPrefix + UUID.randomUUID() + ext;

        try {
            // MetadataDirective.REPLACE — 원본의 Cache-Control은 이전 가시성(공개/비공개)
            // 기준으로 설정된 값이라 그대로 복사하면 안 된다. 대상 가시성에 맞는 값을
            // 명시적으로 다시 지정한다.
            CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                .sourceBucket(bucket)
                .sourceKey(sourceKey)
                .destinationBucket(bucket)
                .destinationKey(targetKey)
                .contentType(contentType)
                .cacheControl(cacheControl)
                .metadataDirective(MetadataDirective.REPLACE)
                .build();

            s3Client.copyObject(copyRequest);
        } catch (S3Exception e) {
            throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
        }

        return targetKey;
    }

    private String safe(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /**
     * 매우 간단한 시그니처 확인 (PNG/JPEG/WEBP). 필요하면 더 정교하게 확장 가능
     */
    private boolean looksLikeImage(byte[] head, String contentType) {
        if (head == null || head.length < 12) {
            return false;
        }

        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (contentType.equals("image/png")) {
            return head[0] == (byte) 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47;
        }
        // JPEG: FF D8 FF
        if (contentType.equals("image/jpeg")) {
            return head[0] == (byte) 0xFF && head[1] == (byte) 0xD8 && head[2] == (byte) 0xFF;
        }
        // WEBP: "RIFF"...."WEBP"
        if (contentType.equals("image/webp")) {
            return head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'W' && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
        }
        return false;
    }

    public record UploadResult(String key, String url, String originalName, String contentType,
                               long size) {

    }

    public void deleteFile(String key) {
        if (key == null || key.isBlank()) {
            throw new BusinessException(S3ErrorCode.INVALID_S3_KEY);
        }

        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

            s3Client.deleteObject(deleteRequest);

        } catch (S3Exception e) {
            throw new BusinessException(S3ErrorCode.FAIL_DELETE_FILE);
        }

        // 삭제 자체는 이미 끝났으므로 CDN 캐시 무효화 실패가 이 메서드를 실패시키지 않는다
        // (CloudFrontService.invalidate() 내부에서 fail-open 처리).
        cloudFrontService.invalidate(key);
    }

}
