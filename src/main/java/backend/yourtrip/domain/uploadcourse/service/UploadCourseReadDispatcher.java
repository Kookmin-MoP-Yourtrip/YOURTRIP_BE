package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.config.BenchmarkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 인기 코스/상세 조회를 어느 트랜잭션 경계로 실행할지 고르는 분기점이다.
 * 벤치마크 관심사를 컨트롤러 밖으로 빼두려고 별도 빈으로 뒀다.
 *
 * <p><b>기본값({@code SEPARATED})에서는 그냥 위임만 한다</b> — 이 클래스가 없던 때와 동작이
 * 완전히 같다. 분기 하나뿐이라 히트 경로에 얹히는 비용도 무시할 수준이다.
 *
 * <p>{@code @Transactional}을 절대 붙이지 않는다. 여기에 붙으면 {@code SEPARATED} 경로까지
 * 트랜잭션 안으로 들어가 두 arm의 차이가 사라진다.
 */
@Service
@RequiredArgsConstructor
public class UploadCourseReadDispatcher {

    private final UploadCourseService uploadCourseService;
    private final TxWrappedUploadCourseReader txWrappedUploadCourseReader;
    private final BenchmarkProperties benchmarkProperties;

    public UploadCourseDetailResponse getDetail(Long uploadCourseId, String viewerKey) {
        if (benchmarkProperties.isTxWrapped()) {
            return txWrappedUploadCourseReader.getDetail(uploadCourseId, viewerKey);
        }
        return uploadCourseService.getDetail(uploadCourseId, viewerKey);
    }

    public UploadCourseListResponse getPopularCourses(KeywordType theme) {
        if (benchmarkProperties.isTxWrapped()) {
            return txWrappedUploadCourseReader.getPopularCourses(theme);
        }
        return uploadCourseService.getPopularCourses(theme);
    }
}
