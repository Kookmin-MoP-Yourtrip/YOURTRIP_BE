package backend.yourtrip.domain.uploadcourse.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import java.util.List;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * travelCourse·user를 LAZY로 두는 것이 안전한 근거 자체를 실행 가능한 형태로 고정한다.
 * <p>
 * 이 저장소는 {@code spring.jpa.open-in-view: false}인데 {@code UploadCourseServiceImpl.getDetail}은
 * {@code @Transactional} 없이, {@code UploadCourseDetailReader}의 짧은 트랜잭션이 끝난 <b>뒤</b>
 * 엔티티의 {@code getTravelCourse()}·{@code getKeywords()}를 읽는다. 그것이 터지지 않는 유일한 이유는
 * {@code findWithTravelCourseAndKeywords}의 JPQL에 fetch join이 들어 있기 때문인데, 그 보장은
 * <b>다른 파일의 문자열 안에만</b> 존재한다. PR #70에서 {@code Place.placeImages}가 정확히 이 방식으로
 * {@code LazyInitializationException}을 냈다.
 * <p>
 * 소유권 체크가 SQL을 내지 않는 것도 Hibernate의 identifier getter 최적화에 기댄 조용한 계약이라,
 * 여기서 함께 못 박는다.
 *
 * @see <a href="https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/85">#85</a>
 */
class UploadCourseLazyProxyContractTest extends UploadCourseJpaSliceSupport {

    @Test
    @DisplayName("findWithTravelCourseAndKeywords는 travelCourse·keywords를 초기화해서 준다 "
        + "— getDetail이 트랜잭션 밖에서 이 둘을 읽는 근거")
    void detailQuery_InitializesAssociationsThatEscapeTheTransaction() {
        // given
        UploadCourse uploadCourse = uploadCourseRepository
            .findWithTravelCourseAndKeywords(detailCourseId)
            .orElseThrow();

        // when — 트랜잭션이 끝난 뒤의 상황을 흉내낸다
        em.detach(uploadCourse);

        // then
        assertThat(Hibernate.isInitialized(uploadCourse.getTravelCourse()))
            .as("JPQL에서 JOIN FETCH uc.travelCourse가 빠지면 getDetail이 "
                + "LazyInitializationException으로 터진다.")
            .isTrue();
        assertThatCode(() -> uploadCourse.getTravelCourse().getStartDate())
            .doesNotThrowAnyException();
        assertThatCode(() -> uploadCourse.getKeywords().size()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("getUser().getId()는 프록시를 초기화하지 않는다 — 소유권 체크가 SQL을 내지 않는 근거")
    void ownershipCheck_ReadsIdWithoutInitializingProxy() {
        // given
        UploadCourse uploadCourse = uploadCourseRepository
            .findWithTravelCourseAndKeywords(detailCourseId)
            .orElseThrow();
        long before = preparedStatements();

        // when & then
        assertThat(Hibernate.isInitialized(uploadCourse.getUser()))
            .as("user는 어떤 JPQL에서도 fetch join되지 않으므로 프록시여야 한다.")
            .isFalse();
        assertThat(uploadCourse.getUser().getId()).isEqualTo(ownerIds.get(0));
        assertThat(Hibernate.isInitialized(uploadCourse.getUser()))
            .as("getId()가 프록시를 초기화했다면 Hibernate의 identifier getter 최적화가 깨진 것이다 "
                + "— @Id를 getter로 옮겼거나 Lombok @Getter를 떼서 getId()가 필드와 대응하지 않게 된 경우다. "
                + "그러면 소유권 체크 5곳(업로드 코스 수정·삭제, fork, 피드 생성·수정)에 users 조회가 부활한다.")
            .isFalse();
        assertThat(preparedStatements()).isEqualTo(before);
    }

    @Test
    @DisplayName("findAllByIdInWithKeywords는 travelCourse를 초기화하지 않는다 — 인기 목록이 2문장인 이유")
    void popularQuery_LeavesTravelCourseUninitialized() {
        // given
        List<Long> ids = List.of(detailCourseId);

        // when
        UploadCourse uploadCourse = uploadCourseRepository.findAllByIdInWithKeywords(ids).get(0);

        // then
        assertThat(Hibernate.isInitialized(uploadCourse.getTravelCourse())).isFalse();
        assertThat(Hibernate.isInitialized(uploadCourse.getUser())).isFalse();
        assertThat(Hibernate.isInitialized(uploadCourse.getKeywords()))
            .as("keywords는 LEFT JOIN FETCH 대상이라 초기화돼 있어야 한다 "
                + "— 목록 매퍼가 트랜잭션 안에서 이걸 읽는다.")
            .isTrue();
    }
}
