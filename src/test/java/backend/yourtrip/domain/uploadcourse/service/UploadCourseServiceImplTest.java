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
import backend.yourtrip.global.config.BenchmarkProperties;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
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
    private RedisTemplate<String, String> redisTemplate;

    @Mock
    private RedisTemplate<String, Object> cacheValueRedisTemplate;

    // mock이 아니라 실제 ObjectMapper를 쓴다. 서비스의 cacheSerializer()가 이 인스턴스로
    // Jackson2JsonRedisSerializer를 만드는데, mock이면 역직렬화가 항상 null을 반환해
    // "캐시 히트" 테스트가 거짓 통과한다(히트한 항목이 null로 담겼다가 응답 조립의
    // filter(Objects::nonNull)에서 사라지는데도 예외 없이 빈 목록이 나온다).
    // CourseListItemCacheItem에는 java.time 필드가 없어 기본 설정으로 충분하다.
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    // mock이 아니라 실제 인스턴스를 쓴다. 이 클래스의 기본값(ENABLED + SEPARATED)이 곧 운영
    // 동작이라, 아래 캐시 경로 테스트들이 검증하려는 대상이 정확히 이 기본값 상태다.
    // mock이면 boolean 기본 반환값(false)에 우연히 기대어 통과하는 셈이 되고, 나중에 게이트
    // 조건이 바뀌어도 테스트가 그대로 통과해버린다.
    @Spy
    private BenchmarkProperties benchmarkProperties = new BenchmarkProperties();

    @InjectMocks
    private UploadCourseServiceImpl uploadCourseService;

    /**
     * 서비스 생성자는 RedisTemplate을 두 개(redisTemplate, cacheValueRedisTemplate) 받는데,
     * 제네릭이 소거되면 Mockito의 생성자 주입에는 둘 다 같은 타입으로 보여 한쪽 mock이 양쪽에
     * 주입된다(실제로 그렇게 동작하는 것을 확인했다). 필드명으로 명시 주입해 바로잡는다.
     */
    @BeforeEach
    void injectRedisTemplatesByName() {
        ReflectionTestUtils.setField(uploadCourseService, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(uploadCourseService, "cacheValueRedisTemplate",
            cacheValueRedisTemplate);
        // 캐시 직렬화기는 @PostConstruct에서 만들어지는데, Mockito의 @InjectMocks는 생성자만
        // 호출하고 생명주기 콜백은 타지 않는다. 초기화를 빠뜨리면 직렬화기가 null이라
        // 캐시 읽기가 fail-open(NPE를 삼키고 미스 처리)으로 빠져 히트 테스트가 조용히 거짓
        // 통과하므로, 여기서 명시적으로 불러준다.
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
        verifyNoInteractions(redisTemplate);
        assertThat(response.uploadCourses()).hasSize(2);
        assertThat(response.uploadCourses().get(0).thumbnailImageUrl())
            .isEqualTo("https://cdn/thumb-1.png");
    }

    @Test
    @DisplayName("랭킹 캐시 미스 시 락을 잡은 요청만 Reader로 DB를 읽고 결과를 캐시에 저장한 뒤 락을 해제한다")
    void getPopularCourses_RankingCacheMiss_LockAcquired_DelegatesToReader() {
        // given
        Cache popularCoursesCache = givenRankingCacheHit("ALL", null);
        givenLockAcquired(true);
        given(uploadCoursePopularReader.readPopularCourseIds(null)).willReturn(List.of(3L));
        givenItemCacheReturns(serialize(cacheItem(3L, "코스3", null)));

        // when
        uploadCourseService.getPopularCourses(null);

        // then
        verify(uploadCoursePopularReader).readPopularCourseIds(null);
        verify(popularCoursesCache).put("ALL", List.of(3L));
        // 락 해제(compare-and-delete Lua)까지 반드시 수행돼야 한다
        verify(redisTemplate).execute(ArgumentMatchers.<RedisScript<Long>>any(),
            ArgumentMatchers.<List<String>>any(), ArgumentMatchers.<Object>any());
    }

    @Test
    @DisplayName("락 획득이 Redis 예외로 실패하면 재시도 없이 즉시 DB로 폴백하고 캐시에 저장하지 않는다")
    void getPopularCourses_LockAcquisitionThrows_FallsBackImmediately() {
        // given
        Cache popularCoursesCache = givenRankingCacheHit("ALL", null);
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.any(java.time.Duration.class)))
            .willThrow(new RuntimeException("redis down"));
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

    // ==========================
    //  벤치마크 토글 (캐싱 효과 부하테스트의 A0 arm)
    // ==========================

    @Test
    @DisplayName("캐시를 끄면 인기 코스 조회가 캐시와 분산 락을 모두 건너뛰고 DB로 직행한다")
    void getPopularCourses_CacheDisabled_BypassesCacheAndLock() {
        // given
        benchmarkProperties.setUploadCourseCache(
            BenchmarkProperties.UploadCourseCacheMode.DISABLED);
        given(uploadCoursePopularReader.readPopularCourseIds(null)).willReturn(List.of(7L));
        given(uploadCoursePopularReader.readCourseListItems(List.of(7L)))
            .willReturn(List.of(cacheItem(7L, "코스7", null)));

        // when
        UploadCourseListResponse response = uploadCourseService.getPopularCourses(null);

        // then — 캐시를 아예 건드리지 않는다(읽기도 쓰기도)
        verifyNoInteractions(cacheManager, cacheValueRedisTemplate);
        // 그리고 분산 락도 시도하지 않는다. 이게 중요한 이유: 락 경로에 들어가면 락을 못 잡은
        // 요청이 "절대 채워지지 않을 캐시"를 최대 1초(10 * 100ms) 기다리며 Thread.sleep하고,
        // 그 sleep이 톰캣 워커 스레드를 잡아 포화 VU 판정을 오염시킨다.
        verifyNoInteractions(redisTemplate);
        verify(uploadCoursePopularReader).readPopularCourseIds(null);
        assertThat(response.uploadCourses())
            .extracting(item -> item.uploadCourseId())
            .containsExactly(7L);
    }

    @Test
    @DisplayName("캐시를 끄면 상세 조회가 캐시를 건너뛰고 매번 DB를 읽는다")
    void getDetail_CacheDisabled_AlwaysReadsFromDatabase() {
        // given
        benchmarkProperties.setUploadCourseCache(
            BenchmarkProperties.UploadCourseCacheMode.DISABLED);
        Long uploadCourseId = 1L;

        User owner = User.builder().email("owner@test.com").password("pass").nickname("owner")
            .build();
        setEntityId(owner, 10L);
        TravelCourse travelCourse = TravelCourse.builder()
            .user(owner).title("경주 여행").location("경주")
            .startDate(LocalDate.now()).endDate(LocalDate.now())
            .type(TravelCourseType.UPLOADED).build();
        setEntityId(travelCourse, 100L);
        UploadCourse uploadCourse = UploadCourse.builder()
            .title("경주 여행").introduction("소개").travelCourse(travelCourse)
            .user(owner).location("경주").build();

        given(uploadCourseDetailReader.read(uploadCourseId)).willReturn(
            new UploadCourseDetailReader.UploadCourseDetailReadResult(uploadCourse, List.of()));

        // when
        uploadCourseService.getDetail(uploadCourseId, "u5");

        // then — 상세 캐시 읽기(GET)도 쓰기(SET)도 일어나지 않아야 한다
        verifyNoInteractions(cacheValueRedisTemplate);
        verify(uploadCourseDetailReader).read(uploadCourseId);
        // 조회수 경로는 캐시 토글과 무관하게 항상 동작한다(두 arm의 공통 통제 변수)
        verify(uploadCourseViewCountService).incrementViewCountIfNotDuplicate(uploadCourseId, "u5");
    }

    @Test
    @DisplayName("캐시를 끄면 랭킹 갱신(refresh-ahead)이 랭킹 쿼리를 실행하지 않는다")
    void refreshAllPopularCoursesCache_CacheDisabled_DoesNothing() {
        // given — 갱신할 캐시가 없는데 그냥 두면 스케줄러 주기(10분)마다 랭킹 쿼리 8회가
        // 측정 구간 한복판에서 실행되어, 캐시가 꺼진 arm에만 없던 DB 부하가 얹힌다.
        benchmarkProperties.setUploadCourseCache(
            BenchmarkProperties.UploadCourseCacheMode.DISABLED);

        // when
        uploadCourseService.refreshAllPopularCoursesCache();

        // then
        verifyNoInteractions(uploadCoursePopularReader, cacheManager, redisTemplate);
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

    private void givenLockAcquired(boolean acquired) {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
            ArgumentMatchers.any(java.time.Duration.class))).willReturn(acquired);
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
