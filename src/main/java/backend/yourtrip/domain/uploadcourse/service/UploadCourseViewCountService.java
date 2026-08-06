package backend.yourtrip.domain.uploadcourse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * 청크 단위로 조회수 증분을 파이프라인 GET으로 한 번에 읽어온다. GETDEL과 달리 값을 지우지 않으므로,
     * 이 시점에는 DB 반영이 아직 확정되지 않았어도 안전하다 — 삭제는 DB 커밋이 끝난 뒤
     * clearSyncedIncrements()가 별도로 담당한다.
     */
    public Map<Long, Long> readPendingIncrements(List<Long> courseIds) {
        if (courseIds.isEmpty()) {
            return Map.of();
        }

        List<Object> results = redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Long courseId : courseIds) {
                connection.stringCommands().get(counterKeyBytes(courseId));
            }
            return null;
        });

        Map<Long, Long> increments = new LinkedHashMap<>();
        for (int i = 0; i < courseIds.size(); i++) {
            String rawValue = (String) results.get(i);
            if (rawValue == null) {
                continue;
            }
            long increment = Long.parseLong(rawValue);
            if (increment > 0) {
                increments.put(courseIds.get(i), increment);
            }
        }
        return increments;
    }

    /**
     * DB 커밋이 끝난 뒤에만 호출해야 한다. 읽어온 값만큼만 DECRBY로 차감해, 그 사이 새로 유입된
     * 조회 증분(다음 주기 dirty set에는 잡히지만 아직 이 카운터 값에 합산된 부분)을 보존한다.
     * DEL을 쓰면 동시에 들어온 증분까지 통째로 지워져 조용히 유실되므로 반드시 DECRBY여야 한다.
     */
    public void clearSyncedIncrements(Map<Long, Long> increments) {
        if (increments.isEmpty()) {
            return;
        }

        redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            for (Map.Entry<Long, Long> entry : increments.entrySet()) {
                connection.stringCommands().decrBy(counterKeyBytes(entry.getKey()), entry.getValue());
            }
            return null;
        });
    }

    private byte[] counterKeyBytes(Long courseId) {
        return (VIEW_COUNT_INCREMENT_KEY_PREFIX + courseId).getBytes(StandardCharsets.UTF_8);
    }
}
