package backend.yourtrip.global.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisDistributedLock 단위 테스트")
class RedisDistributedLockTest {

    private static final String LOCK_KEY = "lock:test";
    private static final String TOKEN = "token-1";
    private static final Duration TTL = Duration.ofSeconds(5);

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private RedisDistributedLock redisDistributedLock;

    @Test
    @DisplayName("락이 비어 있으면 SET NX가 성공해 TRUE를 반환한다")
    void tryAcquire_LockFree_ReturnsTrue() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(LOCK_KEY, TOKEN, TTL)).willReturn(true);

        // when
        Boolean acquired = redisDistributedLock.tryAcquire(LOCK_KEY, TOKEN, TTL);

        // then
        assertThat(acquired).isTrue();
    }

    @Test
    @DisplayName("다른 소유자가 락을 보유 중이면 FALSE를 반환한다")
    void tryAcquire_AlreadyHeld_ReturnsFalse() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(LOCK_KEY, TOKEN, TTL)).willReturn(false);

        // when
        Boolean acquired = redisDistributedLock.tryAcquire(LOCK_KEY, TOKEN, TTL);

        // then
        assertThat(acquired).isFalse();
    }

    @Test
    @DisplayName("Redis 예외가 나면 예외를 전파하지 않고 null을 반환해 정책 판단을 호출측에 넘긴다")
    void tryAcquire_RedisException_ReturnsNull() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        willThrow(new RuntimeException("redis down"))
            .given(valueOperations).setIfAbsent(LOCK_KEY, TOKEN, TTL);

        // when
        Boolean acquired = redisDistributedLock.tryAcquire(LOCK_KEY, TOKEN, TTL);

        // then — FALSE(남이 보유 중)와 null(알 수 없음)은 의미가 다르다. 이 구분이 있어야
        // 호출측이 fail-open(랭킹)과 fail-closed(스케줄러)를 각각 선택할 수 있다.
        assertThat(acquired).isNull();
    }

    @Test
    @DisplayName("락 해제는 토큰을 인자로 넘겨 compare-and-delete 스크립트로 수행한다")
    void release_Always_ExecutesCompareAndDeleteScript() {
        // when
        redisDistributedLock.release(LOCK_KEY, TOKEN);

        // then — 단순 DEL이 아니라 토큰을 비교하는 스크립트여야, TTL 만료 후 남이 잡은 락을
        // 실수로 지우지 않는다
        verify(redisTemplate).execute(ArgumentMatchers.<RedisScript<Long>>any(),
            eq(List.of(LOCK_KEY)), eq(TOKEN));
    }

    @Test
    @DisplayName("락 해제가 Redis 예외로 실패해도 예외를 삼킨다(TTL로 자동 만료되므로)")
    void release_RedisException_SwallowsException() {
        // given
        willThrow(new RuntimeException("redis down")).given(redisTemplate)
            .execute(ArgumentMatchers.<RedisScript<Long>>any(), any(), any());

        // when & then — 여기서 예외가 새면 finally 블록에서 터져 임계 구역의 정상 결과까지 뒤엎는다
        assertThatCode(() -> redisDistributedLock.release(LOCK_KEY, TOKEN))
            .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("락 획득 실패는 예외가 아니라 반환값으로만 알리므로 해제 스크립트를 실행하지 않는다")
    void tryAcquire_DoesNotTouchUnlockScript() {
        // given
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
            .willReturn(false);

        // when
        redisDistributedLock.tryAcquire(LOCK_KEY, TOKEN, TTL);

        // then
        verify(redisTemplate, org.mockito.Mockito.never())
            .execute(ArgumentMatchers.<RedisScript<Long>>any(), any(), any());
    }
}
