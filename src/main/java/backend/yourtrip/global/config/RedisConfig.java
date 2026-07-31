package backend.yourtrip.global.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 아직 어떤 캐시도 사용하지 않는 인프라 설정 단계다.
 * 캐시 이름/TTL 정의만 두고, 실제 조회 경로 연결(@Cacheable 등)은 이후 섹션에서 진행한다.
 */
@Configuration
@EnableCaching
@RequiredArgsConstructor
public class RedisConfig implements CachingConfigurer {

    // 인기 목록 안전망 TTL — 조회수 동기화 스케줄러(10분 주기)가 멈췄을 때를 대비한 마지노선.
    private static final Duration POPULAR_COURSES_TTL = Duration.ofMinutes(30);
    // 상세 캐시 기본 TTL — 실제 적용 시점에는 동시 만료를 피하기 위해 ± jitter가 더해진다.
    private static final Duration COURSE_DETAIL_TTL = Duration.ofMinutes(5);

    private final RedisCacheErrorHandler redisCacheErrorHandler;

    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(new StringRedisSerializer());
        redisTemplate.setHashKeySerializer(new StringRedisSerializer());
        redisTemplate.setHashValueSerializer(new StringRedisSerializer());
        return redisTemplate;
    }

    @Bean
    public RedisCacheManager redisCacheManager(RedisConnectionFactory connectionFactory) {
        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(cacheValueSerializer()));

        Map<String, RedisCacheConfiguration> cacheConfigurations = Map.of(
                "popularCourses", defaultConfig.entryTtl(POPULAR_COURSES_TTL),
                "courseDetail", defaultConfig.entryTtl(COURSE_DETAIL_TTL)
        );

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    private GenericJackson2JsonRedisSerializer cacheValueSerializer() {
        ObjectMapper objectMapper = new ObjectMapper();
        // LocalDateTime 등 java.time 타입을 캐시 DTO에 담기 위해 명시적으로 등록한다.
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return redisCacheErrorHandler;
    }
}
