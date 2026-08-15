package backend.yourtrip.global.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 업로드 코스 캐싱 효과 부하테스트의 arm을 전환하기 위한 <b>측정 전용</b> 스위치다.
 * <p>
 * 캐싱이 실제로 무엇을 해결했는지를 재려면 "캐싱이 없던 상태"와 "캐싱은 있지만 트랜잭션이
 * 안 좁혀진 상태"를 같은 환경에서 돌려봐야 하는데, 그 두 상태는 이미 지나간 커밋이다.
 * 커밋을 checkout해 비교하는 방식은 그 사이에 낀 CloudFront 전환·presign 개선 등이 함께
 * 딸려와 "바뀐 변수가 하나"를 지킬 수 없다. 그래서 <b>같은 JAR을 쓰고 이 프로퍼티만</b>
 * 바꿔 세 상태를 재현한다.
 *
 * <table>
 *   <tr><th>arm</th><th>uploadCourseCache</th><th>uploadCourseTx</th><th>의미</th></tr>
 *   <tr><td>A0</td><td>DISABLED</td><td>WRAPPED</td><td>캐싱 도입 이전</td></tr>
 *   <tr><td>A1</td><td>ENABLED</td><td>WRAPPED</td><td>캐싱 O, 트랜잭션 분리 이전</td></tr>
 *   <tr><td>A2</td><td>ENABLED</td><td>SEPARATED</td><td>현재 운영 (기본값)</td></tr>
 * </table>
 *
 * <p><b>기본값은 반드시 현재 운영 동작과 같아야 한다.</b> 프로퍼티를 아예 주지 않으면
 * ENABLED + SEPARATED로 동작하며, 이는 이 클래스가 없던 때와 완전히 동일하다.
 * 운영 서버에는 이 프로퍼티를 설정하지 않는다({@code application-prod.yml}에도 넣지 않는다).
 *
 * <p>값 이름에 {@code on}/{@code off}를 쓰지 않은 이유: YAML 1.1은 {@code on}/{@code off}/
 * {@code yes}/{@code no}를 불리언으로 파싱해서, yml에 적으면 enum 바인딩이 실패한다.
 * 환경변수로 줄 때는 문자열이라 문제가 없지만, 주는 경로에 따라 동작이 갈리는 설정은 두지 않는다.
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "yourtrip.benchmark")
public class BenchmarkProperties {

    /** 인기 코스/상세 조회의 Redis 캐시 사용 여부. */
    private UploadCourseCacheMode uploadCourseCache = UploadCourseCacheMode.ENABLED;

    /** 두 조회 API의 트랜잭션 경계. */
    private UploadCourseTxMode uploadCourseTx = UploadCourseTxMode.SEPARATED;

    /**
     * 캐시를 우회해야 하는가. 캐시 읽기는 항상 미스를 반환하고, 캐시 쓰기는 no-op이 된다.
     * 랭킹 분산 락도 함께 우회한다 — 캐시가 없으면 스탬피드 방지 락은 지킬 대상이 없는데,
     * 락 대기 {@code Thread.sleep}(최대 1초)이 톰캣 워커 스레드를 잡아 포화 VU 판정을 오염시킨다.
     */
    public boolean isCacheDisabled() {
        return uploadCourseCache == UploadCourseCacheMode.DISABLED;
    }

    /**
     * 조회 메서드 전체를 readOnly 트랜잭션으로 감싸야 하는가(= 트랜잭션 분리 이전 상태).
     * <p>
     * 감싸면 캐시가 100% 히트해 SQL이 0건이어도 커넥션은 요청마다 대여된다.
     * {@code provider_disables_autocommit}이 설정돼 있지 않아 트랜잭션 begin 시점에 물리 커넥션을
     * 잡아 autocommit을 끄고, {@code readOnly = true}면 {@code Connection.setReadOnly(true)}
     * 호출도 커넥션을 요구하기 때문이다. 이 측정이 재현하려는 것이 정확히 그 상태다.
     */
    public boolean isTxWrapped() {
        return uploadCourseTx == UploadCourseTxMode.WRAPPED;
    }

    /**
     * 측정용 설정이 실수로 남은 채 배포되는 것을 기동 로그에서 드러낸다.
     * 이 경고가 운영 로그에 보이면 그 서버는 캐시가 꺼져 있거나 커넥션을 낭비하고 있다는 뜻이다.
     */
    @PostConstruct
    void warnIfNotProductionDefaults() {
        if (isCacheDisabled() || isTxWrapped()) {
            log.warn("벤치마크 모드로 기동합니다 — 운영 설정이 아닙니다. "
                    + "uploadCourseCache={}, uploadCourseTx={}",
                uploadCourseCache, uploadCourseTx);
        }
    }

    public enum UploadCourseCacheMode {
        ENABLED, DISABLED
    }

    public enum UploadCourseTxMode {
        /** 현재 운영 상태. DB 접근만 Reader 빈의 짧은 트랜잭션에 있다. */
        SEPARATED,
        /** 트랜잭션 분리 이전 상태. 조회 메서드 전체가 하나의 readOnly 트랜잭션이다. */
        WRAPPED
    }
}
