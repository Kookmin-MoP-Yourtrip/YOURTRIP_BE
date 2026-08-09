package backend.yourtrip.domain.uploadcourse.scheduler;

import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseViewCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountSyncScheduler {

    // Redis GET/DECRBY 파이프라인 1회에 묶는 코스 ID 수. 성능(왕복 절감) 목적과 함께, 크래시 시
    // 유실이 아니라 "지연"으로 그치는 상한을 이 값으로 제한하는 정합성 목적도 겸한다.
    // 자세한 트레이드오프 분석은 docs/tasks/TASK-5.md 참고.
    private static final int SYNC_CHUNK_SIZE = 1000;

    private final RedisTemplate<String, String> redisTemplate;
    private final UploadCourseViewCountService uploadCourseViewCountService;
    private final UploadCourseService uploadCourseService;

    @Scheduled(cron = "0 0/10 * * * *") // 매 10분마다 실행
    public void syncViewCountsToDb() {
        String snapshotKey = UploadCourseViewCountService.VIEW_COUNT_DIRTY_SET_KEY + "_snapshot_" + UUID.randomUUID();

        try {
            Boolean hasKey = redisTemplate.hasKey(UploadCourseViewCountService.VIEW_COUNT_DIRTY_SET_KEY);
            if (!Boolean.TRUE.equals(hasKey)) {
                return; // 변경된 코스가 없음
            }

            try {
                // RENAME을 통해 현재 모인 dirty 목록을 스냅샷으로 격리 (신규 유입과 분리)
                redisTemplate.rename(UploadCourseViewCountService.VIEW_COUNT_DIRTY_SET_KEY, snapshotKey);
            } catch (Exception e) {
                // RENAME 시점에 키가 없다면 무시
                return;
            }

            Set<String> dirtyCourseIds = redisTemplate.opsForSet().members(snapshotKey);
            if (dirtyCourseIds == null || dirtyCourseIds.isEmpty()) {
                redisTemplate.delete(snapshotKey);
                return;
            }

            List<Long> courseIds = dirtyCourseIds.stream().map(Long::parseLong).toList();
            int syncedCount = 0;
            for (List<Long> chunk : partition(courseIds, SYNC_CHUNK_SIZE)) {
                syncedCount += syncChunk(chunk);
            }

            // DB 업데이트 후 스냅샷 삭제
            redisTemplate.delete(snapshotKey);

            // DB에 인기 코스 순위 변동이 반영되었으므로, 캐시를 갱신한다 (Refresh-Ahead)
            uploadCourseService.refreshAllPopularCoursesCache();

            log.info("조회수 동기화 및 랭킹 갱신 완료. 대상 코스 수: {}, 반영된 코스 수: {}",
                dirtyCourseIds.size(), syncedCount);
        } catch (Exception e) {
            log.error("조회수 동기화 스케줄러 실행 중 오류 발생", e);
        }
    }

    /**
     * 청크 하나를 GET(파이프라인) → DB 커밋 → DECRBY(파이프라인) 순서로 처리한다.
     * 청크 단위로 예외를 격리해, 한 청크가 실패해도 나머지 청크는 계속 진행한다. 실패한 청크는
     * DECRBY를 호출하지 않으므로 그 증분은 Redis에 그대로 남아, 다음에 해당 코스가 다시 조회되면
     * 자연스럽게 재동기화된다(유실이 아니라 지연).
     */
    private int syncChunk(List<Long> chunk) {
        try {
            Map<Long, Long> increments = uploadCourseViewCountService.readPendingIncrements(chunk);
            if (increments.isEmpty()) {
                return 0;
            }

            // 이 호출이 반환되는 시점에 이 청크의 DB 트랜잭션이 독립적으로 커밋된다.
            uploadCourseService.applyViewCountIncrements(increments);

            // DB 커밋이 확정된 뒤에만 Redis 카운터를 차감한다.
            uploadCourseViewCountService.clearSyncedIncrements(increments);
            return increments.size();
        } catch (Exception e) {
            log.error("조회수 동기화 청크 처리 중 오류 발생. chunkSize={}", chunk.size(), e);
            return 0;
        }
    }

    private static List<List<Long>> partition(List<Long> ids, int chunkSize) {
        List<List<Long>> chunks = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += chunkSize) {
            chunks.add(ids.subList(i, Math.min(i + chunkSize, ids.size())));
        }
        return chunks;
    }
}
