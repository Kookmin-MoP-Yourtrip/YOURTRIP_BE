package backend.yourtrip.domain.uploadcourse.repository;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.uploadcourse.entity.CourseKeyword;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.user.entity.User;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

/**
 * 테마 지정 인기 코스 랭킹 쿼리({@code findPopularCourseIdsByTheme})의 의미 계약을 고정한다.
 * <p>
 * 이 쿼리는 원래 {@code findPopularCourseIds(theme, pageable)} 하나에
 * {@code (:theme IS NULL OR EXISTS (...))} 형태로 합쳐져 있었다. 그 OR 구조 때문에 PostgreSQL이
 * EXISTS를 세미조인으로 승격하지 못해 랭킹 쿼리가 데이터 규모에 선형으로 느려졌고, 이를 고치려
 * 쿼리를 둘로 분리하면서 <b>테마 분기를 타는 테스트가 하나도 없다는 것</b>이 드러나 이 클래스를 만들었다.
 * <p>
 * <b>이 테스트가 지킬 수 없는 것</b>: 테스트 DB는 H2라 실행계획을 잴 수 없다. 여기서 고정하는 것은
 * 결과 동등성·정렬·문장 수뿐이고, "Nested Loop Semi Join이 선택되는가"라는 플랜 계약은
 * docs/tasks/popular-theme-index/README.md의 EXPLAIN 기록이 지킨다. H2와 PostgreSQL은 방언도
 * 달라, 여기서 통과한 SQL이 운영에서 도는 SQL과 문자열까지 같지는 않다.
 */
class UploadCoursePopularThemeQueryTest extends UploadCourseJpaSliceSupport {

    /** 필터 결과를 전부 보기 위해 상위 5건보다 넉넉히 요청한다. */
    private static final PageRequest TOP_TEN = PageRequest.of(0, 10);

    private Long cultureOnlyId;
    private Long softDeletedFoodId;
    private Long duplicateFoodId;

    /**
     * 각 테스트 첫 줄에서 명시적으로 호출한다.
     * <p>
     * {@code @BeforeEach}로 두지 않는 이유: 여기서 나가는 INSERT가 Hibernate 통계에 섞여 문장 수
     * 단언이 무의미해진다. 마지막에 flush/clear/statistics.clear()로 영점을 다시 잡는다.
     * <p>
     * 공용 시드({@link UploadCourseJpaSliceSupport})는 건드리지 않는다 — 5개 코스 전부에
     * HEALING+FOOD를 똑같이 넣고 있어 그대로는 "테마로 걸러진다"를 증명할 수 없고, 공용 시드에 코스를
     * 더하면 {@code UploadCourseSqlStatementCountTest}의 {@code hasSize(COURSE_COUNT)}가 깨진다.
     * <p>
     * 조회수는 공용 시드(5,4,3,2,1)와 겹치지 않는 값으로 준다 — 동점이 있으면 정렬이 비결정적이 돼
     * 순서 단언이 산발적으로 실패한다.
     */
    private void seedThemeVariants() {
        // CULTURE만 가진 코스 — FOOD 조회에서 빠져야 한다
        cultureOnlyId = persistCourse("문화 코스", 30, KeywordType.CULTURE);

        // FOOD를 가졌지만 소프트 삭제된 코스 — @SQLRestriction으로 빠져야 한다
        softDeletedFoodId = persistCourse("삭제된 맛집 코스", 20, KeywordType.FOOD);

        // 같은 FOOD 키워드를 두 번 가진 코스 — 중복 행이 나오면 안 된다
        duplicateFoodId = persistCourse("맛집 중복 코스", 10, KeywordType.FOOD, KeywordType.FOOD);

        em.flush();
        // deleted에는 setter도 @SQLDelete도 없어 네이티브로 상태를 만든다.
        em.createNativeQuery("update upload_course set deleted = true where upload_course_id = :id")
            .setParameter("id", softDeletedFoodId)
            .executeUpdate();

        em.flush();
        em.clear();
        statistics.clear();
    }

    private Long persistCourse(String title, int viewCount, KeywordType... keywords) {
        User owner = User.builder()
            .email(title + "@yourtrip.test")
            .password("encoded-password")
            .nickname(title)
            .build();
        em.persist(owner);

        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner)
            .title(title)
            .location("경주")
            .startDate(LocalDate.of(2026, 5, 1))
            .endDate(LocalDate.of(2026, 5, 2))
            .type(TravelCourseType.UPLOADED)
            .build();
        em.persist(travelCourse);

