package backend.yourtrip.domain.uploadcourse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadCourseViewCountService {

    public static final String VIEW_COUNT_INCREMENT_KEY_PREFIX = "view_count:increment:";
    public static final String VIEW_COUNT_DIRTY_SET_KEY = "view_count_dirty";

    private final RedisTemplate<String, String> redisTemplate;

    public void incrementViewCount(Long uploadCourseId) {
        try {
            String counterKey = VIEW_COUNT_INCREMENT_KEY_PREFIX + uploadCourseId;
            redisTemplate.opsForValue().increment(counterKey);
            redisTemplate.opsForSet().add(VIEW_COUNT_DIRTY_SET_KEY, String.valueOf(uploadCourseId));
        } catch (Exception e) {
            // Redis 장애 격리 (fail-open)
            log.warn("Redis 조회수 카운터 증가 실패. uploadCourseId={}", uploadCourseId, e);
        }
    }
}
