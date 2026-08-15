package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 두 조회 API를 <b>트랜잭션 분리 이전 상태</b>로 되돌리는 측정 전용 래퍼다.
 * {@link backend.yourtrip.global.config.BenchmarkProperties.UploadCourseTxMode#WRAPPED}일 때만
 * {@link UploadCourseReadDispatcher}가 이 빈을 탄다.
 *
 * <p><b>왜 래퍼 빈이 필요한가</b>: {@code @Transactional}은 컴파일 타임 애노테이션이라 프로퍼티로
 * 껐다 켤 수 없고, 프록시 기반이라 같은 클래스 안에서의 self-invocation에도 걸리지 않는다.
 * 그래서 트랜잭션을 "여는" 역할만 하는 얇은 빈을 밖에 두고 분기는 디스패처가 한다.
 *
 * <p><b>왜 이것이 분리 이전과 동등한가</b>: 이 메서드가 트랜잭션을 열어둔 상태에서 안쪽
 * {@link UploadCourseDetailReader}/{@link UploadCoursePopularReader}의 {@code @Transactional}은
 * 기본 전파(REQUIRED)로 <b>기존 트랜잭션에 참여</b>한다. 결과적으로 메서드 전체가 하나의 readOnly
 * 트랜잭션이 되며, 이는 분리 이전 코드가 서비스 메서드에 직접 {@code @Transactional(readOnly = true)}를
 * 달았던 것과 같다. 캐시 조회(Redis)·락 대기 {@code Thread.sleep}·CloudFront URL 조립이 전부
 * 트랜잭션 안으로 들어오는 것까지 동일하다.
 *
 * <p>그 결과가 이 측정이 재현하려는 병목이다 — <b>캐시가 100% 히트해 SQL이 0건이어도 커넥션은
 * 요청마다 대여된다.</b> {@code provider_disables_autocommit}이 설정돼 있지 않아 트랜잭션 begin
 * 시점에 물리 커넥션을 잡아 autocommit을 끄고, {@code readOnly = true}면
 * {@code Connection.setReadOnly(true)} 호출도 커넥션을 요구하기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class TxWrappedUploadCourseReader {

    private final UploadCourseService uploadCourseService;

    @Transactional(readOnly = true)
    public UploadCourseDetailResponse getDetail(Long uploadCourseId, String viewerKey) {
        return uploadCourseService.getDetail(uploadCourseId, viewerKey);
    }

    @Transactional(readOnly = true)
    public UploadCourseListResponse getPopularCourses(KeywordType theme) {
        return uploadCourseService.getPopularCourses(theme);
    }
}
