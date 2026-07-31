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
    // 코스 콘텐츠(엔티티 ID 단위) 캐시 TTL — 거의 안 바뀌는 read-heavy 데이터라 넉넉하게 잡는다.
    // 정합성은 이 TTL이 아니라 콘텐츠 변경 이벤트 발생 시의 write-through(cache.put)로 보장한다.
    // public: 아이템 캐시를 파이프라인으로 직접 쓰는 UploadCourseServiceImpl에서도 동일 TTL을 적용해야 해서 공유한다.
    public static final Duration COURSE_LIST_ITEM_TTL = Duration.ofHours(2);

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

    /**
     * 캐시 값(JSON) 배치 조회(MGET) 전용 템플릿. RedisCacheManager와 동일한 값 직렬화(GenericJackson2Json)를 써서
     * 캐시에 저장된 값을 그대로 읽어올 수 있다. Spring Cache 추상화(Cache 인터페이스)는 다중 키 조회를 지원하지 않아
     * 코스 여러 건을 한 번에 조회해야 하는 아이템 캐시 배치 읽기에 별도로 사용한다.
     */
    @Bean
    public RedisTemplate<String, Object> cacheValueRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> redisTemplate = new RedisTemplate<>();
        redisTemplate.setConnectionFactory(connectionFactory);
        redisTemplate.setKeySerializer(new StringRedisSerializer());
        redisTemplate.setValueSerializer(cacheValueSerializer());
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
                "courseDetail", defaultConfig.entryTtl(COURSE_DETAIL_TTL),
                "courseListItem", defaultConfig.entryTtl(COURSE_LIST_ITEM_TTL)
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
