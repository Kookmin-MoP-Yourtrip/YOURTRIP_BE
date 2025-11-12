package backend.yourtrip.global.s3.service;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
public class S3ImageUploadService {

    private final S3Client s3Client;
    @Value("${s3.max-size-bytes}")
    private long maxSizeBytes;
    @Value("${s3.allowed-content-types}")
    private String allowedContentTypes;
    @Value("${s3.bucket}")
    private String bucket;
    @Value("${s3.region}")
    private String region;

    //허용 타입을 set으로 변환
    private Set<String> allowedContentTypeSet() {
        return Stream.of(allowedContentTypes.split(","))
            .map(String::trim).collect(Collectors.toSet());
    }

    public UploadResult uploadImage(MultipartFile file) throws IOException {
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
        String ext = switch (contentType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
        String key = "uploads/images/" + LocalDate.now() + "/" + UUID.randomUUID() + ext;

        // 업로드
        PutObjectRequest put = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .cacheControl("public, max-age=3600, immutable") // 필요 시 조정
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
}
