package backend.yourtrip.domain.uploadcourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.uploadcourse.dto.cache.CourseListItemCacheItem;
import backend.yourtrip.domain.uploadcourse.dto.request.DayScheduleUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseUpdateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.RedisCallback;
import backend.yourtrip.global.redis.RedisDistributedLock;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class UploadCourseServiceImplTest {

    @Mock
    private UploadCourseRepository uploadCourseRepository;

    @Mock
    private MyCourseService myCourseService;

    @Mock
    private UploadCourseDetailReader uploadCourseDetailReader;

    @Mock
    private UploadCoursePopularReader uploadCoursePopularReader;

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
    private RedisDistributedLock redisDistributedLock;

    @Mock
    private RedisTemplate<String, Object> cacheValueRedisTemplate;

    // mock이 아니라 실제 ObjectMapper를 쓴다. 서비스의 cacheSerializer()가 이 인스턴스로
    // Jackson2JsonRedisSerializer를 만드는데, mock이면 역직렬화가 항상 null을 반환해
    // "캐시 히트" 테스트가 거짓 통과한다(히트한 항목이 null로 담겼다가 응답 조립의
    // filter(Objects::nonNull)에서 사라지는데도 예외 없이 빈 목록이 나온다).
    // CourseListItemCacheItem에는 java.time 필드가 없어 기본 설정으로 충분하다.
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private UploadCourseServiceImpl uploadCourseService;

    /**
     * 캐시 직렬화기는 @PostConstruct에서 만들어지는데, Mockito의 @InjectMocks는 생성자만 호출하고
     * 생명주기 콜백은 타지 않는다. 초기화를 빠뜨리면 직렬화기가 null이라 캐시 읽기가
     * fail-open(NPE를 삼키고 미스 처리)으로 빠져 히트 테스트가 조용히 거짓 통과하므로, 여기서
     * 명시적으로 불러준다.
     * <p>
     * 이전에는 여기서 RedisTemplate 두 개를 필드명으로 명시 주입하기도 했다. 제네릭이 소거되면
     * Mockito의 생성자 주입에 둘 다 같은 타입으로 보여 한쪽 mock이 양쪽에 주입됐기 때문이다.
     * 분산 락이 RedisDistributedLock으로 분리되면서 서비스가 받는 RedisTemplate이 하나만 남아
     * 모호성 자체가 사라졌으므로, 그 우회 코드는 제거했다.
     */
    @BeforeEach
    void initCacheSerializers() {
        ReflectionTestUtils.invokeMethod(uploadCourseService, "initCacheSerializers");
    }

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

    // ==========================
    //  인기 코스 조회 — 트랜잭션 밖 캐시 경로
    // ==========================

    @Test
    @DisplayName("랭킹·아이템 캐시가 모두 히트하면 DB 조회를 담당하는 Reader와 리포지토리를 전혀 호출하지 않는다")
    void getPopularCourses_AllCacheHit_DoesNotTouchDatabase() {
        // given
        givenRankingCacheHit("ALL", List.of(1L, 2L));
        givenItemCacheReturns(
            serialize(cacheItem(1L, "코스1", "thumb-1.png")),
            serialize(cacheItem(2L, "코스2", "thumb-2.png"))
        );
        given(cloudFrontService.getPublicUrl("thumb-1.png")).willReturn("https://cdn/thumb-1.png");
        given(cloudFrontService.getPublicUrl("thumb-2.png")).willReturn("https://cdn/thumb-2.png");

        // when
        UploadCourseListResponse response = uploadCourseService.getPopularCourses(null);

        // then — 캐시 히트 경로가 DB 커넥션을 전혀 쓰지 않는 것이 이 구조의 핵심이다
        verifyNoInteractions(uploadCoursePopularReader, uploadCourseRepository);
        // 랭킹이 히트했으면 분산 락을 시도할 이유도 없다
        verifyNoInteractions(redisDistributedLock);
        assertThat(response.uploadCourses()).hasSize(2);
        assertThat(response.uploadCourses().get(0).thumbnailImageUrl())
            .isEqualTo("https://cdn/thumb-1.png");
    }

    @Test
    @DisplayName("랭킹 캐시 미스 시 락을 잡은 요청만 Reader로 DB를 읽고 결과를 캐시에 저장한 뒤 락을 해제한다")
    void getPopularCourses_RankingCacheMiss_LockAcquired_DelegatesToReader() {
        // given
        Cache popularCoursesCache = givenRankingCacheHit("ALL", null);
        givenLockAcquisitionReturns(true);
        given(uploadCoursePopularReader.readPopularCourseIds(null)).willReturn(List.of(3L));
        givenItemCacheReturns(serialize(cacheItem(3L, "코스3", null)));

        // when
        uploadCourseService.getPopularCourses(null);

        // then
        verify(uploadCoursePopularReader).readPopularCourseIds(null);
        verify(popularCoursesCache).put("ALL", List.of(3L));
        // 락 해제(compare-and-delete Lua)까지 반드시 수행돼야 한다
        verify(redisDistributedLock).release(ArgumentMatchers.anyString(), ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("락 획득이 Redis 오류로 실패하면 재시도 없이 즉시 DB로 폴백하고 캐시에 저장하지 않는다")
    void getPopularCourses_LockAcquisitionFailsWithRedisError_FallsBackImmediately() {
        // given — Redis 예외를 삼켜 null로 바꾸는 건 RedisDistributedLock의 책임이므로(그 동작은
        // RedisDistributedLockTest가 검증한다), 여기서는 서비스가 보는 null 3-state만 재현한다
        Cache popularCoursesCache = givenRankingCacheHit("ALL", null);
        givenLockAcquisitionReturns(null);
        given(uploadCoursePopularReader.readPopularCourseIds(null)).willReturn(List.of(4L));
        givenItemCacheReturns(serialize(cacheItem(4L, "코스4", null)));

        // when — 재시도 루프(10 * 100ms)를 타면 1초가 걸리므로 시간으로 경로를 판별한다
        assertTimeoutPreemptively(java.time.Duration.ofMillis(700),
            () -> uploadCourseService.getPopularCourses(null));

        // then
        verify(uploadCoursePopularReader, times(1)).readPopularCourseIds(null);
        verify(popularCoursesCache, never()).put(ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    @DisplayName("아이템 캐시가 일부만 히트하면 누락된 ID만 DB에서 읽고 랭킹 순서를 그대로 복원한다")
    void getPopularCourses_PartialItemCacheMiss_ReadsOnlyMissingIds() {
        // given — 랭킹은 [1, 2, 3]인데 아이템 캐시에는 2번만 있다
        givenRankingCacheHit("ALL", List.of(1L, 2L, 3L));
        givenItemCacheReturns(null, serialize(cacheItem(2L, "코스2", null)), null);
        given(uploadCoursePopularReader.readCourseListItems(List.of(1L, 3L)))
            .willReturn(List.of(cacheItem(1L, "코스1", null), cacheItem(3L, "코스3", null)));

        // when
        UploadCourseListResponse response = uploadCourseService.getPopularCourses(null);

        // then
        verify(uploadCoursePopularReader).readCourseListItems(List.of(1L, 3L));
        // IN 조회는 순서를 보장하지 않으므로 랭킹 순서로 재정렬돼야 한다
        assertThat(response.uploadCourses())
            .extracting(item -> item.uploadCourseId())
            .containsExactly(1L, 2L, 3L);
    }

    @Test
    @DisplayName("mood 카테고리가 아닌 테마로 인기 코스를 조회하면 캐시나 DB에 손대기 전에 예외가 발생한다")
    void getPopularCourses_InvalidTheme_ThrowsBeforeTouchingCacheOrDatabase() {
        // when & then
        assertThatThrownBy(() -> uploadCourseService.getPopularCourses(KeywordType.FAMILY))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", UploadCourseErrorCode.INVALID_THEME_TYPE);

        verifyNoInteractions(cacheManager, uploadCoursePopularReader, uploadCourseRepository);
    }

    @Test
    @DisplayName("랭킹 조회와 아이템 조회 사이에 삭제된 코스는 응답에서 조용히 제외된다")
    void getPopularCourses_CourseDeletedBetweenQueries_ExcludedFromResponse() {
        // given — 랭킹에는 99번이 남아 있지만 아이템 조회 시점에는 이미 삭제돼 반환되지 않는다.
        // 트랜잭션을 분리하면 두 조회 사이의 창이 넓어지므로 이 동작을 고정해 둔다.
        givenRankingCacheHit("ALL", List.of(1L, 99L));
        givenItemCacheReturns(serialize(cacheItem(1L, "코스1", null)), null);
        given(uploadCoursePopularReader.readCourseListItems(List.of(99L))).willReturn(List.of());

        // when
        UploadCourseListResponse response = uploadCourseService.getPopularCourses(null);

        // then
        assertThat(response.uploadCourses())
            .extracting(item -> item.uploadCourseId())
            .containsExactly(1L);
    }

    @Test
    @DisplayName("캐시 값이 null로 역직렬화되면 히트로 오인하지 않고 DB에서 다시 읽는다")
    void getPopularCourses_DeserializedNull_TreatedAsCacheMiss() {
        // given — 리터럴 "null"이 저장돼 있으면 역직렬화가 예외 없이 null을 반환한다.
        // 이를 map에 그대로 담으면 containsKey가 true라 미스로 잡히지 않고, 최종
        // filter(Objects::nonNull)에서 사라져 해당 코스만 목록에서 누락된다.
        givenRankingCacheHit("ALL", List.of(5L));
        givenItemCacheReturns("null".getBytes(StandardCharsets.UTF_8));
        given(uploadCoursePopularReader.readCourseListItems(List.of(5L)))
            .willReturn(List.of(cacheItem(5L, "코스5", null)));

        // when
        UploadCourseListResponse response = uploadCourseService.getPopularCourses(null);

        // then
        verify(uploadCoursePopularReader).readCourseListItems(List.of(5L));
        assertThat(response.uploadCourses())
            .extracting(item -> item.uploadCourseId())
            .containsExactly(5L);
    }

    @Test
    @DisplayName("락 획득에 실패해도 재시도 중 승자가 캐시를 채우면 DB를 읽지 않고 그 값을 쓴다")
    void getPopularCourses_LockNotAcquired_CacheFilledDuringRetry_UsesCachedValue() {
        // given — 락은 다른 요청이 보유 중이고, 재시도 2회째에 승자가 캐시를 채운 상황
        Cache popularCoursesCache = mock(Cache.class);
        given(cacheManager.getCache("popularCourses")).willReturn(popularCoursesCache);
        given(popularCoursesCache.get(eq("ALL"), eq(List.class)))
            .willReturn(null)             // 최초 조회 — 미스라서 락 경로로 들어간다
            .willReturn(null)             // 재시도 1회차 — 아직 승자가 채우기 전
            .willReturn(List.of(5L));     // 재시도 2회차 — 승자가 채움
        givenLockAcquisitionReturns(false);
        givenItemCacheReturns(serialize(cacheItem(5L, "코스5", null)));

        // when
        UploadCourseListResponse response = uploadCourseService.getPopularCourses(null);

        // then — 이 재시도가 존재하는 이유 자체가 "패자들이 각자 DB로 직행하는 것"을 막기 위해서다.
        // 여기서 Reader가 불리면 콜드 스타트 스탬피드 방지가 무의미해진다.
        verifyNoInteractions(uploadCoursePopularReader);
        // 승자가 이미 채워둔 값이므로 패자가 다시 쓸 이유가 없다
        verify(popularCoursesCache, never()).put(ArgumentMatchers.any(), ArgumentMatchers.any());
        // 락을 잡지 못했으면 남의 락을 해제해서는 안 된다
        verify(redisDistributedLock, never()).release(ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString());
        // 최초 1회 + 재시도 2회 = 3회. 값을 얻은 즉시 빠져나와 남은 8회를 낭비하지 않는다
        verify(popularCoursesCache, times(3)).get(eq("ALL"), eq(List.class));
        assertThat(response.uploadCourses())
            .extracting(item -> item.uploadCourseId())
            .containsExactly(5L);
    }

    @Test
    @DisplayName("락 획득에 실패하고 재시도를 모두 소진하면 DB로 폴백하되 캐시에는 저장하지 않는다")
    void getPopularCourses_LockNotAcquired_RetriesExhausted_FallsBackWithoutCaching() {
        // given — 승자가 죽었거나 예상보다 오래 걸려 재시도 예산(10 * 100ms) 안에 캐시가 안 채워진 상황
        Cache popularCoursesCache = givenRankingCacheHit("ALL", null);
        givenLockAcquisitionReturns(false);
        given(uploadCoursePopularReader.readPopularCourseIds(null)).willReturn(List.of(6L));
        givenItemCacheReturns(serialize(cacheItem(6L, "코스6", null)));

        // when
        UploadCourseListResponse response = uploadCourseService.getPopularCourses(null);

        // then — 최초 1회 + 재시도 10회 = 11회. 타이밍에 기대지 않고 호출 횟수로 루프 완주를 증명한다
        verify(popularCoursesCache, times(11)).get(eq("ALL"), eq(List.class));
        // 응답은 정상적으로 나와야 한다(fail-open) — 기다리다 실패했다고 사용자에게 에러를 줄 순 없다
        verify(uploadCoursePopularReader, times(1)).readPopularCourseIds(null);
        assertThat(response.uploadCourses())
            .extracting(item -> item.uploadCourseId())
            .containsExactly(6L);
        // 락을 못 잡은 쪽이 캐시를 쓰면 승자의 결과를 덮어쓸 수 있으므로 저장하지 않는다
        verify(popularCoursesCache, never()).put(ArgumentMatchers.any(), ArgumentMatchers.any());
        verify(redisDistributedLock, never()).release(ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString());
    }

    /**
     * 랭킹 캐시가 주어진 값을 반환하도록 stub한다. cachedIds가 null이면 캐시 미스다.
     */
    private Cache givenRankingCacheHit(String cacheKey, List<Long> cachedIds) {
        Cache popularCoursesCache = mock(Cache.class);
        given(cacheManager.getCache("popularCourses")).willReturn(popularCoursesCache);
        given(popularCoursesCache.get(eq(cacheKey), eq(List.class))).willReturn(cachedIds);
        return popularCoursesCache;
    }

    /**
     * 락 획득 결과를 stub한다. TRUE(획득), FALSE(다른 요청이 보유 중), null(Redis 오류)의
     * 3-state를 그대로 넘겨 서비스의 세 분기를 각각 재현할 수 있다.
     */
    private void givenLockAcquisitionReturns(Boolean acquired) {
        given(redisDistributedLock.tryAcquire(ArgumentMatchers.anyString(),
            ArgumentMatchers.anyString(), ArgumentMatchers.any(java.time.Duration.class)))
            .willReturn(acquired);
    }

    /**
     * 아이템 캐시 MGET 결과를 stub한다. 인자 순서가 조회 ID 순서와 대응하며, null은 그 ID의 미스다.
     */
    private void givenItemCacheReturns(byte[]... rawValues) {
        given(cacheValueRedisTemplate.execute(
            ArgumentMatchers.<RedisCallback<List<byte[]>>>any()))
            .willReturn(java.util.Arrays.asList(rawValues));
    }

    private CourseListItemCacheItem cacheItem(Long id, String title, String thumbnailS3Key) {
        return new CourseListItemCacheItem(id, title, "경주", thumbnailS3Key, 0, List.of());
    }

    private byte[] serialize(CourseListItemCacheItem item) {
        return new Jackson2JsonRedisSerializer<>(new ObjectMapper(),
            CourseListItemCacheItem.class).serialize(item);
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
