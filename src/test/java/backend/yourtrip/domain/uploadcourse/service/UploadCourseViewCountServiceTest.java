package backend.yourtrip.domain.uploadcourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class UploadCourseViewCountServiceTest {

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private SetOperations<String, String> setOperations;

    @InjectMocks
    private UploadCourseViewCountService uploadCourseViewCountService;

    @Test
    @DisplayName("로그인 사용자는 userId 기반 viewerKey를 반환한다")
    void resolveViewerKey_LoggedInUser_ReturnsUserPrefixedKey() {
        String viewerKey = uploadCourseViewCountService.resolveViewerKey(42L, "1.2.3.4", "Mozilla/5.0");

        assertThat(viewerKey).isEqualTo("u42");
    }

    @Test
    @DisplayName("비로그인 사용자는 IP+User-Agent 해시 기반 viewerKey를 반환한다")
    void resolveViewerKey_AnonymousUser_ReturnsHashedKey() {
        String viewerKey = uploadCourseViewCountService.resolveViewerKey(null, "1.2.3.4", "Mozilla/5.0");

        assertThat(viewerKey).startsWith("a");
        assertThat(viewerKey).hasSize(1 + 64); // "a" + SHA-256 hex(64자)
    }

    @Test
    @DisplayName("동일한 IP+User-Agent는 항상 같은 viewerKey를 만든다")
    void resolveViewerKey_SameIpAndUserAgent_ReturnsSameHash() {
        String first = uploadCourseViewCountService.resolveViewerKey(null, "1.2.3.4", "Mozilla/5.0");
        String second = uploadCourseViewCountService.resolveViewerKey(null, "1.2.3.4", "Mozilla/5.0");

        assertThat(first).isEqualTo(second);
    }

    @Test
    @DisplayName("IP나 User-Agent가 다르면 다른 viewerKey를 만든다")
    void resolveViewerKey_DifferentIpOrUserAgent_ReturnsDifferentHash() {
        String first = uploadCourseViewCountService.resolveViewerKey(null, "1.2.3.4", "Mozilla/5.0");
        String second = uploadCourseViewCountService.resolveViewerKey(null, "5.6.7.8", "Mozilla/5.0");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("User-Agent가 없어도(null) 예외 없이 처리한다")
    void resolveViewerKey_NullUserAgent_DoesNotThrow() {
        String viewerKey = uploadCourseViewCountService.resolveViewerKey(null, "1.2.3.4", null);

        assertThat(viewerKey).startsWith("a");
    }

    @Test
    @DisplayName("로그인 사용자와 비로그인 사용자의 viewerKey는 접두사로 구분되어 충돌하지 않는다")
    void resolveViewerKey_LoggedInAndAnonymous_NeverCollide() {
        String loggedIn = uploadCourseViewCountService.resolveViewerKey(1L, "1.2.3.4", "Mozilla/5.0");
        String anonymous = uploadCourseViewCountService.resolveViewerKey(null, "1.2.3.4", "Mozilla/5.0");

        assertThat(loggedIn).isNotEqualTo(anonymous);
        assertThat(loggedIn).startsWith("u");
        assertThat(anonymous).startsWith("a");
    }

    @Test
    @DisplayName("TTL 내 최초 방문이면 조회수를 증가시킨다")
    void incrementViewCountIfNotDuplicate_FirstView_Increments() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        given(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(24))))
            .willReturn(true);

        uploadCourseViewCountService.incrementViewCountIfNotDuplicate(1L, "u42");

        verify(valueOperations).increment("view_count:increment:1");
        verify(setOperations).add("view_count_dirty", "1");
    }

    @Test
    @DisplayName("TTL 내 이미 조회한 viewerKey면 조회수를 증가시키지 않는다")
    void incrementViewCountIfNotDuplicate_DuplicateView_DoesNotIncrement() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(24))))
            .willReturn(false);

        uploadCourseViewCountService.incrementViewCountIfNotDuplicate(1L, "u42");

        verify(valueOperations, never()).increment(anyString());
    }

    @Test
    @DisplayName("dedup 체크 중 Redis 예외가 발생하면 fail-open으로 조회수를 증가시킨다")
    void incrementViewCountIfNotDuplicate_RedisException_FailsOpenAndIncrements() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        lenient().when(redisTemplate.opsForSet()).thenReturn(setOperations);
        willThrow(new RuntimeException("Redis 연결 실패"))
            .given(valueOperations).setIfAbsent(anyString(), eq("1"), eq(Duration.ofHours(24)));

        uploadCourseViewCountService.incrementViewCountIfNotDuplicate(1L, "u42");

        verify(valueOperations).increment("view_count:increment:1");
        verify(setOperations).add("view_count_dirty", "1");
    }
}
