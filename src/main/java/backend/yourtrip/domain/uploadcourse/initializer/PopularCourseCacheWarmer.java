package backend.yourtrip.domain.uploadcourse.initializer;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.service.UploadCourseService;
import backend.yourtrip.global.config.BenchmarkProperties;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 서버 기동 직후(ApplicationReadyEvent) 인기 목록 캐시(전체 + mood 테마 7종, 총 8개 키)를 미리 채운다.
 * 콜드 스타트 분산 락(UploadCourseServiceImpl의 랭킹 캐시 락)을 대체하지 않고 보강하는 용도다 —
 * Redis만 단독으로 재시작되는 경우엔 앱이 재기동되지 않아 이 웜업도 재실행되지 않으므로,
 * 그 경우엔 여전히 분산 락이 유일한 방어선이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PopularCourseCacheWarmer {

    private final UploadCourseService uploadCourseService;
    private final BenchmarkProperties benchmarkProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpPopularCoursesCache() {
        if (benchmarkProperties.isCacheDisabled()) {
            // 채울 캐시가 없다. 그냥 두면 기동 직후 랭킹 쿼리 8회가 무의미하게 실행된다.
            log.info("캐시가 비활성화되어 인기 목록 웜업을 건너뜁니다.");
            return;
        }

        List<KeywordType> themes = new ArrayList<>();
        themes.add(null);
        themes.addAll(KeywordType.findByCategory("mood"));

        int successCount = 0;
        for (KeywordType theme : themes) {
            try {
                // 기존 getPopularCourses를 그대로 호출해 랭킹 캐시와 아이템 캐시를 함께 채운다.
                // 부팅 시점엔 동시 요청이 없어 분산 락 경로를 타도 항상 즉시 락을 획득한다.
                uploadCourseService.getPopularCourses(theme);
                successCount++;
            } catch (Exception e) {
                // 웜업은 순수 최적화이므로 한 테마가 실패해도 앱 부팅이나 나머지 테마 웜업에 영향을 주지 않는다.
                log.warn("인기 목록 캐시 웜업 실패. theme={}", theme, e);
            }
        }
        log.info("인기 목록 캐시 웜업 완료: {}/{}건 성공", successCount, themes.size());
    }
}
