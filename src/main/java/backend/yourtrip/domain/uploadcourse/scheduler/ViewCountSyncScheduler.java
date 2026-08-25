package backend.yourtrip.domain.uploadcourse.scheduler;

import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseViewCountService;
import backend.yourtrip.global.redis.RedisDistributedLock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
    // 자세한 트레이드오프 분석은 docs/tasks/redis-caching/task/view-counter.md 참고.
    private static final int SYNC_CHUNK_SIZE = 1000;

    private static final String SYNC_LOCK_KEY = "lock:viewCountSync";

    // 정상 종료 시엔 finally에서 즉시 해제하므로, 이 TTL은 "락을 쥔 인스턴스가 그대로 죽었을 때"만
    // 쓰이는 안전망이다. 값은 아래 두 방향의 하한/상한 사이에서 잡았다.
    //   - 하한(길어야 하는 이유): TTL이 실제 소요보다 짧으면 작업 도중 만료돼 다른 인스턴스가
    //     진입한다 — 락을 걸고도 중복 반영이 그대로 재현되므로 이쪽이 더 치명적이다. 실측
    //     기준(docs/tasks/redis-caching/task/view-counter.md)으로 코스 1만 건 동기화가 중앙값
    //     2,042ms이고, 여기에 refreshAllPopularCoursesCache()의 랭킹 쿼리 8회(ALL + mood 테마
    //     7종)가 더해져도 수 초 규모다. 5분이면 수십 배 여유가 있다.
    //   - 상한(짧아야 하는 이유): 크론 주기(10분)보다 짧아야 한다. 10분 이상이면 락 보유
    //     인스턴스가 죽었을 때 다음 주기까지 막혀 2주기 이상 밀린다. 5분이면 크래시 1회당 최대
    //     한 주기만 지연되고 자동 복구된다.
    private static final Duration SYNC_LOCK_TTL = Duration.ofMinutes(5);

    private final RedisTemplate<String, String> redisTemplate;
    private final UploadCourseViewCountService uploadCourseViewCountService;
    private final UploadCourseService uploadCourseService;
    private final RedisDistributedLock redisDistributedLock;

    /**
     * dirty set을 RENAME으로 스냅샷 격리하는 것만으로는 다중 인스턴스 동시 실행을 완전히 막지
     * 못한다. RENAME이 격리하는 건 "대상 명단"이지 "카운터 값"이 아니기 때문이다. 한 인스턴스가
     * RENAME한 직후 같은 코스에 조회가 1건 유입되면 dirty set이 되살아나고, 그걸 다른 인스턴스가
     * 곧바로 스냅샷하면 같은 코스 ID가 양쪽 명단에 들어간다. 그러면 둘이 같은 카운터 값을 읽어
     * 각자 DB에 더하고(과대 집계) 각자 DECRBY까지 해서 카운터가 음수로 고착된다 — 음수가 되면
     * readPendingIncrements()가 0 이하를 걸러내므로 이후 조회가 로그 없이 유실된다.
     * <p>
     * 그래서 RENAME부터 DECRBY까지의 구간 전체를 명시적 분산 락으로 감싼다.
     */
    @Scheduled(cron = "0 0/10 * * * *") // 매 10분마다 실행
    public void syncViewCountsToDb() {
        String lockToken = UUID.randomUUID().toString();
        Boolean locked = redisDistributedLock.tryAcquire(SYNC_LOCK_KEY, lockToken, SYNC_LOCK_TTL);

        if (locked == null) {
            // Redis 예외 — 락 없이 진행하면 위에서 막으려던 중복 반영이 그대로 재현되고, 어차피
            // 이후 모든 단계가 Redis에 의존해 실패한다. 배치라 미뤄도 되므로 이번 주기는 포기한다
            // (fail-closed). 조회 증분은 Redis에 그대로 남아 다음 주기에 재시도된다.
            log.warn("조회수 동기화 분산 락 획득 실패(Redis 오류), 이번 주기를 건너뜁니다.");
            return;
        }

        if (!locked) {
            // 다른 인스턴스가 이미 수행 중 — 기다릴 이유가 없다. 여기서 빠져야 그 인스턴스가
            // 방금 RENAME으로 가져간 명단을 이쪽이 다시 스냅샷하는 일이 원천 차단된다.
            log.debug("다른 인스턴스가 조회수 동기화 수행 중, 이번 주기를 건너뜁니다.");
            return;
        }

        try {
            doSyncViewCountsToDb();
        } finally {
            redisDistributedLock.release(SYNC_LOCK_KEY, lockToken);
        }
    }

    private void doSyncViewCountsToDb() {
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
