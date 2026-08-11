package backend.yourtrip.domain.mycourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.mycourse.event.MyCourseImagesCleanupEvent;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceImageRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.mycourse.repository.TravelCourseRepository;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.cloudfront.config.CloudFrontExecutorConfig;
import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import backend.yourtrip.global.cloudfront.service.CloudFrontSigningGate;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.CloudFrontErrorCode;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import backend.yourtrip.global.gemini.service.GeminiService;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.s3.service.S3Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * getPlaceListByDay의 CloudFront Signed URL 병렬 발급 + 입장 제어 게이트(CloudFrontSigningGate)를
 * 검증한다. 게이트가 실제 executor로 서명을 병렬 실행하는지 증명해야 하므로, 게이트를 mock하지
 * 않고 permits=100(사실상 무제한)인 실제 인스턴스를 생성자에 직접 주입한다(@InjectMocks 미사용).
 * executor는 CloudFrontExecutorConfig.buildSigningExecutor로 만들어 프로덕션과 동일한 풀 정책을 쓴다.
 * 거부 경로(permit 소진, 큐 포화)는 CloudFrontSigningGateTest에서 별도로 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MyCourseServiceImplTest {

    @Mock
    private UserService userService;
    @Mock
    private S3Service s3Service;
    @Mock
    private CloudFrontService cloudFrontService;
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
    @Mock
    private MyCourseDetailReader myCourseDetailReader;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ThreadPoolTaskExecutor cloudFrontSigningExecutor;
    private CloudFrontSigningGate cloudFrontSigningGate;
    private MyCourseServiceImpl myCourseService;

    private static final Long OWNER_ID = 10L;
    private static final Long COURSE_ID = 1L;
    private static final Long DAY_ID = 100L;

    @BeforeEach
    void setUp() {
        // 프로덕션과 동일한 풀 정책(코어=최대=4, 큐 100, AbortPolicy)을 팩터리로 재사용한다.
        cloudFrontSigningExecutor = CloudFrontExecutorConfig.buildSigningExecutor(
            4, 100, new ThreadPoolExecutor.AbortPolicy());
        cloudFrontSigningExecutor.initialize(); // 테스트에는 Spring 컨테이너가 없어 직접 호출한다.

        // permits=100 → 사실상 무제한이라 이 테스트들에서는 게이트가 배압을 걸지 않는다.
        // 거부/데드라인 경로는 CloudFrontSigningGateTest에서 결정론적으로 별도 검증한다.
        cloudFrontSigningGate = new CloudFrontSigningGate(
            cloudFrontService, cloudFrontSigningExecutor,
            /* permits */ 100, /* acquireTimeoutMs */ 1000,
            /* maxKeysPerRequest */ 100, /* deadlineMs */ 5000,
            new SimpleMeterRegistry());

        myCourseService = new MyCourseServiceImpl(
            userService, s3Service, cloudFrontService, geminiService, objectMapper,
            travelCourseRepository, dayScheduleRepository, placeRepository,
            placeImageRepository, uploadCourseRepository, kakaoLocalClient,
            myCourseDetailReader, cloudFrontSigningGate, eventPublisher
        );
    }

    @AfterEach
    void tearDown() {
        cloudFrontSigningExecutor.shutdown();
    }

    @Test
    @DisplayName("병렬로 서명해도 이미지-장소 매핑과 응답 구조가 올바르다")
    void getPlaceListByDay_ParallelSigning_MapsImagesToCorrectPlaces() {
        // given
        DaySchedule daySchedule = daySchedule();
        Place place1 = place(daySchedule, 1L);
        Place place2 = place(daySchedule, 2L);
        addImage(place1, 11L, "private/p1-a.jpg");
        addImage(place1, 12L, "private/p1-b.jpg");
        addImage(place2, 21L, "private/p2-a.jpg");
        daySchedule.getPlaces().add(place1);
        daySchedule.getPlaces().add(place2);

        given(userService.getCurrentUserId()).willReturn(OWNER_ID);
        given(myCourseDetailReader.readDaySchedule(COURSE_ID, DAY_ID, OWNER_ID))
            .willReturn(daySchedule);
        given(cloudFrontService.getSignedUrl(anyString()))
            .willAnswer(inv -> "signed-" + inv.getArgument(0, String.class));

        // when
        DayScheduleResponse response = myCourseService.getPlaceListByDay(COURSE_ID, DAY_ID);

        // then
        assertThat(response.places()).hasSize(2);
        PlaceResponse resPlace1 = findPlace(response, 1L);
        PlaceResponse resPlace2 = findPlace(response, 2L);

        assertThat(resPlace1.placeImages())
            .extracting(PlaceImageResponse::placeImageUrl)
            .containsExactlyInAnyOrder("signed-private/p1-a.jpg", "signed-private/p1-b.jpg");
        assertThat(resPlace2.placeImages())
            .extracting(PlaceImageResponse::placeImageUrl)
            .containsExactly("signed-private/p2-a.jpg");
    }

    @Test
    @DisplayName("서명 작업이 커스텀 스레드풀에서 실제로 동시에 실행된다")
    void getPlaceListByDay_SigningRunsConcurrentlyOnDedicatedPool() throws Exception {
        // given: 이미지 4장, 풀 크기 4 — 4개의 서명 작업이 동시에 barrier에 도달해야만
        // 전부 통과할 수 있도록 해서, "다른 스레드에서 실행됐다"뿐 아니라 "진짜 병렬로 실행됐다"를
        // sleep 기반 타이밍 추정 없이 결정적으로 검증한다.
        DaySchedule daySchedule = daySchedule();
        Place place = place(daySchedule, 1L);
        addImage(place, 11L, "k1");
        addImage(place, 12L, "k2");
        addImage(place, 13L, "k3");
        addImage(place, 14L, "k4");
        daySchedule.getPlaces().add(place);

        given(userService.getCurrentUserId()).willReturn(OWNER_ID);
        given(myCourseDetailReader.readDaySchedule(COURSE_ID, DAY_ID, OWNER_ID))
            .willReturn(daySchedule);

        CyclicBarrier barrier = new CyclicBarrier(4);
        Set<String> threadNames = new CopyOnWriteArraySet<>();
        String callerThreadName = Thread.currentThread().getName();

        given(cloudFrontService.getSignedUrl(anyString())).willAnswer(inv -> {
            threadNames.add(Thread.currentThread().getName());
            // 4개 작업이 모두 이 지점에 도달해야만 통과된다 = 진짜 동시 실행 증명.
            // 순차 실행이거나 풀이 작으면 여기서 타임아웃(2초) 되어 실패로 드러난다.
            barrier.await(2, TimeUnit.SECONDS);
            return "signed-" + inv.getArgument(0, String.class);
        });

        // when
        DayScheduleResponse response = myCourseService.getPlaceListByDay(COURSE_ID, DAY_ID);

        // then
        assertThat(response.places().get(0).placeImages()).hasSize(4);
        assertThat(threadNames).hasSize(4);
        assertThat(threadNames).allMatch(name -> name.startsWith("cloudfront-signing-"));
        assertThat(threadNames).doesNotContain(callerThreadName);
    }

    @Test
    @DisplayName("일부 이미지 서명이 실패해도 나머지 이미지는 정상 반환된다")
    void getPlaceListByDay_PartialSigningFailure_ExcludesFailedImageOnly() {
        // given
        DaySchedule daySchedule = daySchedule();
        Place place = place(daySchedule, 1L);
        addImage(place, 11L, "ok-1");
        addImage(place, 12L, "broken");
        addImage(place, 13L, "ok-2");
        daySchedule.getPlaces().add(place);

        given(userService.getCurrentUserId()).willReturn(OWNER_ID);
        given(myCourseDetailReader.readDaySchedule(COURSE_ID, DAY_ID, OWNER_ID))
            .willReturn(daySchedule);
        given(cloudFrontService.getSignedUrl("broken"))
            .willThrow(new BusinessException(CloudFrontErrorCode.FAIL_GENERATE_SIGNED_URL));
        given(cloudFrontService.getSignedUrl("ok-1")).willReturn("signed-ok-1");
        given(cloudFrontService.getSignedUrl("ok-2")).willReturn("signed-ok-2");

        // when
        DayScheduleResponse response = myCourseService.getPlaceListByDay(COURSE_ID, DAY_ID);

        // then: 전체 요청은 실패하지 않고, 실패한 이미지만 제외된다.
        assertThat(response.places().get(0).placeImages())
            .extracting(PlaceImageResponse::placeImageUrl)
            .containsExactlyInAnyOrder("signed-ok-1", "signed-ok-2");
    }

    @Test
    @DisplayName("코스 소유자가 삭제하면 cascade delete되고, 장소 이미지가 있으면 S3 정리 이벤트가 발행된다")
    void deleteCourse_Success_PublishesImageCleanupEvent() {
        // given
        DaySchedule daySchedule = daySchedule();
        Place place = place(daySchedule, 1L);
        addImage(place, 11L, "k1");
        addImage(place, 12L, "k2");
        daySchedule.getPlaces().add(place);
        TravelCourse travelCourse = daySchedule.getCourse();

        given(travelCourseRepository.existsById(COURSE_ID)).willReturn(true);
        given(travelCourseRepository.existsByIdAndUser_Id(COURSE_ID, OWNER_ID)).willReturn(true);
        given(userService.getCurrentUserId()).willReturn(OWNER_ID);
        given(travelCourseRepository.findCourseWithDaySchedule(COURSE_ID))
            .willReturn(Optional.of(travelCourse));
        given(dayScheduleRepository.findDaySchedulesWithPlaces(COURSE_ID))
            .willReturn(List.of(daySchedule));

        // when
        myCourseService.deleteCourse(COURSE_ID);

        // then
        verify(travelCourseRepository).delete(travelCourse);
        ArgumentCaptor<MyCourseImagesCleanupEvent> captor =
            ArgumentCaptor.forClass(MyCourseImagesCleanupEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().imageS3Keys()).containsExactlyInAnyOrder("k1", "k2");
    }

    @Test
    @DisplayName("장소 이미지가 없는 코스를 삭제하면 S3 정리 이벤트를 발행하지 않는다")
    void deleteCourse_NoImages_DoesNotPublishCleanupEvent() {
        // given
        DaySchedule daySchedule = daySchedule();
        TravelCourse travelCourse = daySchedule.getCourse();

        given(travelCourseRepository.existsById(COURSE_ID)).willReturn(true);
        given(travelCourseRepository.existsByIdAndUser_Id(COURSE_ID, OWNER_ID)).willReturn(true);
        given(userService.getCurrentUserId()).willReturn(OWNER_ID);
        given(travelCourseRepository.findCourseWithDaySchedule(COURSE_ID))
            .willReturn(Optional.of(travelCourse));
        given(dayScheduleRepository.findDaySchedulesWithPlaces(COURSE_ID))
            .willReturn(List.of(daySchedule));

        // when
        myCourseService.deleteCourse(COURSE_ID);

        // then
        verify(travelCourseRepository).delete(travelCourse);
        verify(eventPublisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("존재하지 않는 코스를 삭제하려 하면 COURSE_NOT_FOUND 예외가 발생한다")
    void deleteCourse_CourseNotFound_ThrowsException() {
        // given
        given(travelCourseRepository.existsById(COURSE_ID)).willReturn(false);

        // when & then
        assertThatThrownBy(() -> myCourseService.deleteCourse(COURSE_ID))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", MyCourseErrorCode.COURSE_NOT_FOUND);
    }

    @Test
    @DisplayName("소유하지 않은 코스를 삭제하려 하면 NOT_OWNED_COURSE 예외가 발생한다")
    void deleteCourse_NotOwner_ThrowsException() {
        // given
        given(travelCourseRepository.existsById(COURSE_ID)).willReturn(true);
        given(travelCourseRepository.existsByIdAndUser_Id(COURSE_ID, OWNER_ID)).willReturn(false);
        given(userService.getCurrentUserId()).willReturn(OWNER_ID);

        // when & then
        assertThatThrownBy(() -> myCourseService.deleteCourse(COURSE_ID))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("errorCode", MyCourseErrorCode.NOT_OWNED_COURSE);
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
