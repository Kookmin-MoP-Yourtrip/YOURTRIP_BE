package backend.yourtrip.domain.user.store;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis + TTL 기반 이메일 인증 저장소.
 * <p>
 * 장애 정책은 fail-closed다: Redis 접근 실패 시 예외를 삼키지 않고 503으로 요청을 거부한다.
 * 캐시 경로(RedisCacheErrorHandler)의 fail-open과 다른 이유 — 캐시는 DB라는 정확한 폴백이
 * 있어 성능 강등으로 끝나지만, 인증코드는 Redis가 유일한 원본이라 폴백할 곳이 없다.
 * 실패를 400(사용자 잘못)으로 위장하면 장애가 은폐되므로 명시적 503로 드러낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisEmailVerificationStore implements EmailVerificationStore {

    private static final String CODE_KEY_PREFIX = "email_verification:code:";
    private static final String VERIFIED_KEY_PREFIX = "email_verification:verified:";
    private static final String TEMP_PASSWORD_KEY_PREFIX = "email_verification:temp_password:";

    static final Duration CODE_TTL = Duration.ofMinutes(5);
    // 인증 완료 후 프로필 입력 등 남은 가입 단계를 마칠 때까지 허용하는 시간.
    // 기존 인메모리 구현은 무기한이었으나, 무기한 "인증됨" 상태는 누수이자 보안 결함이라 상한을 둔다.
    static final Duration VERIFIED_TTL = Duration.ofMinutes(30);

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public void saveCode(String email, String code) {
        execute(() -> {
            redisTemplate.opsForValue().set(CODE_KEY_PREFIX + email, code, CODE_TTL);
            return null;
        });
    }

    @Override
    public Optional<String> findCode(String email) {
        return execute(() ->
            Optional.ofNullable(redisTemplate.opsForValue().get(CODE_KEY_PREFIX + email)));
    }

    @Override
    public void markVerified(String email) {
        execute(() -> {
            redisTemplate.opsForValue().set(VERIFIED_KEY_PREFIX + email, "1", VERIFIED_TTL);
            return null;
        });
    }

    @Override
    public boolean isVerified(String email) {
        return execute(() ->
            redisTemplate.opsForValue().get(VERIFIED_KEY_PREFIX + email) != null);
    }

    @Override
    public void saveTempPassword(String email, String encodedPassword) {
        execute(() -> {
            redisTemplate.opsForValue()
                .set(TEMP_PASSWORD_KEY_PREFIX + email, encodedPassword, VERIFIED_TTL);
            return null;
        });
    }

    @Override
    public Optional<String> findTempPassword(String email) {
        return execute(() ->
            Optional.ofNullable(redisTemplate.opsForValue().get(TEMP_PASSWORD_KEY_PREFIX + email)));
    }

    @Override
    public void clear(String email) {
        execute(() -> {
            redisTemplate.delete(List.of(
                CODE_KEY_PREFIX + email,
                VERIFIED_KEY_PREFIX + email,
                TEMP_PASSWORD_KEY_PREFIX + email));
            return null;
        });
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (DataAccessException e) {
            log.error("이메일 인증 저장소(Redis) 접근 실패 - fail-closed로 요청을 거부한다", e);
            throw new BusinessException(UserErrorCode.VERIFICATION_SERVICE_UNAVAILABLE);
        }
    }
}
