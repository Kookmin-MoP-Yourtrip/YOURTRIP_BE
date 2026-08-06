package backend.yourtrip.domain.mycourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceImageRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.mycourse.repository.TravelCourseRepository;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.gemini.service.GeminiService;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.s3.service.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * test/presigned-url-bottleneck 전용 조정: getPlaceListByDay가 PR #61 이전의 순차 S3
 * presign 방식으로 되돌아갔으므로(CloudFront Signed URL 병렬 서명 제거), 이 클래스가 검증하던
 * "커스텀 스레드풀에서 실제로 병렬 실행되는가"/"일부 서명 실패 시 나머지만 반환하는가"는
 * 더 이상 이 코드 경로의 동작이 아니다(순차 stream이라 병렬성도, 개별 실패 격리도 없다).
 * 이 조정은 이 브랜치에서만 유지하고 main에는 머지하지 않는다 — PR #61의 병렬 서명 구조로
 * 되돌아가면 원래 테스트도 함께 복원해야 한다.
 */
@ExtendWith(MockitoExtension.class)
class MyCourseServiceImplTest {

    @Mock
    private UserService userService;
    @Mock
    private S3Service s3Service;
    @Mock
    private GeminiService geminiService;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private TravelCourseRepository travelCourseRepository;
    @Mock
    private DayScheduleRepository dayScheduleRepository;
    @Mock
    private PlaceRepository placeRepository;
    @Mock
    private PlaceImageRepository placeImageRepository;
    @Mock
    private UploadCourseRepository uploadCourseRepository;
    @Mock
    private KakaoLocalClient kakaoLocalClient;

    @InjectMocks
    private MyCourseServiceImpl myCourseService;

    private static final Long OWNER_ID = 10L;
    private static final Long COURSE_ID = 1L;
    private static final Long DAY_ID = 100L;

    @BeforeEach
    void setUp() {
        // @InjectMocks가 필드 순서대로 생성자에 주입한다.
    }

    @Test
    @DisplayName("순차 presign으로 발급해도 이미지-장소 매핑과 응답 구조가 올바르다")
    void getPlaceListByDay_SequentialPresign_MapsImagesToCorrectPlaces() {
        // given
        DaySchedule daySchedule = daySchedule();
        Place place1 = place(daySchedule, 1L);
        Place place2 = place(daySchedule, 2L);
        addImage(place1, 11L, "private/p1-a.jpg");
        addImage(place1, 12L, "private/p1-b.jpg");
        addImage(place2, 21L, "private/p2-a.jpg");
        daySchedule.getPlaces().add(place1);
        daySchedule.getPlaces().add(place2);

        given(travelCourseRepository.existsById(COURSE_ID)).willReturn(true);
        given(travelCourseRepository.existsByIdAndUser_Id(COURSE_ID, OWNER_ID)).willReturn(true);
        given(userService.getCurrentUserId()).willReturn(OWNER_ID);
        given(dayScheduleRepository.findByIdWithPlaces(COURSE_ID, DAY_ID))
            .willReturn(Optional.of(daySchedule));
        given(s3Service.getPresignedUrl(anyString()))
            .willAnswer(inv -> "presigned-" + inv.getArgument(0, String.class));

        // when
        DayScheduleResponse response = myCourseService.getPlaceListByDay(COURSE_ID, DAY_ID);

        // then
        assertThat(response.places()).hasSize(2);
        PlaceResponse resPlace1 = findPlace(response, 1L);
        PlaceResponse resPlace2 = findPlace(response, 2L);

        assertThat(resPlace1.placeImages())
            .extracting(PlaceImageResponse::placeImageUrl)
            .containsExactlyInAnyOrder("presigned-private/p1-a.jpg", "presigned-private/p1-b.jpg");
        assertThat(resPlace2.placeImages())
            .extracting(PlaceImageResponse::placeImageUrl)
            .containsExactly("presigned-private/p2-a.jpg");
    }

    private DaySchedule daySchedule() {
        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner").build();
        setEntityId(owner, OWNER_ID);
        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner).title("t").location("l")
            .startDate(LocalDate.now()).endDate(LocalDate.now())
            .type(TravelCourseType.DIRECT)
            .build();
        setEntityId(travelCourse, COURSE_ID);
        DaySchedule daySchedule = DaySchedule.builder().course(travelCourse).day(1).build();
        setEntityId(daySchedule, DAY_ID);
        return daySchedule;
    }

    private Place place(DaySchedule daySchedule, Long placeId) {
        Place place = Place.builder()
            .daySchedule(daySchedule).placeName("place").latitude(0).longitude(0)
            .placeUrl("url").placeLocation("loc")
            .build();
        setEntityId(place, placeId);
        return place;
    }

    private void addImage(Place place, Long imageId, String s3Key) {
        PlaceImage image = new PlaceImage(place, s3Key);
        setEntityId(image, imageId);
        place.getPlaceImages().add(image);
    }

    private PlaceResponse findPlace(DayScheduleResponse response, Long placeId) {
        return response.places().stream()
            .filter(p -> p.placeId().equals(placeId))
            .findFirst()
            .orElseThrow();
    }

    private void setEntityId(Object entity, Long id) {
        try {
            Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
