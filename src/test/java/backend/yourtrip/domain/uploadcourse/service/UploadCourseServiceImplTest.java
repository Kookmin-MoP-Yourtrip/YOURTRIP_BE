package backend.yourtrip.domain.uploadcourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.uploadcourse.dto.request.DayScheduleUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import backend.yourtrip.global.s3.service.S3Service;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

@ExtendWith(MockitoExtension.class)
class UploadCourseServiceImplTest {

    @Mock
    private UploadCourseRepository uploadCourseRepository;

    @Mock
    private MyCourseService myCourseService;

    @Mock
    private UserService userService;

    @Mock
    private S3Service s3Service;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private UploadCourseServiceImpl uploadCourseService;

    @Test
    @DisplayName("작성자가 아닌 사용자가 코스 수정을 시도하면 NOT_OWNED_UPLOAD_COURSE 예외가 발생한다")
    void updateUploadCourse_NotOwner_ThrowsException() {
        // given
        Long uploadCourseId = 1L;
        Long ownerId = 10L;
        Long otherUserId = 20L;

        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner").build();
        setEntityId(owner, ownerId);

        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner)
            .title("원래 제목")
            .location("경주")
            .startDate(LocalDate.now())
            .endDate(LocalDate.now())
            .type(TravelCourseType.UPLOADED)
            .build();

        UploadCourse uploadCourse = UploadCourse.builder()
            .title("원래 제목")
            .introduction("소개")
            .thumbnailImageS3Key("thumb.png")
            .travelCourse(travelCourse)
            .user(owner)
            .location("경주")
            .build();

        given(uploadCourseRepository.findWithTravelCourseAndKeywords(uploadCourseId))
            .willReturn(Optional.of(uploadCourse));
        given(userService.getCurrentUserId()).willReturn(otherUserId);

        UploadCourseUpdateRequest request = UploadCourseUpdateRequest.builder()
            .title("수정된 제목")
            .startDate(LocalDate.now())
            .endDate(LocalDate.now())
            .keywords(List.of())
            .daySchedules(List.of())
            .build();

        // when & then
        assertThatThrownBy(() -> uploadCourseService.updateUploadCourse(uploadCourseId, request, null, null))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UploadCourseErrorCode.NOT_OWNED_UPLOAD_COURSE);
    }

    @Test
    @DisplayName("작성자가 업로드 코스를 수정하면 정보가 올바르게 갱신된다")
    void updateUploadCourse_Success() {
        // given
        Long uploadCourseId = 1L;
        Long ownerId = 10L;

        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner").build();
        setEntityId(owner, ownerId);

        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner)
            .title("원래 제목")
            .location("경주")
            .startDate(LocalDate.of(2025, 3, 1))
            .endDate(LocalDate.of(2025, 3, 1))
            .type(TravelCourseType.UPLOADED)
            .build();
        setEntityId(travelCourse, 100L);

        DaySchedule daySchedule = DaySchedule.builder()
            .course(travelCourse)
            .day(1)
            .build();
        setEntityId(daySchedule, 1000L);
        travelCourse.getDaySchedules().add(daySchedule);

        Place place = Place.builder()
            .daySchedule(daySchedule)
            .placeName("황리단길")
            .startTime(LocalTime.of(10, 0))
            .memo("메모")
            .latitude(35.8)
            .longitude(129.2)
            .placeUrl("http://kakao.com")
            .placeLocation("경주")
            .build();
        setEntityId(place, 2000L);
        daySchedule.getPlaces().add(place);

        UploadCourse uploadCourse = UploadCourse.builder()
            .title("원래 제목")
            .introduction("소개")
            .thumbnailImageS3Key("thumb.png")
            .travelCourse(travelCourse)
            .user(owner)
            .location("경주")
            .build();

        given(uploadCourseRepository.findWithTravelCourseAndKeywords(uploadCourseId))
            .willReturn(Optional.of(uploadCourse));
        given(userService.getCurrentUserId()).willReturn(ownerId);
        given(myCourseService.getDaySchedulesWithPlaces(100L)).willReturn(List.of(daySchedule));
        given(myCourseService.getAllDaySchedulesByCourse(100L)).willReturn(List.of(
            new DayScheduleResponse(1000L, 1, List.of())
        ));
        given(s3Service.getPresignedUrl("thumb.png")).willReturn("http://s3.com/thumb.png");

        UploadCourseUpdateRequest request = UploadCourseUpdateRequest.builder()
            .title("수정된 경주 여행")
            .introduction("수정된 소개글")
            .location("경주 황리단길")
            .startDate(LocalDate.of(2025, 3, 1))
            .endDate(LocalDate.of(2025, 3, 1))
            .keywords(List.of(KeywordType.WALK, KeywordType.FOOD))
            .daySchedules(List.of(
                DayScheduleUpdateRequest.builder()
                    .dayScheduleId(1000L)
                    .day(1)
                    .places(List.of(
                        PlaceUpdateRequest.builder()
                            .placeId(2000L)
                            .placeName("수정된 황리단길")
                            .startTime(LocalTime.of(11, 0))
                            .memo("수정된 메모")
                            .latitude(35.9)
                            .longitude(129.3)
                            .placeUrl("http://kakao.com/new")
                            .placeLocation("경주 포석로")
                            .placeImages(List.of())
                            .build()
                    ))
                    .build()
            ))
            .build();

        // when
        UploadCourseDetailResponse response = uploadCourseService.updateUploadCourse(uploadCourseId, request, null, null);

        // then
        assertThat(response).isNotNull();
        assertThat(uploadCourse.getTitle()).isEqualTo("수정된 경주 여행");
        assertThat(uploadCourse.getIntroduction()).isEqualTo("수정된 소개글");
        assertThat(uploadCourse.getLocation()).isEqualTo("경주 황리단길");
        assertThat(uploadCourse.getKeywords()).hasSize(2);
        assertThat(travelCourse.getTitle()).isEqualTo("수정된 경주 여행");
        assertThat(place.getPlaceName()).isEqualTo("수정된 황리단길");
    }

    private void setEntityId(Object entity, Long id) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
