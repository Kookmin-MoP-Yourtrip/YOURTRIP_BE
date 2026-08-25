package backend.yourtrip.domain.uploadcourse.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseViewCountService;
import backend.yourtrip.global.redis.RedisDistributedLock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;

/**
 * 이 스케줄러의 정합성은 "한 번에 한 인스턴스만 RENAME~DECRBY 구간에 들어간다"에 전적으로 달려
 * 있다. RENAME은 대상 명단만 격리할 뿐 카운터 값은 격리하지 못하므로, 두 인스턴스가 겹쳐 들어오면
 * 같은 증분이 DB에 두 번 반영되고 Redis 카운터는 음수로 고착된다.
 * <p>
 * 그래서 아래 테스트들의 초점은 "동기화 로직이 맞는가"보다 <b>"락을 못 잡았을 때 임계 구역에 단
 * 한 발자국도 들이지 않는가"</b>에 있다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ViewCountSyncScheduler 단위 테스트")
class ViewCountSyncSchedulerTest {

    private static final String DIRTY_SET_KEY = UploadCourseViewCountService.VIEW_COUNT_DIRTY_SET_KEY;
    private static final String SYNC_LOCK_KEY = "lock:viewCountSync";

    @Mock
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private SetOperations<String, String> setOperations;

    @Mock
    private UploadCourseViewCountService uploadCourseViewCountService;

    @Mock
    private UploadCourseService uploadCourseService;

    @Mock
    private RedisDistributedLock redisDistributedLock;

    @InjectMocks
    private ViewCountSyncScheduler viewCountSyncScheduler;

    // ====== 동시 실행 보호 ======

    @Test
    @DisplayName("다른 인스턴스가 락을 보유 중이면 RENAME조차 하지 않고 이번 주기를 건너뛴다")
    void syncViewCountsToDb_LockHeldByAnotherInstance_DoesNotEnterCriticalSection() {
        // given — 다중 인스턴스에서 늦게 도착한 쪽의 상황
        givenLockAcquisitionReturns(false);

        // when
        viewCountSyncScheduler.syncViewCountsToDb();

        // then — RENAME을 못 하는 것이 이 수정의 핵심이다. 락이 없던 시절에는 앞선 인스턴스가
        // RENAME한 직후 재생성된 dirty set을 이쪽이 곧바로 스냅샷해, 같은 코스의 카운터를 양쪽이
        // 읽고 각자 DB에 반영하는 중복 창이 열렸다.
        verify(redisTemplate, never()).rename(anyString(), anyString());
        verify(redisTemplate, never()).hasKey(anyString());
        verifyNoInteractions(uploadCourseViewCountService, uploadCourseService);
    }

    @Test
    @DisplayName("락을 잡지 못했으면 남의 락을 건드리지 않도록 해제도 시도하지 않는다")
    void syncViewCountsToDb_LockNotAcquired_DoesNotRelease() {
        // given
        givenLockAcquisitionReturns(false);

        // when
        viewCountSyncScheduler.syncViewCountsToDb();

        // then
        verify(redisDistributedLock, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 오류로 락 획득 여부를 알 수 없으면 진행하지 않고 건너뛴다(fail-closed)")
    void syncViewCountsToDb_LockAcquisitionFailsWithRedisError_SkipsCycle() {
        // given — RedisDistributedLock이 예외를 삼키고 null을 돌려준 상황
        givenLockAcquisitionReturns(null);

        // when
        viewCountSyncScheduler.syncViewCountsToDb();

        // then — 조회 경로의 fail-open과 정반대다. 락 없이 진행하면 막으려던 중복 반영이 그대로
        // 재현되고, 어차피 이후 단계가 전부 Redis에 의존한다. 증분은 Redis에 남아 다음 주기에
        // 재시도되므로 건너뛰는 대가가 "지연"에 그친다.
        verify(redisTemplate, never()).rename(anyString(), anyString());
        verifyNoInteractions(uploadCourseViewCountService, uploadCourseService);
        verify(redisDistributedLock, never()).release(anyString(), anyString());
    }

    @Test
    @DisplayName("락은 동기화 전용 키와 5분 TTL로 획득하고, 해제할 때 획득 시점과 같은 토큰을 넘긴다")
    void syncViewCountsToDb_LockAcquired_UsesSameTokenForAcquireAndRelease() {
        // given
        givenLockAcquisitionReturns(true);
        given(redisTemplate.hasKey(DIRTY_SET_KEY)).willReturn(false);

        // when
        viewCountSyncScheduler.syncViewCountsToDb();

        // then — TTL은 임계 구역의 실제 소요(실측 수 초)보다 충분히 길고 크론 주기(10분)보다
        // 짧아야 한다. 값이 바뀌면 이 테스트가 먼저 깨져 근거를 다시 따지게 만든다.
        ArgumentCaptor<String> acquireToken = ArgumentCaptor.forClass(String.class);
        verify(redisDistributedLock).tryAcquire(eq(SYNC_LOCK_KEY), acquireToken.capture(),
            eq(Duration.ofMinutes(5)));

        ArgumentCaptor<String> releaseToken = ArgumentCaptor.forClass(String.class);
        verify(redisDistributedLock).release(eq(SYNC_LOCK_KEY), releaseToken.capture());

        // 토큰이 다르면 compare-and-delete가 아무것도 지우지 않아 락이 TTL까지 남는다
        assertThat(releaseToken.getValue()).isEqualTo(acquireToken.getValue());
    }

    @Test
    @DisplayName("동기화 도중 오류가 나도 락은 반드시 해제한다")
    void syncViewCountsToDb_SyncFails_StillReleasesLock() {
        // given
        givenLockAcquisitionReturns(true);
        willThrow(new RuntimeException("redis down")).given(redisTemplate).hasKey(DIRTY_SET_KEY);

        // when
        viewCountSyncScheduler.syncViewCountsToDb();

        // then — 해제를 빠뜨리면 TTL(5분)이 만료될 때까지 다음 주기까지 통째로 막힌다
        verify(redisDistributedLock).release(eq(SYNC_LOCK_KEY), anyString());
    }

    // ====== 락 획득 후 정상 경로 ======

    @Test
    @DisplayName("락을 획득하면 스냅샷을 읽어 DB에 반영하고 커밋 후 Redis 증분을 차감한 뒤 락을 해제한다")
    void syncViewCountsToDb_LockAcquired_SyncsChunkAndReleasesLock() {
        // given
        givenLockAcquisitionReturns(true);
        given(redisTemplate.hasKey(DIRTY_SET_KEY)).willReturn(true);
        given(redisTemplate.opsForSet()).willReturn(setOperations);
        Set<String> dirtyIds = new LinkedHashSet<>(List.of("1", "2"));
        given(setOperations.members(anyString())).willReturn(dirtyIds);
        Map<Long, Long> increments = Map.of(1L, 10L, 2L, 20L);
        given(uploadCourseViewCountService.readPendingIncrements(List.of(1L, 2L)))
            .willReturn(increments);

        // when
        viewCountSyncScheduler.syncViewCountsToDb();

        // then
        verify(redisTemplate).rename(eq(DIRTY_SET_KEY), anyString());
        verify(uploadCourseService).applyViewCountIncrements(increments);
        verify(uploadCourseViewCountService).clearSyncedIncrements(increments);
        verify(uploadCourseService).refreshAllPopularCoursesCache();
        verify(redisDistributedLock).release(eq(SYNC_LOCK_KEY), anyString());
    }

    @Test
    @DisplayName("동기화 대상이 없어 조기 종료해도 락은 해제한다")
    void syncViewCountsToDb_NoDirtySet_ReleasesLock() {
        // given
        givenLockAcquisitionReturns(true);
        given(redisTemplate.hasKey(DIRTY_SET_KEY)).willReturn(false);

        // when
        viewCountSyncScheduler.syncViewCountsToDb();

        // then
        verify(redisTemplate, never()).rename(anyString(), anyString());
        verifyNoInteractions(uploadCourseViewCountService, uploadCourseService);
        verify(redisDistributedLock).release(eq(SYNC_LOCK_KEY), anyString());
    }

    private void givenLockAcquisitionReturns(Boolean acquired) {
        given(redisDistributedLock.tryAcquire(anyString(), anyString(), any(Duration.class)))
            .willReturn(acquired);
    }
}
