package backend.yourtrip.global.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * Redis 장애/지연 시에도 요청을 실패시키지 않는 fail-open 정책.
 * 예외를 삼키고 WARN 로그만 남겨, 캐시 조회/저장 실패가 원본 DB 조회 흐름을 막지 않도록 한다.
 */
@Slf4j
@Component
public class RedisCacheErrorHandler implements CacheErrorHandler {

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Redis 캐시 조회 실패 - cache: {}, key: {}", cache.getName(), key, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, @Nullable Object value) {
        log.warn("Redis 캐시 저장 실패 - cache: {}, key: {}", cache.getName(), key, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        log.warn("Redis 캐시 삭제 실패 - cache: {}, key: {}", cache.getName(), key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("Redis 캐시 전체 삭제 실패 - cache: {}", cache.getName(), exception);
    }
}
