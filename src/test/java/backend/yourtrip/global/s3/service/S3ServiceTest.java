package backend.yourtrip.global.s3.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import java.io.IOException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 key 조립 규약을 고정하는 테스트.
 *
 * <p>key를 만드는 코드는 {@code upload()}와 {@code copy()} 두 곳에 각각 존재해서, 한쪽만
 * 고치면 업로드 경로와 복사(fork/업로드 사본) 경로가 조용히 어긋난다. 그런데 이 조립 로직을
 * 검증하는 테스트가 없었다 — 이 테스트가 그 안전망이다.
 *
 * <p>TASK-PRESIGN-BOTTLENECK-FIX.md 1단계에서 비공개 key를 {@code private/{courseId}/...}로
 * 바꾸기 직전에, 현행 동작({@code {prefix}/{yyyy-MM-dd}/{uuid}.{ext}})을 먼저 고정해둔다.
 * 이후 커밋에서 이 기대값이 바뀌는 diff가 곧 "무엇이 어떻게 바뀌었는지"의 기록이 된다.
 */
@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    private static final String BUCKET = "yourtrip-media-test";
    private static final String REGION = "ap-northeast-2";
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final String ALLOWED_CONTENT_TYPES =
        "image/png,image/jpeg,image/webp,image/jpg,video/mp4,video/quicktime,video/webm";

    // {prefix}/{yyyy-MM-dd}/{uuid}.{ext} — 날짜 세그먼트와 UUID를 정규식으로 확인한다.
    // 실제 날짜값을 박아 비교하면 자정 경계에서 깨질 수 있어 형식만 본다.
    private static final String DATE_SEGMENT = "\\d{4}-\\d{2}-\\d{2}";
    private static final String UUID_SEGMENT = "[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}";

    @Mock
    private S3Client s3Client;
    @Mock
    private CloudFrontService cloudFrontService;

    @Captor
    private ArgumentCaptor<PutObjectRequest> putRequestCaptor;
    @Captor
    private ArgumentCaptor<CopyObjectRequest> copyRequestCaptor;

    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Service = new S3Service(s3Client, cloudFrontService);
        ReflectionTestUtils.setField(s3Service, "bucket", BUCKET);
        ReflectionTestUtils.setField(s3Service, "region", REGION);
        ReflectionTestUtils.setField(s3Service, "maxSizeBytes", MAX_SIZE_BYTES);
        ReflectionTestUtils.setField(s3Service, "allowedContentTypes", ALLOWED_CONTENT_TYPES);
    }

    @Nested
    @DisplayName("업로드 시 key 조립")
    class Upload {

        @Test
        @DisplayName("공개 업로드는 uploads/{날짜}/{uuid}.{확장자} key와 6개월 Cache-Control을 쓴다")
        void uploadFile_UsesPublicPrefixAndLongCacheControl() throws IOException {
            s3Service.uploadFile(imageFile("image/jpeg"));

            PutObjectRequest request = capturePut();
            assertThat(request.key()).matches("uploads/" + DATE_SEGMENT + "/" + UUID_SEGMENT + "\\.jpg");
            assertThat(request.bucket()).isEqualTo(BUCKET);
            assertThat(request.contentType()).isEqualTo("image/jpeg");
            assertThat(request.cacheControl()).isEqualTo("public, max-age=15552000, immutable");
        }

        @Test
        @DisplayName("비공개 업로드는 private/{날짜}/{uuid}.{확장자} key와 1주일 Cache-Control을 쓴다")
        void uploadPrivateFile_UsesPrivatePrefixAndShorterCacheControl() throws IOException {
            s3Service.uploadPrivateFile(imageFile("image/png"));

            PutObjectRequest request = capturePut();
            assertThat(request.key()).matches("private/" + DATE_SEGMENT + "/" + UUID_SEGMENT + "\\.png");
            assertThat(request.contentType()).isEqualTo("image/png");
            assertThat(request.cacheControl()).isEqualTo("public, max-age=604800, immutable");
        }

        @Test
        @DisplayName("반환된 UploadResult의 key가 실제 S3에 올린 key와 같다")
        void uploadFile_ReturnsSameKeyAsUploaded() throws IOException {
            S3Service.UploadResult result = s3Service.uploadFile(imageFile("image/webp"));

            assertThat(result.key()).isEqualTo(capturePut().key());
            assertThat(result.contentType()).isEqualTo("image/webp");
        }

        @Test
        @DisplayName("같은 파일을 두 번 올려도 key가 겹치지 않는다")
        void upload_GeneratesDistinctKeys() throws IOException {
            s3Service.uploadPrivateFile(imageFile("image/jpeg"));
            s3Service.uploadPrivateFile(imageFile("image/jpeg"));

            verify(s3Client, org.mockito.Mockito.times(2))
                .putObject(putRequestCaptor.capture(), any(RequestBody.class));
            assertThat(putRequestCaptor.getAllValues().get(0).key())
                .isNotEqualTo(putRequestCaptor.getAllValues().get(1).key());
        }
    }

    @Nested
    @DisplayName("복사 시 key 조립")
    class Copy {

        @Test
        @DisplayName("비공개로 복사하면 private prefix가 붙고 확장자와 Content-Type이 복원된다")
        void copyToPrivate_UsesPrivatePrefixAndRestoresContentType() {
            String targetKey = s3Service.copyToPrivate("uploads/2026-01-01/source.png");

            CopyObjectRequest request = captureCopy();
            assertThat(request.destinationKey())
                .matches("private/" + DATE_SEGMENT + "/" + UUID_SEGMENT + "\\.png")
                .isEqualTo(targetKey);
            assertThat(request.sourceKey()).isEqualTo("uploads/2026-01-01/source.png");
            assertThat(request.contentType()).isEqualTo("image/png");
            assertThat(request.cacheControl()).isEqualTo("public, max-age=604800, immutable");
            // 원본의 Cache-Control은 이전 가시성 기준이라 그대로 복사하면 안 된다
            assertThat(request.metadataDirective()).isEqualTo(MetadataDirective.REPLACE);
        }

        @Test
        @DisplayName("공개로 복사하면 uploads prefix와 6개월 Cache-Control이 붙는다")
        void copyToPublic_UsesPublicPrefix() {
            s3Service.copyToPublic("private/2026-01-01/source.jpg");

            CopyObjectRequest request = captureCopy();
            assertThat(request.destinationKey())
                .matches("uploads/" + DATE_SEGMENT + "/" + UUID_SEGMENT + "\\.jpg");
            assertThat(request.contentType()).isEqualTo("image/jpeg");
            assertThat(request.cacheControl()).isEqualTo("public, max-age=15552000, immutable");
        }

        @Test
        @DisplayName("확장자가 없는 원본은 .bin과 application/octet-stream으로 떨어진다")
        void copy_WithoutExtension_FallsBackToBinary() {
            s3Service.copyToPrivate("uploads/2026-01-01/no-extension");

            CopyObjectRequest request = captureCopy();
            assertThat(request.destinationKey()).endsWith(".bin");
            assertThat(request.contentType()).isEqualTo("application/octet-stream");
        }
    }

    @Nested
    @DisplayName("업로드 전 검증")
    class Validation {

        @Test
        @DisplayName("빈 파일은 EMPTY_FILE로 거부한다")
        void upload_EmptyFile_Throws() {
            MultipartFile empty = new MockMultipartFile("file", "empty.jpg", "image/jpeg", new byte[0]);

            assertThatThrownBy(() -> s3Service.uploadPrivateFile(empty))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.EMPTY_FILE);
        }

        @Test
        @DisplayName("허용 용량을 넘으면 OVER_SIZE_FILE로 거부한다")
        void upload_OverSizedFile_Throws() {
            MultipartFile tooLarge = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                new byte[(int) MAX_SIZE_BYTES + 1]);

            assertThatThrownBy(() -> s3Service.uploadPrivateFile(tooLarge))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.OVER_SIZE_FILE);
        }

        @Test
        @DisplayName("화이트리스트에 없는 Content-Type은 NOT_ALLOW_FILE_TYPE으로 거부한다")
        void upload_DisallowedContentType_Throws() {
            MultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "pdf".getBytes());

            assertThatThrownBy(() -> s3Service.uploadPrivateFile(pdf))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.NOT_ALLOW_FILE_TYPE);
        }
    }

    @Test
    @DisplayName("파일 삭제는 S3 삭제 후 CloudFront invalidation까지 이어진다")
    void deleteFile_InvalidatesCdnCache() {
        s3Service.deleteFile("private/2026-01-01/target.jpg");

        verify(cloudFrontService).invalidate("private/2026-01-01/target.jpg");
    }

    private MultipartFile imageFile(String contentType) {
        return new MockMultipartFile("file", "photo", contentType, "image-bytes".getBytes());
    }

    private PutObjectRequest capturePut() {
        verify(s3Client).putObject(putRequestCaptor.capture(), any(RequestBody.class));
        return putRequestCaptor.getValue();
    }

    private CopyObjectRequest captureCopy() {
        verify(s3Client).copyObject(copyRequestCaptor.capture());
        return copyRequestCaptor.getValue();
    }
}