        UploadCourse uploadCourse = UploadCourse.builder()
            .title(title)
            .introduction("소개")
            .thumbnailImageS3Key("thumbnail.png")
            .travelCourse(travelCourse)
            .user(owner)
            .location("경주")
            .build();
        for (KeywordType keyword : keywords) {
            uploadCourse.getKeywords().add(new CourseKeyword(uploadCourse, keyword));
        }
        for (int i = 0; i < viewCount; i++) {
            uploadCourse.increaseViewCount();
        }
        em.persist(uploadCourse);
        return uploadCourse.getId();
    }

    @Test
    @DisplayName("테마 조회는 그 키워드를 가진 코스만 반환한다")
    void themeQuery_ReturnsOnlyCoursesHavingThatKeyword() {
        seedThemeVariants();

        List<Long> ids = uploadCourseRepository.findPopularCourseIdsByTheme(
            KeywordType.CULTURE, TOP_TEN);

        assertThat(ids)
            .as("공용 시드 5건은 HEALING+FOOD만 가지므로 CULTURE 조회에 걸리면 안 된다")
            .containsExactly(cultureOnlyId);
    }

    @Test
    @DisplayName("테마 조회는 조회수 내림차순으로 정렬된다")
    void themeQuery_OrdersByViewCountDesc() {
        seedThemeVariants();

        List<Long> ids = uploadCourseRepository.findPopularCourseIdsByTheme(
            KeywordType.FOOD, TOP_TEN);

        List<Long> expected = em.createQuery(
                "select uc from UploadCourse uc where uc.id in :ids", UploadCourse.class)
            .setParameter("ids", ids)
            .getResultList()
            .stream()
            .sorted(Comparator.comparingInt(UploadCourse::getViewCount).reversed())
            .map(UploadCourse::getId)
            .toList();

        assertThat(ids).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("테마 조회는 소프트 삭제된 코스를 제외한다")
    void themeQuery_ExcludesSoftDeletedCourse() {
        seedThemeVariants();

        List<Long> ids = uploadCourseRepository.findPopularCourseIdsByTheme(
            KeywordType.FOOD, TOP_TEN);

        assertThat(ids)
            .as("UploadCourse의 @SQLRestriction(deleted = false)이 분리된 쿼리에도 살아 있어야 한다")
            .doesNotContain(softDeletedFoodId);
    }

    @Test
    @DisplayName("같은 키워드를 두 번 가진 코스도 한 번만 반환한다")
    void themeQuery_DoesNotDuplicate_WhenCourseHasSameKeywordTwice() {
        seedThemeVariants();

        List<Long> ids = uploadCourseRepository.findPopularCourseIdsByTheme(
            KeywordType.FOOD, TOP_TEN);

        assertThat(ids)
            .as("EXISTS(세미조인)를 JOIN으로 바꾸면 여기서 중복이 난다. 중복을 DISTINCT로 막으면 "
                + "top-N 인덱스 스캔이 Sort/Unique로 퇴화해 규모 의존성이 되살아난다.")
            .containsOnlyOnce(duplicateFoodId)
            .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("해당 테마를 가진 코스가 없으면 빈 목록을 반환한다")
    void themeQuery_ReturnsEmpty_WhenNoCourseHasTheme() {
        seedThemeVariants();

        List<Long> ids = uploadCourseRepository.findPopularCourseIdsByTheme(
            KeywordType.SHOPPING, TOP_TEN);

        assertThat(ids).isEmpty();
    }

    @Test
    @DisplayName("테마 없는 조회는 삭제되지 않은 전 코스를 조회수 내림차순으로 반환한다")
    void allQuery_MatchesLegacyNullThemeSemantics() {
        seedThemeVariants();

        List<Long> ids = uploadCourseRepository.findPopularCourseIds(TOP_TEN);

        // 같은 JPQL로 기대값을 만들면 아무것도 검증하지 못한다. 전건을 받아 Java에서 정렬한다.
        List<Long> expected = em.createQuery("select uc from UploadCourse uc", UploadCourse.class)
            .getResultList()
            .stream()
            .sorted(Comparator.comparingInt(UploadCourse::getViewCount).reversed())
            .map(UploadCourse::getId)
            .limit(TOP_TEN.getPageSize())
            .toList();

        assertThat(ids).containsExactlyElementsOf(expected);
        assertThat(ids).doesNotContain(softDeletedFoodId);
    }

    @Test
    @DisplayName("테마 지정 캐시 미스 경로도 SQL 2문장으로 끝난다 — 랭킹 1 + IN 조회 1")
    void themeMissPath_IssuesExactlyTwoStatements() {
        // 공용 시드 5건이 전부 FOOD를 가지므로 별도 픽스처 없이 top5가 채워진다.
        List<Long> ids = uploadCourseRepository.findPopularCourseIdsByTheme(
            KeywordType.FOOD, PageRequest.of(0, COURSE_COUNT));
        List<UploadCourse> courses = uploadCourseRepository.findAllByIdInWithKeywords(ids);

        assertThat(courses).hasSize(COURSE_COUNT);
        assertThat(preparedStatements())
            .as("ALL 경로(UploadCourseSqlStatementCountTest)와 같은 2문장이어야 한다. "
                + "EXISTS 서브쿼리가 별도 select로 쪼개지면 여기서 드러난다.")
            .isEqualTo(2);
    }
}
