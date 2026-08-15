package backend.yourtrip.domain.uploadcourse.service;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.config.BenchmarkProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 디스패처는 벤치마크 arm을 실제로 가르는 지점이다. 여기서 분기가 어긋나면 A1을 잰다고
 * 믿으면서 A2를 재게 되는데, 결과 수치만 봐서는 알아챌 방법이 없다. 그래서 두 경로가
 * 각각 의도한 빈으로만 가는지를 잠가둔다.
 */
@ExtendWith(MockitoExtension.class)
class UploadCourseReadDispatcherTest {

    @Mock
    private UploadCourseService uploadCourseService;

    @Mock
    private TxWrappedUploadCourseReader txWrappedUploadCourseReader;

    // 기본값(SEPARATED)이 곧 운영 동작이라 실제 인스턴스를 쓴다.
    @Spy
    private BenchmarkProperties benchmarkProperties = new BenchmarkProperties();

    @InjectMocks
    private UploadCourseReadDispatcher dispatcher;

    @Test
    @DisplayName("기본값에서는 상세 조회를 서비스로 직접 위임하고 트랜잭션 래퍼를 타지 않는다")
    void getDetail_DefaultSeparated_DelegatesToServiceDirectly() {
        dispatcher.getDetail(1L, "u5");

        verify(uploadCourseService).getDetail(1L, "u5");
        verifyNoInteractions(txWrappedUploadCourseReader);
    }

    @Test
    @DisplayName("기본값에서는 인기 코스 조회를 서비스로 직접 위임하고 트랜잭션 래퍼를 타지 않는다")
    void getPopularCourses_DefaultSeparated_DelegatesToServiceDirectly() {
        dispatcher.getPopularCourses(KeywordType.HEALING);

        verify(uploadCourseService).getPopularCourses(KeywordType.HEALING);
        verifyNoInteractions(txWrappedUploadCourseReader);
    }

    @Test
    @DisplayName("WRAPPED로 바꾸면 상세 조회가 트랜잭션 래퍼를 거친다")
    void getDetail_Wrapped_GoesThroughTxWrapper() {
        benchmarkProperties.setUploadCourseTx(BenchmarkProperties.UploadCourseTxMode.WRAPPED);

        dispatcher.getDetail(1L, "u5");

        verify(txWrappedUploadCourseReader).getDetail(1L, "u5");
        // 서비스를 직접 부르면 트랜잭션이 안 열려 A1이 A2와 같아진다
        verifyNoInteractions(uploadCourseService);
    }

    @Test
    @DisplayName("WRAPPED로 바꾸면 인기 코스 조회가 트랜잭션 래퍼를 거친다")
    void getPopularCourses_Wrapped_GoesThroughTxWrapper() {
        benchmarkProperties.setUploadCourseTx(BenchmarkProperties.UploadCourseTxMode.WRAPPED);

        dispatcher.getPopularCourses(null);

        verify(txWrappedUploadCourseReader).getPopularCourses(null);
        verifyNoInteractions(uploadCourseService);
    }
}
