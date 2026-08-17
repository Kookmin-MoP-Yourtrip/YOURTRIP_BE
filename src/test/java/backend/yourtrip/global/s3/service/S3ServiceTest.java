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
 * <p>PRESIGN-BOTTLENECK-FIX.md 1단계로 <b>비공개 key만</b> 코스 단위
 * ({@code private/{courseId}/{uuid}.{ext}})로 바뀌었다. 공개 key는 서명 대상이 아니라
 * 이 변경과 무관하므로 날짜 기반({@code uploads/{yyyy-MM-dd}/{uuid}.{ext}})을 유지한다.
 */
@ExtendWith(MockitoExtension.class)
class S3ServiceTest {

    private static final String BUCKET = "yourtrip-media-test";
    private static final String REGION = "ap-northeast-2";
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;
    private static final String ALLOWED_CONTENT_TYPES =
        "image/png,image/jpeg,image/webp,image/jpg,video/mp4,video/quicktime,video/webm";

    private static final Long COURSE_ID = 42L;

    // 공개: uploads/{yyyy-MM-dd}/{uuid}.{ext} — 실제 날짜값을 박아 비교하면 자정 경계에서
    // 깨질 수 있어 형식만 본다. 비공개: private/{courseId}/{uuid}.{ext}.
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
        @DisplayName("비공개 업로드는 private/{courseId}/{uuid}.{확장자} key와 1주일 Cache-Control을 쓴다")
        void uploadPrivateFile_UsesCourseScopedPrefixAndShorterCacheControl() throws IOException {
            s3Service.uploadPrivateFile(imageFile("image/png"), COURSE_ID);

            PutObjectRequest request = capturePut();
            // 코스 단위 와일드카드 서명(private/42/*)의 스코프와 정확히 맞아야 한다
            assertThat(request.key()).matches("private/42/" + UUID_SEGMENT + "\\.png");
            assertThat(request.contentType()).isEqualTo("image/png");
            assertThat(request.cacheControl()).isEqualTo("public, max-age=604800, immutable");
        }

        @Test
        @DisplayName("서로 다른 코스의 이미지는 서로 다른 prefix로 분리된다")
        void uploadPrivateFile_SeparatesKeysByCourse() throws IOException {
            s3Service.uploadPrivateFile(imageFile("image/jpeg"), 1L);
            s3Service.uploadPrivateFile(imageFile("image/jpeg"), 2L);

            verify(s3Client, org.mockito.Mockito.times(2))
                .putObject(putRequestCaptor.capture(), any(RequestBody.class));
            assertThat(putRequestCaptor.getAllValues().get(0).key()).startsWith("private/1/");
            assertThat(putRequestCaptor.getAllValues().get(1).key()).startsWith("private/2/");
        }

        @Test
        @DisplayName("courseId가 없으면 key를 만들지 않고 즉시 실패한다")
        void uploadPrivateFile_WithoutCourseId_ThrowsBeforeUpload() {
            // private/null/... 같은 key가 저장되면 어떤 코스 스코프 서명으로도 접근할 수 없어
            // 영구 403이 된다. 저장 전에 터뜨려야 한다.
            assertThatThrownBy(() -> s3Service.uploadPrivateFile(imageFile("image/jpeg"), null))
                .isInstanceOf(IllegalArgumentException.class);

            org.mockito.Mockito.verifyNoInteractions(s3Client);
        }

        @Test
        @DisplayName("반환된 UploadResult의 key가 실제 S3에 올린 key와 같다")
        void uploadFile_ReturnsSameKeyAsUploaded() throws IOException {
            S3Service.UploadResult result = s3Service.uploadFile(imageFile("image/webp"));

            assertThat(result.key()).isEqualTo(capturePut().key());
            assertThat(result.contentType()).isEqualTo("image/webp");
        }

        @Test
        @DisplayName("같은 코스에 같은 파일을 두 번 올려도 key가 겹치지 않는다")
        void upload_GeneratesDistinctKeys() throws IOException {
            s3Service.uploadPrivateFile(imageFile("image/jpeg"), COURSE_ID);
            s3Service.uploadPrivateFile(imageFile("image/jpeg"), COURSE_ID);

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
        @DisplayName("비공개로 복사하면 사본 코스의 prefix가 붙고 확장자와 Content-Type이 복원된다")
        void copyToPrivate_UsesTargetCoursePrefixAndRestoresContentType() {
            String targetKey = s3Service.copyToPrivate("uploads/2026-01-01/source.png", COURSE_ID);

            CopyObjectRequest request = captureCopy();
            // fork 사본은 원본 코스가 아니라 사본 코스의 서명 스코프에 들어가야 한다
            assertThat(request.destinationKey())
                .matches("private/42/" + UUID_SEGMENT + "\\.png")
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
            s3Service.copyToPrivate("uploads/2026-01-01/no-extension", COURSE_ID);

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

            assertThatThrownBy(() -> s3Service.uploadPrivateFile(empty, COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.EMPTY_FILE);
        }

        @Test
        @DisplayName("허용 용량을 넘으면 OVER_SIZE_FILE로 거부한다")
        void upload_OverSizedFile_Throws() {
            MultipartFile tooLarge = new MockMultipartFile("file", "big.jpg", "image/jpeg",
                new byte[(int) MAX_SIZE_BYTES + 1]);

            assertThatThrownBy(() -> s3Service.uploadPrivateFile(tooLarge, COURSE_ID))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", S3ErrorCode.OVER_SIZE_FILE);
        }

        @Test
        @DisplayName("화이트리스트에 없는 Content-Type은 NOT_ALLOW_FILE_TYPE으로 거부한다")
        void upload_DisallowedContentType_Throws() {
            MultipartFile pdf = new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "pdf".getBytes());

            assertThatThrownBy(() -> s3Service.uploadPrivateFile(pdf, COURSE_ID))
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
