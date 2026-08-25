package backend.yourtrip.global.redis;

import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis {@code SET NX PX} 기반 분산 락의 원시 연산(획득/해제)만 제공한다.
 * <p>
 * 이 컴포넌트는 <b>정책을 담지 않는다</b>. 락 획득에 실패했을 때 기다릴지 건너뛸지, Redis 자체가
 * 죽었을 때 fail-open으로 그냥 진행할지 fail-closed로 포기할지는 호출측이 정한다. 실제로 현재 두
 * 사용처의 정책이 정반대다.
 * <ul>
 *   <li>인기 코스 랭킹 캐시({@code UploadCourseServiceImpl}) — 사용자에게 응답을 반드시 줘야 하는
 *       요청 경로라, 획득 실패 시 짧게 재시도하고 Redis 예외면 DB 직접 조회로 <b>fail-open</b>한다.</li>
 *   <li>조회수 동기화 스케줄러({@code ViewCountSyncScheduler}) — 미뤄도 되는 배치 경로라, 획득에
 *       실패하면 그 주기를 통째로 건너뛰고 다음 주기에 재시도한다(<b>fail-closed</b>).</li>
 * </ul>
 * 그래서 {@link #tryAcquire}는 성공/실패/Redis오류를 구분하는 3-state를 그대로 돌려주고, 판단은
 * 호출측에 맡긴다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDistributedLock {

    // 락 소유자만 자기 락을 지울 수 있도록 GET/DEL을 원자적으로 비교-삭제하는 스크립트.
    // 단순 DEL이면 TTL 만료 후 다른 요청이 이미 잡은 락을 실수로 지울 위험이 있다.
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of("""
        if redis.call('get', KEYS[1]) == ARGV[1] then
            return redis.call('del', KEYS[1])
        else
            return 0
        end
        """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;

    /**
     * 락 획득을 1회 시도한다(대기하지 않는다). 재시도가 필요하면 호출측이 직접 반복한다.
     *
     * @param token 소유권 증명용 고유값(보통 {@code UUID.randomUUID().toString()}). 해제할 때 같은
     *              값을 넘겨야 하며, 이 값이 있어야 TTL 만료 후 남이 잡은 락을 잘못 지우지 않는다.
     * @param ttl   락 보유자가 죽어도 락이 영원히 남지 않도록 하는 안전망. 임계 구역의 실제 소요
     *              시간보다 넉넉히 길어야 한다 — 작업 도중 만료되면 다른 인스턴스가 진입해 락이
     *              없는 것과 같아진다.
     * @return TRUE(획득 성공), FALSE(다른 소유자가 보유 중), {@code null}(Redis 예외 — 획득 여부를
     *         알 수 없으므로 어떻게 처리할지는 호출측이 결정한다)
     */
    public Boolean tryAcquire(String key, String token, Duration ttl) {
        try {
            return redisTemplate.opsForValue().setIfAbsent(key, token, ttl);
        } catch (Exception e) {
            log.warn("분산 락 획득 시도 실패. lockKey={}", key, e);
            return null;
        }
    }

    /**
     * 토큰이 일치할 때만 락을 해제한다. 다른 소유자의 락이면 아무 일도 하지 않는다.
     * <p>
     * 해제 실패는 삼킨다(fail-open) — TTL이 지나면 어차피 자동 만료되므로 호출측이 처리할 수 있는
     * 게 없고, 여기서 예외를 던지면 정작 임계 구역의 작업 결과까지 뒤엎게 된다.
     */
    public void release(String key, String token) {
        try {
            redisTemplate.execute(UNLOCK_SCRIPT, List.of(key), token);
        } catch (Exception e) {
            log.warn("분산 락 해제 실패(TTL로 자동 만료됨). lockKey={}", key, e);
        }
    }
}
