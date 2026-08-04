package backend.yourtrip.domain.uploadcourse.scheduler;

import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseViewCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountSyncScheduler {

    private final RedisTemplate<String, String> redisTemplate;
    private final UploadCourseRepository uploadCourseRepository;
    private final UploadCourseService uploadCourseService;

    @Scheduled(cron = "0 0/10 * * * *") // 매 10분마다 실행
    @Transactional
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

            for (String courseIdStr : dirtyCourseIds) {
                String counterKey = UploadCourseViewCountService.VIEW_COUNT_INCREMENT_KEY_PREFIX + courseIdStr;
                String incrementStr = redisTemplate.opsForValue().getAndDelete(counterKey);

                if (incrementStr != null) {
                    long increment = Long.parseLong(incrementStr);
                    if (increment > 0) {
                        Long uploadCourseId = Long.parseLong(courseIdStr);
                        uploadCourseRepository.incrementViewCount(uploadCourseId, increment);
                    }
                }
            }

            // DB 업데이트 후 스냅샷 삭제
            redisTemplate.delete(snapshotKey);

            // DB에 인기 코스 순위 변동이 반영되었으므로, 캐시를 갱신한다 (Refresh-Ahead)
            uploadCourseService.refreshAllPopularCoursesCache();

            log.info("조회수 동기화 및 랭킹 갱신 완료. 갱신된 코스 수: {}", dirtyCourseIds.size());
        } catch (Exception e) {
            log.error("조회수 동기화 스케줄러 실행 중 오류 발생", e);
        }
    }
}
