package backend.yourtrip.domain.uploadcourse.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class UploadCourseViewCountService {

    public static final String VIEW_COUNT_INCREMENT_KEY_PREFIX = "view_count:increment:";
    public static final String VIEW_COUNT_DIRTY_SET_KEY = "view_count_dirty";
    public static final String VIEW_DEDUP_KEY_PREFIX = "view_dedup:";

    private static final String LOGGED_IN_VIEWER_PREFIX = "u";
    private static final String ANONYMOUS_VIEWER_PREFIX = "a";
    private static final String UNKNOWN_PLACEHOLDER = "unknown";
    private static final String VIEW_DEDUP_MARKER = "1";
    private static final Duration VIEW_DEDUP_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 로그인 사용자는 userId, 비로그인 사용자는 IP+User-Agent 해시로 조회자를 식별한다.
     * 접두사(u/a)로 네임스페이스를 분리해, 우연히 같은 문자열이 되어도 로그인·비로그인 조회자가
     * 서로 다른 dedup 키를 갖도록 보장한다.
     */
    public String resolveViewerKey(Long userId, String clientIp, String userAgent) {
        if (userId != null) {
            return LOGGED_IN_VIEWER_PREFIX + userId;
        }
        return ANONYMOUS_VIEWER_PREFIX + hashAnonymousViewer(clientIp, userAgent);
    }

    private String hashAnonymousViewer(String clientIp, String userAgent) {
        String raw = (clientIp == null || clientIp.isBlank() ? UNKNOWN_PLACEHOLDER : clientIp)
            + "|" + (userAgent == null || userAgent.isBlank() ? UNKNOWN_PLACEHOLDER : userAgent);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // JDK 표준 알고리즘이라 실질적으로 발생하지 않는다.
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", e);
        }
    }

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
     * 같은 조회자(viewerKey)가 TTL(24시간) 내에 이미 조회했다면 조회수를 증가시키지 않는다.
     * dedup 체크 자체가 Redis 장애로 실패한 경우에는 fail-open으로 간주해 증가시킨다 —
     * incrementViewCount()의 fail-open 정책과 일관성을 맞춘 것이다.
     */
    public void incrementViewCountIfNotDuplicate(Long uploadCourseId, String viewerKey) {
        if (tryMarkViewed(uploadCourseId, viewerKey)) {
            incrementViewCount(uploadCourseId);
        }
    }

    private boolean tryMarkViewed(Long uploadCourseId, String viewerKey) {
        try {
            String dedupKey = VIEW_DEDUP_KEY_PREFIX + uploadCourseId + ":" + viewerKey;
            Boolean isFirstView = redisTemplate.opsForValue()
                .setIfAbsent(dedupKey, VIEW_DEDUP_MARKER, VIEW_DEDUP_TTL);
            return Boolean.TRUE.equals(isFirstView);
        } catch (Exception e) {
            // Redis 장애 격리 (fail-open) — dedup 체크가 실패해도 조회수 자체는 정상 반영되어야 한다.
            log.warn("Redis 조회수 중복 방지 체크 실패. uploadCourseId={}, viewerKey={}", uploadCourseId,
                viewerKey, e);
            return true;
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
