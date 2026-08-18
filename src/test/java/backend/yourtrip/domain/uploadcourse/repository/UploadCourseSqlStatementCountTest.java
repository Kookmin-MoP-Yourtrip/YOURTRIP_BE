package backend.yourtrip.domain.uploadcourse.repository;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.uploadcourse.dto.cache.CourseListItemCacheItem;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListItemResponse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.mapper.UploadCourseMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

/**
 * 각 조회 경로가 실제로 발행하는 SQL 문장 수를 고정한다.
 * <p>
 * fetch 전략은 {@link backend.yourtrip.domain.uploadcourse.entity.EntityFetchStrategyTest}가
 * 선언 수준에서 지키지만, 그것만으로는 부족하다 — 매퍼가 새 연관을 건드리기 시작하거나, JPQL에서
 * fetch join이 빠지거나, LAZY로 선언해도 Hibernate가 무시하는 매핑(mappedBy 역방향 @OneToOne 등)이
 * 추가되면 선언은 그대로인 채 문장 수만 늘어난다. 이 테스트가 그 층을 맡는다.
 *
 * @see <a href="https://github.com/Kookmin-MoP-Yourtrip/YOURTRIP_BE/issues/85">#85</a>
 */
class UploadCourseSqlStatementCountTest extends UploadCourseJpaSliceSupport {

    @Autowired
    private DayScheduleRepository dayScheduleRepository;

    @Test
    @DisplayName("인기 코스 캐시 미스 경로는 SQL 2문장으로 끝난다 — 랭킹 1 + IN 조회 1")
    void popularMissPath_IssuesExactlyTwoStatements() {
        // given & when — UploadCoursePopularReader가 하는 일을 그대로 재현한다
        List<Long> ids = uploadCourseRepository.findPopularCourseIds(null,
            PageRequest.of(0, COURSE_COUNT));
        List<CourseListItemCacheItem> items = uploadCourseRepository.findAllByIdInWithKeywords(ids)
            .stream()
            // 실제 매퍼를 태워야 의미가 있다. 매퍼가 새 연관을 건드리기 시작하면 여기서 드러난다.
            .map(UploadCourseMapper::toCourseListItemCacheItem)
            .toList();

        // then
        assertThat(items).hasSize(COURSE_COUNT);
        assertThat(preparedStatements())
            .as("2문장을 넘으면 UploadCourse의 travelCourse/user가 EAGER로 되돌아갔거나 "
                + "매퍼가 fetch join되지 않은 연관을 건드리기 시작한 것이다(#85). "
                + "이 값은 Micrometer hibernate_statements_total과 같은 카운터다.")
            .isEqualTo(2);
    }

    @Test
    @DisplayName("상세 조회 캐시 미스 경로는 SQL 3문장이다 — 코스 1 + 일정 1 + 장소사진 subselect 1")
    void detailMissPath_IssuesExactlyThreeStatements() {
        // given & when — UploadCourseDetailReader.read를 그대로 재현한다
        UploadCourse uploadCourse = uploadCourseRepository
            .findWithTravelCourseAndKeywords(detailCourseId)
            .orElseThrow();
        List<DaySchedule> daySchedules = dayScheduleRepository.findDaySchedulesWithPlaces(
            uploadCourse.getTravelCourse().getId());
        // Place.placeImages는 @Fetch(SUBSELECT)라 첫 접근에서 한 문장이 나간다(리더가 강제 초기화하는 그 지점)
        daySchedules.forEach(daySchedule ->
            daySchedule.getPlaces().forEach(place -> place.getPlaceImages().size()));

        // then
        assertThat(daySchedules).isNotEmpty();
        assertThat(preparedStatements())
            .as("4문장이면 UploadCourse.user가 EAGER로 되돌아간 것이다 — 상세 응답은 user를 "
                + "전혀 쓰지 않는데도 users 조회가 따라붙던 것이 #85 이전 상태다.")
            .isEqualTo(3);
    }

    @Test
    @DisplayName("검색 목록은 코스가 몇 건이든 SQL 1문장이다")
    void searchList_IssuesExactlyOneStatement() {
        // given & when — getAllForSearch(sortType=NEW)와 동일한 인자로 호출한다
        List<UploadCourseListItemResponse> responses = uploadCourseRepository
            .findAllByKeywordsOrderByCreatedAtDesc(null, List.of(), 0)
            .stream()
            .map(uploadCourse -> UploadCourseMapper.toListItemResponse(uploadCourse, "thumb-url"))
            .toList();

        // then
        assertThat(responses).hasSize(COURSE_COUNT);
        assertThat(preparedStatements())
            .as("이 경로는 LIMIT이 없어 회귀하면 코스 수에 비례해 문장이 폭발한다 "
                + "(코스 3,000건이면 3,001문장 이상). 목록 매퍼는 travelCourse/user를 쓰지 않는다.")
            .isEqualTo(1);
    }

    @Test
    @DisplayName("내 업로드 코스 목록은 SQL 1문장이다")
    void myUploadCourseList_IssuesExactlyOneStatement() {
        // given & when
        List<UploadCourse> uploadCourses = uploadCourseRepository
            .findAllByUserIdOrderByCreatedAtDesc(ownerIds.get(0));
        uploadCourses.forEach(
            uploadCourse -> UploadCourseMapper.toListItemResponse(uploadCourse, "thumb-url"));

        // then
        assertThat(uploadCourses).hasSize(1);
        assertThat(preparedStatements()).isEqualTo(1);
    }
}
