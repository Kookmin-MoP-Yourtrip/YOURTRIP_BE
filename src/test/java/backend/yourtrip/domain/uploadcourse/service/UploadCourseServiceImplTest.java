package backend.yourtrip.domain.uploadcourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.uploadcourse.dto.request.DayScheduleUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.event.UploadCourseCacheRefreshEvent;
import backend.yourtrip.domain.uploadcourse.event.UploadCourseDeletedEvent;
import backend.yourtrip.domain.uploadcourse.event.UploadCourseImagesCleanupEvent;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import backend.yourtrip.global.s3.service.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisTemplate;

@ExtendWith(MockitoExtension.class)
class UploadCourseServiceImplTest {

    @Mock
    private UploadCourseRepository uploadCourseRepository;

    @Mock
    private MyCourseService myCourseService;

    @Mock
    private UploadCourseDetailReader uploadCourseDetailReader;

    @Mock
    private UserService userService;

    @Mock
    private S3Service s3Service;

    @Mock
    private CloudFrontService cloudFrontService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private UploadCourseViewCountService uploadCourseViewCountService;

    @Mock
    private RedisTemplate<String, Object> cacheValueRedisTemplate;

    @Mock
    private ObjectMapper objectMapper;

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
        given(cloudFrontService.getPublicUrl("thumb.png")).willReturn("http://cloudfront.example.com/thumb.png");

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
        verify(eventPublisher).publishEvent(any(UploadCourseCacheRefreshEvent.class));
    }

    @Test
    @DisplayName("resolveViewerKey는 로그인 여부를 판별해 viewerKey 계산을 위임한다")
    void resolveViewerKey_DelegatesToViewCountService() {
        // given
        given(userService.getCurrentUserIdOrNull()).willReturn(5L);
        given(uploadCourseViewCountService.resolveViewerKey(5L, "1.2.3.4", "Mozilla/5.0"))
            .willReturn("u5");

        // when
        String viewerKey = uploadCourseService.resolveViewerKey("1.2.3.4", "Mozilla/5.0");

        // then
        assertThat(viewerKey).isEqualTo("u5");
        verify(uploadCourseViewCountService).resolveViewerKey(5L, "1.2.3.4", "Mozilla/5.0");
    }

    @Test
    @DisplayName("getDetail 호출 시 전달받은 viewerKey로 조회수 중복 방지 게이트를 호출한다")
    void getDetail_CallsViewCountGateWithViewerKey() {
        // given
        Long uploadCourseId = 1L;
        String viewerKey = "u5";

        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner").build();
        setEntityId(owner, 10L);

        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner)
            .title("경주 여행")
            .location("경주")
            .startDate(LocalDate.now())
            .endDate(LocalDate.now())
            .type(TravelCourseType.UPLOADED)
            .build();
        setEntityId(travelCourse, 100L);

        UploadCourse uploadCourse = UploadCourse.builder()
            .title("경주 여행")
            .introduction("소개")
            .travelCourse(travelCourse)
            .user(owner)
            .location("경주")
            .build();

        given(uploadCourseDetailReader.read(uploadCourseId))
            .willReturn(new UploadCourseDetailReader.UploadCourseDetailReadResult(uploadCourse, List.of()));

        // when
        uploadCourseService.getDetail(uploadCourseId, viewerKey);

        // then
        verify(uploadCourseViewCountService).incrementViewCountIfNotDuplicate(uploadCourseId, viewerKey);
    }

    @Test
    @DisplayName("작성자가 아닌 사용자가 업로드 코스 삭제를 시도하면 NOT_OWNED_UPLOAD_COURSE 예외가 발생한다")
    void deleteUploadCourse_NotOwner_ThrowsException() {
        // given
        Long uploadCourseId = 1L;
        Long ownerId = 10L;
        Long otherUserId = 20L;

        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner").build();
        setEntityId(owner, ownerId);

        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner).title("t").location("경주")
            .startDate(LocalDate.now()).endDate(LocalDate.now())
            .type(TravelCourseType.UPLOADED)
            .build();
        setEntityId(travelCourse, 100L);

        UploadCourse uploadCourse = UploadCourse.builder()
            .title("t").introduction("소개").thumbnailImageS3Key("thumb.png")
            .travelCourse(travelCourse).user(owner).location("경주")
            .build();

        given(uploadCourseRepository.findWithTravelCourseAndKeywords(uploadCourseId))
            .willReturn(Optional.of(uploadCourse));
        given(userService.getCurrentUserId()).willReturn(otherUserId);

        // when & then
        assertThatThrownBy(() -> uploadCourseService.deleteUploadCourse(uploadCourseId))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UploadCourseErrorCode.NOT_OWNED_UPLOAD_COURSE);
    }

    @Test
    @DisplayName("존재하지 않는 업로드 코스를 삭제하려 하면 UPLOAD_COURSE_NOT_FOUND 예외가 발생한다")
    void deleteUploadCourse_NotFound_ThrowsException() {
        // given
        given(uploadCourseRepository.findWithTravelCourseAndKeywords(1L)).willReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> uploadCourseService.deleteUploadCourse(1L))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND);
    }

    @Test
    @DisplayName("작성자가 업로드 코스를 삭제하면 hidden copy까지 함께 삭제되고 S3/Redis 이벤트가 발행된다")
    void deleteUploadCourse_Success_DeletesHiddenCopyAndPublishesEvents() {
        // given
        Long uploadCourseId = 1L;
        Long ownerId = 10L;

        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner").build();
        setEntityId(owner, ownerId);

        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner).title("t").location("경주")
            .startDate(LocalDate.now()).endDate(LocalDate.now())
            .type(TravelCourseType.UPLOADED)
            .build();
        setEntityId(travelCourse, 100L);

        DaySchedule daySchedule = DaySchedule.builder().course(travelCourse).day(1).build();
        setEntityId(daySchedule, 1000L);
        Place place = Place.builder()
            .daySchedule(daySchedule).placeName("황리단길")
            .latitude(35.8).longitude(129.2)
            .placeUrl("url").placeLocation("경주")
            .build();
        setEntityId(place, 2000L);
        PlaceImage image = new PlaceImage(place, "place-key.jpg");
        setEntityId(image, 3000L);
        place.getPlaceImages().add(image);
        daySchedule.getPlaces().add(place);

        UploadCourse uploadCourse = UploadCourse.builder()
            .title("t").introduction("소개").thumbnailImageS3Key("thumb.png")
            .travelCourse(travelCourse).user(owner).location("경주")
            .build();

        given(uploadCourseRepository.findWithTravelCourseAndKeywords(uploadCourseId))
            .willReturn(Optional.of(uploadCourse));
        given(userService.getCurrentUserId()).willReturn(ownerId);
        given(myCourseService.getDaySchedulesWithPlaces(100L)).willReturn(List.of(daySchedule));

        // when
        uploadCourseService.deleteUploadCourse(uploadCourseId);

        // then
        verify(uploadCourseRepository).delete(uploadCourse);
        verify(myCourseService).deleteHiddenUploadCopy(travelCourse);
        verify(eventPublisher).publishEvent(ArgumentMatchers.<Object>argThat(event ->
            event instanceof UploadCourseImagesCleanupEvent imagesEvent
                && imagesEvent.imageS3Keys().size() == 2
                && imagesEvent.imageS3Keys().containsAll(List.of("thumb.png", "place-key.jpg"))
        ));
        verify(eventPublisher).publishEvent(ArgumentMatchers.<Object>argThat(event ->
            event instanceof UploadCourseDeletedEvent deletedEvent
                && deletedEvent.uploadCourseId().equals(uploadCourseId)
        ));
    }

    @Test
    @DisplayName("기본 썸네일만 있고 장소 이미지가 없으면 S3 정리 이벤트를 발행하지 않는다")
    void deleteUploadCourse_DefaultThumbnailAndNoImages_DoesNotPublishCleanupEvent() {
        // given
        Long uploadCourseId = 1L;
        Long ownerId = 10L;

        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner").build();
        setEntityId(owner, ownerId);

        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner).title("t").location("경주")
            .startDate(LocalDate.now()).endDate(LocalDate.now())
            .type(TravelCourseType.UPLOADED)
            .build();
        setEntityId(travelCourse, 100L);

        UploadCourse uploadCourse = UploadCourse.builder()
            .title("t").introduction("소개").thumbnailImageS3Key("default-upload-course-thumbnail.png")
            .travelCourse(travelCourse).user(owner).location("경주")
            .build();

        given(uploadCourseRepository.findWithTravelCourseAndKeywords(uploadCourseId))
            .willReturn(Optional.of(uploadCourse));
        given(userService.getCurrentUserId()).willReturn(ownerId);
        given(myCourseService.getDaySchedulesWithPlaces(100L)).willReturn(List.of());

        // when
        uploadCourseService.deleteUploadCourse(uploadCourseId);

        // then
        verify(eventPublisher, never()).publishEvent(any(UploadCourseImagesCleanupEvent.class));
        verify(eventPublisher).publishEvent(any(UploadCourseDeletedEvent.class));
    }

    @Test
    @DisplayName("삭제된 코스가 캐시된 인기 랭킹에 있는 테마만 골라서 evict하고, 없는 테마는 건드리지 않는다")
    void invalidateDeletedUploadCourseCaches_EvictsOnlyMatchingThemeCache() {
        // given
        Long uploadCourseId = 42L;
        Cache popularCoursesCache = mock(Cache.class);
        given(cacheManager.getCache("popularCourses")).willReturn(popularCoursesCache);
        // ALL 캐시에는 삭제된 코스가 포함되어 있고, FOOD 캐시에는 포함되어 있지 않다.
        // 나머지 mood 테마 캐시는 stub하지 않아 mock 기본값(null)이 반환되며,
        // 이는 "해당 테마는 캐시 미스"로 취급되어 evict 대상에서 자연히 제외된다.
        given(popularCoursesCache.get(eq("ALL"), eq(List.class)))
            .willReturn(List.of(42L, 7L, 8L, 9L, 10L));
        given(popularCoursesCache.get(eq("FOOD"), eq(List.class)))
            .willReturn(List.of(1L, 2L, 3L, 4L, 5L));

        // when
        uploadCourseService.invalidateDeletedUploadCourseCaches(
            new UploadCourseDeletedEvent(uploadCourseId));

        // then
        verify(popularCoursesCache).evict("ALL");
        verify(popularCoursesCache, never()).evict("FOOD");
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
