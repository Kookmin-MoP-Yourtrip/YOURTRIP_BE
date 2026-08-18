package backend.yourtrip.domain.uploadcourse.repository;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.uploadcourse.entity.CourseKeyword;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 발행되는 SQL 문장 수와 LAZY 프록시의 초기화 여부를 재는 테스트들의 공통 토대다.
 * <p>
 * {@code @SpringBootTest}가 아니라 {@code @DataJpaTest}를 쓰는 이유: Redis·S3·CloudFront·Gemini
 * 빈을 아예 로딩하지 않아 컨텍스트가 가볍고, 이 테스트가 재려는 것이 JPA 매핑과 JPQL뿐이기 때문이다.
 * {@code replace = NONE}은 application-test.yml의 H2 설정을 그대로 쓰겠다는 뜻이다(기본값은 Spring이
 * 자체 임베디드 DB로 갈아끼우는데, 그러면 MODE=PostgreSQL 같은 설정이 통째로 무시된다).
 * <p>
 * 시드에서 <b>소유자를 코스마다 다른 사람으로</b> 흩어놓는 것이 이 클래스의 핵심이다. 부하테스트
 * 시드(scripts/sql/seed-benchmark.sql)는 전 코스를 단일 사용자가 소유해 {@code users} 조회가
 * 1회로 접혔고, 그래서 EAGER N+1의 실제 크기가 8문장으로 과소평가됐다
 * (docs/tasks/cache-effect-measurement/phase0-local-gate.md). 소유자를 흩어놓아야 fetch 전략이
 * EAGER로 되돌아갔을 때 그 회귀가 코스 수만큼 곱해져 드러난다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
abstract class UploadCourseJpaSliceSupport {

    /** 인기 코스 조회 상한(POPULAR_COURSE_LIMIT)과 맞춰둔다. top5가 전부 채워져야 N+1이 5배로 드러난다. */
    protected static final int COURSE_COUNT = 5;

    @PersistenceContext
    protected EntityManager em;

    @Autowired
    protected UploadCourseRepository uploadCourseRepository;

    protected Statistics statistics;

    /** viewCount 내림차순 = 인기 순위 순서. 인덱스 0이 1위다. */
    protected final List<Long> uploadCourseIds = new ArrayList<>();
    protected final List<Long> ownerIds = new ArrayList<>();

    /** 일정·장소·장소사진까지 달려 있는 코스(상세 조회 경로 측정용). 1위 코스와 같다. */
    protected Long detailCourseId;
    protected Long detailTravelCourseId;

    @BeforeEach
    void seedAndResetStatistics() {
        uploadCourseIds.clear();
        ownerIds.clear();

        for (int i = 0; i < COURSE_COUNT; i++) {
            User owner = User.builder()
                .email("owner" + i + "@yourtrip.test")
                .password("encoded-password")
                .nickname("소유자" + i)
                .build();
            em.persist(owner);

            TravelCourse travelCourse = TravelCourse.builder()
                .user(owner)
                .title("경주 코스 " + i)
                .location("경주")
                .startDate(LocalDate.of(2026, 5, 1))
                .endDate(LocalDate.of(2026, 5, 2))
                .type(TravelCourseType.UPLOADED)
                .build();
            em.persist(travelCourse);

            UploadCourse uploadCourse = UploadCourse.builder()
                .title("경주 1박 2일 " + i)
                .introduction("소개 " + i)
                .thumbnailImageS3Key("thumbnail-" + i + ".png")
                .travelCourse(travelCourse)
                .user(owner)
                .location("경주")
                .build();
            uploadCourse.getKeywords().add(new CourseKeyword(uploadCourse, KeywordType.HEALING));
            uploadCourse.getKeywords().add(new CourseKeyword(uploadCourse, KeywordType.FOOD));

            // 인덱스가 작을수록 조회수가 높다 — findPopularCourseIds의 정렬 결과를 예측 가능하게 만든다.
            for (int view = 0; view < COURSE_COUNT - i; view++) {
                uploadCourse.increaseViewCount();
            }
            em.persist(uploadCourse);

            if (i == 0) {
                seedDaySchedule(travelCourse);
                detailTravelCourseId = travelCourse.getId();
            }

            uploadCourseIds.add(uploadCourse.getId());
            ownerIds.add(owner.getId());
        }
        detailCourseId = uploadCourseIds.get(0);

        // 시드 INSERT가 1차 캐시에 남아 있으면 이후 조회가 SELECT를 아예 내지 않아 측정이 무의미해진다.
        em.flush();
        em.clear();

        statistics = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
    }

    private void seedDaySchedule(TravelCourse travelCourse) {
        DaySchedule daySchedule = DaySchedule.builder().course(travelCourse).day(1).build();
        em.persist(daySchedule);

        Place place = Place.builder()
            .daySchedule(daySchedule)
            .placeName("불국사")
            .startTime(LocalTime.of(10, 0))
            .memo("아침 일찍")
            .latitude(35.7900)
            .longitude(129.3320)
            .placeUrl("https://place.map.kakao.com/1")
            .placeLocation("경주시 진현동")
            .build();
        em.persist(place);
        em.persist(new PlaceImage(place, "place-image-1.png"));
    }

    /**
     * Micrometer가 {@code hibernate_statements_total{status="prepared"}}로 내보내는 값과 동일한
     * 카운터다. 즉 이 테스트가 세는 수와 부하테스트 대시보드가 세는 수가 같다.
     */
    protected long preparedStatements() {
        return statistics.getPrepareStatementCount();
    }
}
