package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.mycourse.dto.response.AICourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.CourseForkResponse;
import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListItemResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceMemoUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceStartTimeUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceUpdateResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.mycourse.event.MyCourseImagesCleanupEvent;
import backend.yourtrip.domain.mycourse.mapper.DayScheduleMapper;
import backend.yourtrip.domain.mycourse.mapper.PlaceMapper;
import backend.yourtrip.domain.mycourse.mapper.TravelCourseMapper;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceImageRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.mycourse.repository.TravelCourseRepository;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import backend.yourtrip.global.cloudfront.service.CloudFrontService;
import backend.yourtrip.global.cloudfront.service.CloudFrontService.CourseSignature;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto.PlaceDto;
import backend.yourtrip.global.gemini.service.GeminiService;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import backend.yourtrip.global.s3.service.S3Service;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
@Slf4j
public class MyCourseServiceImpl implements MyCourseService {

    private final UserService userService;
    private final S3Service s3Service;
    private final CloudFrontService cloudFrontService;
    private final GeminiService geminiService;

    private final ObjectMapper objectMapper;

    private final TravelCourseRepository travelCourseRepository;
    private final DayScheduleRepository dayScheduleRepository;
    private final PlaceRepository placeRepository;
    private final PlaceImageRepository placeImageRepository;
    private final UploadCourseRepository uploadCourseRepository;
    private final KakaoLocalClient kakaoLocalClient;
    private final MyCourseDetailReader myCourseDetailReader;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public MyCourseCreateResponse saveCourse(MyCourseCreateRequest request) {
        User user = userService.getUser(userService.getCurrentUserId());

        //코스 생성
        TravelCourse travelCourse = TravelCourseMapper.toEntity(request, user);
        TravelCourse savedCourse = travelCourseRepository.save(travelCourse);

        //일차 생성
        int days = (int) ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;
        for (int i = 1; i <= days; i++) {
            dayScheduleRepository.save(new DaySchedule(travelCourse, i));
        }

        return TravelCourseMapper.toCreateResponse(savedCourse);
    }

    @Override
    @Transactional(readOnly = true)
    public MyCourseListResponse getMyCourseList() {
        Long userId = userService.getCurrentUserId();
        // 업로드 코스 뒤에 숨은 사본(UPLOADED 타입)은 목록에서 제외
        List<TravelCourse> courses = travelCourseRepository.findByUser_IdAndTypeNotOrderByUpdatedAtDesc(
            userId, TravelCourseType.UPLOADED);

        List<MyCourseListItemResponse> listItems = courses.stream()
            .map(TravelCourseMapper::toListItemResponse)
            .toList();

        return new MyCourseListResponse(listItems);
    }


    @Override
    @Transactional
    public PlaceCreateResponse savePlace(Long courseId, Long dayId, PlaceCreateRequest request) {
        checkExistCourse(courseId);
        Long userId = userService.getCurrentUserId();
        checkOwnedCourse(courseId, userId);

        DaySchedule daySchedule = dayScheduleRepository.findByIdAndUserId(userId, courseId,
                dayId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.DAY_SCHEDULE_NOT_FOUND));

        Place savedPlace = placeRepository.save(PlaceMapper.toEntity(request, daySchedule));

        return PlaceMapper.toCreateResponse(savedPlace);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DaySchedule> getDaySchedulesWithPlaces(Long courseId) {
        return dayScheduleRepository.findDaySchedulesWithPlaces(courseId);
    }

    @Override
    public DayScheduleResponse getPlaceListByDay(Long courseId, Long dayId) {
        Long userId = userService.getCurrentUserId();
        // 존재·소유권 확인 + DB 조회는 MyCourseDetailReader의 짧은 트랜잭션 안에서 끝난다.
        // 아래 서명은 트랜잭션 밖에서 실행돼 HikariCP 커넥션이 서명 시간만큼 묶이지 않는다
        // (PRESIGN-BOTTLENECK-FIX.md 0단계 — self-invocation 문제로 같은 클래스 안
        // 메서드 분리로는 트랜잭션을 못 좁혀 별도 협력 빈으로 뺐다).
        DaySchedule daySchedule = myCourseDetailReader.readDaySchedule(courseId, dayId, userId);

        // MyCourseController의 "/{courseId}/days/{dayId}/places" — 작성자만 볼 수 있는
        // 비공개 조회이므로 Signed URL을 발급한다.

        // 이미지가 몇 장이든 서명은 코스당 1회다 — custom policy의 Resource를
        // private/{courseId}/* 와일드카드로 잡아 그 결과 쿼리스트링을 모든 이미지 URL에
        // 재사용한다(PRESIGN-BOTTLENECK-FIX.md 1단계). 서명은 반드시 위 소유권 검증
        // 이후에 해야 한다 — 순서가 뒤집히면 인가 실패 요청에도 서명이 발급된다.
        //
        // 전용 스레드풀/세마포어 게이트를 거치지 않고 직접 호출한다 — 서명이 요청당 1회로
        // 상수화된 뒤로는 fan-out이 없어 격리할 이유가 사라졌다(요청 스레드에서 서명 1회를
        // 직접 실행해도 부하는 CPU 코어를 공유하는 다른 요청 처리 비용과 자릿수가 같다).
        // Run D/D2 EC2 실측에서 executor 큐 거부(cloudfront_signing_rejected_total)가 두
        // arm 모두 0으로 수렴해 이 인프라가 발동하지 않는 안전망임을 확인한 뒤 제거했다
        // (docs/tasks/connection-pool-bottleneck/stage1/run-d-signature-once.md 판정 5).
        CourseSignature signature = cloudFrontService.signCourseScope(courseId);

        // daySchedule은 이미 페치 조인되어 있어 트랜잭션이 끝난 뒤에도 안전하게 읽을 수 있다
        // (LazyInitializationException은 아직 초기화 안 된 프록시에 접근할 때만 발생한다).
        List<PlaceImageResponse> imageIdAndUrls = daySchedule.getPlaces().stream()
            .flatMap(place -> place.getPlaceImages().stream()
                .map(placeImage -> new PlaceImageResponse(
                    place.getId(),
                    placeImage.getId(),
                    signature.signedUrlFor(placeImage.getPlaceImageS3Key()))))
            .toList();

        return DayScheduleMapper.toDayScheduleResponse(daySchedule, imageIdAndUrls);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayScheduleResponse> getAllDaySchedulesByOwnedCourse(Long courseId) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());

        List<DaySchedule> daySchedules = getDaySchedulesWithPlaces(courseId);

        // MyCourseController에는 노출되지 않는 내부 헬퍼 — UploadCourseServiceImpl.createUploadCourse()가
        // 방금 만든 업로드용 hidden copy(TravelCourseType.UPLOADED, 공개 key)의 응답 조립에만 쓴다.
        // 그래서 여기서 만드는 이미지는 이미 공개 콘텐츠라 서명 없는 URL을 발급해야 한다.
        return daySchedules.stream()
            .map(daySchedule -> {
                List<PlaceImageResponse> imageIdAndUrls = daySchedule.getPlaces().stream()
                    .flatMap(place -> place.getPlaceImages().stream()
                        .map(placeImage -> new PlaceImageResponse(
                            place.getId(),
                            placeImage.getId(),
                            cloudFrontService.getPublicUrl(placeImage.getPlaceImageS3Key())
                        ))
                    )
                    .toList();

                return DayScheduleMapper.toDayScheduleResponse(daySchedule, imageIdAndUrls);
            })
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayScheduleResponse> getAllDaySchedulesByCourse(Long courseId) {
        checkExistCourse(courseId);

        List<DaySchedule> daySchedules = getDaySchedulesWithPlaces(courseId);

        // MyCourseController에는 노출되지 않는 내부 헬퍼 — UploadCourseServiceImpl.updateUploadCourse()가
        // 업로드 코스 자체(공개 콘텐츠)의 갱신 응답 조립에만 쓴다. 서명 없는 URL을 발급한다.
        return daySchedules.stream()
            .map(daySchedule -> {
                List<PlaceImageResponse> imageIdAndUrls = daySchedule.getPlaces().stream()
                    .flatMap(place -> place.getPlaceImages().stream()
                        .map(placeImage -> new PlaceImageResponse(
                            place.getId(),
                            placeImage.getId(),
                            cloudFrontService.getPublicUrl(placeImage.getPlaceImageS3Key())
                        ))
                    )
                    .toList();

                return DayScheduleMapper.toDayScheduleResponse(daySchedule, imageIdAndUrls);
            })
            .toList();
    }

    private void checkExistCourse(Long courseId) {
        if (!travelCourseRepository.existsById(courseId)) {
            throw new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND);
        }
    }

    private void checkExistDaySchedule(Long dayId, Long courseId) {
        if (!dayScheduleRepository.existsByIdAndCourse_Id(dayId, courseId)) {
            throw new BusinessException(MyCourseErrorCode.DAY_SCHEDULE_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public PlaceStartTimeUpdateResponse updatePlaceTime(Long courseId, Long dayId, Long placeId,
        LocalTime startTime) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());
        checkExistDaySchedule(dayId, courseId);

        getPlaceByIdAndDayId(placeId, dayId).setStartTime(startTime);

        return new PlaceStartTimeUpdateResponse(placeId, startTime);
    }

    private Place getPlaceByIdAndDayId(Long placeId, Long dayId) {
        return placeRepository.findByIdAndDaySchedule_Id(placeId, dayId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.PLACE_NOT_FOUND));
    }

    @Override
    @Transactional
    public PlaceMemoUpdateResponse updatePlaceMemo(Long courseId, Long dayId, Long placeId,
        String memo) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());
        checkExistDaySchedule(dayId, courseId);

        getPlaceByIdAndDayId(placeId, dayId).setMemo(memo);

        return new PlaceMemoUpdateResponse(placeId, memo);
    }

    @Override
    @Transactional
    public PlaceImageCreateResponse addPlaceImage(Long courseId, Long dayId, Long placeId,
        MultipartFile placeImage) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());
        checkExistDaySchedule(dayId, courseId);
        Place place = getPlaceByIdAndDayId(placeId, dayId);

        String placeImageS3Key;
        try {
            placeImageS3Key = s3Service.uploadPrivateFile(placeImage, courseId).key();
        } catch (IOException e) {
            throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
        }

        PlaceImage savedPlaceImage = placeImageRepository.save(
            new PlaceImage(place, placeImageS3Key));

        return new PlaceImageCreateResponse(savedPlaceImage.getId(),
            cloudFrontService.signCourseScope(courseId).signedUrlFor(placeImageS3Key));
    }

    @Override
    @Transactional
    public PlaceUpdateResponse updatePlace(Long courseId, Long dayId, Long placeId,
        PlaceUpdateRequest request) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());
        checkExistDaySchedule(dayId, courseId);
        Place place = getPlaceByIdAndDayId(placeId, dayId);

        place.updatePlace(request);

        return PlaceMapper.toUpdateResponse(place);
    }

    @Override
    @Transactional
    public void deletePlaceImage(Long courseId, Long dayId, Long placeId, Long imageId) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());
        checkExistDaySchedule(dayId, courseId);
        Place place = getPlaceByIdAndDayId(placeId, dayId);

        PlaceImage placeImage = placeImageRepository.findByIdAndPlace_Id(imageId, placeId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.PLACE_IMAGE_NOT_FOUND));

        s3Service.deleteFile(placeImage.getPlaceImageS3Key());

        place.getPlaceImages().remove(placeImage);
    }

    @Override
    @Transactional
    public void deletePlace(Long courseId, Long dayId, Long placeId) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());
        checkExistDaySchedule(dayId, courseId);
        Place place = getPlaceByIdAndDayId(placeId, dayId);

        placeRepository.delete(place);

        // S3에서 장소 사진들 삭제
        place.getPlaceImages().forEach(placeImage ->
            s3Service.deleteFile(placeImage.getPlaceImageS3Key())
        );
    }

    /**
     * uploadCourse는 원본 mycourse와 완전히 독립된 데이터로 취급한다(업로드 시점에 딥카피된
     * 별도 사본이므로). 따라서 원본이 이미 업로드된 상태(uploaded=true)여도 이 삭제를 막지
     * 않는다 — 원본을 지워도 이미 공개된 uploadCourse는 그대로 유지된다.
     */
    @Override
    @Transactional
    public void deleteCourse(Long courseId) {
        checkExistCourse(courseId);
        Long userId = userService.getCurrentUserId();
        checkOwnedCourse(courseId, userId);

        TravelCourse travelCourse = travelCourseRepository.findCourseWithDaySchedule(courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));

        // day/place/image를 전부 로드해 S3 key를 미리 수집한다(삭제 후에는 엔티티에 접근할 수 없음).
        // dayScheduleRepository로 조회하지만 같은 트랜잭션(영속성 컨텍스트) 내에서 travelCourse가
        // 이미 로드해둔 daySchedules와 동일한 관리 엔티티를 공유하므로(1차 캐시), 아래 delete()
        // 호출 시 cascade가 정상적으로 이 place/image까지 전부 타게 된다.
        List<DaySchedule> daySchedules = getDaySchedulesWithPlaces(courseId);
        List<String> imageS3Keys = daySchedules.stream()
            .flatMap(ds -> ds.getPlaces().stream())
            .flatMap(place -> place.getPlaceImages().stream())
            .map(PlaceImage::getPlaceImageS3Key)
            .toList();

        travelCourseRepository.delete(travelCourse);

        if (!imageS3Keys.isEmpty()) {
            eventPublisher.publishEvent(new MyCourseImagesCleanupEvent(imageS3Keys));
        }
    }

    @Override
    @Transactional
    public void deleteHiddenUploadCopy(TravelCourse hiddenCopy) {
        travelCourseRepository.delete(hiddenCopy);
    }

    /**
     * 커밋 확정 후 S3 이미지 정리를 요청 스레드에서 분리해 코스 삭제 API의 응답 시간이 이미지
     * 개수에 비례해 늘어나지 않도록 한다. 실패한 key는 WARN 로그만 남기고 나머지는 계속
     * 진행한다(fail-open) — 이 코드베이스 전반의 캐시/외부 I/O 실패 처리 관례와 동일하다.
     * private이 아닌 이유는 refreshUploadCourseCaches와 동일(AOP 프록시 제약).
     */
    @Async("courseImageCleanupExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void cleanupDeletedCourseImages(MyCourseImagesCleanupEvent event) {
        for (String key : event.imageS3Keys()) {
            try {
                s3Service.deleteFile(key);
            } catch (Exception e) {
                log.warn("코스 삭제 후 S3 이미지 정리 실패. key={}", key, e);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public MyCourseDetailResponse getMyCourseDetail(Long courseId) {
        Long userId = userService.getCurrentUserId();
        checkOwnedCourse(courseId, userId);

        TravelCourse travelCourse = travelCourseRepository.findCourseWithDaySchedule(courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));

        return TravelCourseMapper.toDetailResponse(travelCourse);
    }

    private void checkOwnedCourse(Long courseId, Long userId) {
        if (!travelCourseRepository.existsByIdAndUser_Id(courseId, userId)) {
            throw new BusinessException(MyCourseErrorCode.NOT_OWNED_COURSE);
        }
    }

    /**
     * fork/업로드 사본 생성에 공용으로 쓰이는 딥카피 헬퍼.
     * TravelCourse 본체 + 참여자(소유자) + 일차별 DaySchedule/Place/PlaceImage를 전부 새 PK로 복제한다.
     * PlaceImage는 S3 오브젝트 자체를 실제로 복사한다(key 문자열만 복사하면 원본과 사본이 물리적으로
     * 같은 오브젝트를 가리켜 "업로드 코스=공개/내 코스=비공개" 경계가 무너지기 때문 — type이
     * UPLOADED(비공개→공개)인지 FORK(공개→비공개)인지에 따라 대상 가시성을 정한다).
     */
    private TravelCourse copyMyCourseWithSchedule(TravelCourse original, User user,
        TravelCourseType type) {
        TravelCourse copyTravelCourse = TravelCourseMapper.toCopyEntity(original, type, user);
        TravelCourse savedCourse = travelCourseRepository.save(copyTravelCourse);

        int days = (int) ChronoUnit.DAYS.between(
            copyTravelCourse.getStartDate(), copyTravelCourse.getEndDate()) + 1;
        for (int i = 1; i <= days; i++) {
            DaySchedule copiedDaySchedule = new DaySchedule(copyTravelCourse, i);
            dayScheduleRepository.save(copiedDaySchedule);

            original.getDaySchedules().get(i - 1).getPlaces().forEach(originalPlace -> {
                // 장소 복사
                Place copiedPlace = PlaceMapper.toCopyEntity(originalPlace, copiedDaySchedule);
                copiedDaySchedule.getPlaces().add(copiedPlace);

                // 장소 이미지 복사 — S3 오브젝트를 대상 가시성으로 실제 복사한 새 key를 사용한다.
                originalPlace.getPlaceImages().forEach(originalImage -> {
                    String copiedKey = switch (type) {
                        case UPLOADED -> s3Service.copyToPublic(originalImage.getPlaceImageS3Key());
                        // 사본 key는 원본 코스가 아니라 방금 채번된 사본 코스(savedCourse)의
                        // 스코프에 들어가야 한다 — 그래야 fork한 사용자가 자기 코스의 서명으로
                        // 이 이미지에 접근할 수 있다.
                        case FORK -> s3Service.copyToPrivate(
                            originalImage.getPlaceImageS3Key(), savedCourse.getId());
                        default -> throw new IllegalStateException(
                            "copyMyCourseWithSchedule은 UPLOADED/FORK 타입에서만 호출되어야 합니다: "
                                + type);
                    };

                    PlaceImage copiedImage = new PlaceImage(copiedPlace, copiedKey);
                    copiedPlace.getPlaceImages().add(copiedImage);
                });
            });
        }

        return savedCourse;
    }

    @Override
    @Transactional
    public CourseForkResponse forkCourse(Long uploadCourseId) {
        UploadCourse uploadCourse = uploadCourseRepository.findWithTravelCourseById(uploadCourseId)
            .orElseThrow(
                () -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        Long userId = userService.getCurrentUserId();

        if (uploadCourse.getUser().getId().equals(userId)) {
            throw new BusinessException(MyCourseErrorCode.CANNOT_FORK_OWNED_COURSE);
        }

        User user = userService.getUser(userId);

        uploadCourse.increaseForkCount();

        TravelCourse copyTravelCourse = copyMyCourseWithSchedule(uploadCourse.getTravelCourse(),
            user, TravelCourseType.FORK);

        return new CourseForkResponse(copyTravelCourse.getId());
    }

    @Override
    @Transactional
    public TravelCourse createHiddenUploadCopy(Long myCourseId) {
        checkExistCourse(myCourseId);
        Long userId = userService.getCurrentUserId();
        checkOwnedCourse(myCourseId, userId);

        TravelCourse original = travelCourseRepository.findCourseWithDaySchedule(myCourseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));

        if (original.isUploaded()) {
            throw new BusinessException(UploadCourseErrorCode.COURSE_ALREADY_UPLOAD);
        }

        User user = userService.getUser(userId);
        TravelCourse hiddenCopy = copyMyCourseWithSchedule(original, user,
            TravelCourseType.UPLOADED);

        original.markAsUploaded();

        return hiddenCopy;
    }

    @Transactional
    public AICourseCreateResponse createAICourse(AICourseCreateRequest request) {
        int days =
            (int) ChronoUnit.DAYS.between(request.startDate(), request.endDate()) + 1;

        //gemini 호출해서 json 문자열 받기
        String json = geminiService.generateAICourse(request.location(), days, request.keywords());
        log.info(json);

        //json -> dto 바이딩
        GeminiCourseDto courseDto;
        try {
            courseDto = objectMapper.readValue(json, GeminiCourseDto.class);
        } catch (JsonProcessingException e) {
            log.error("Gemini에서 받은 JSON 파싱 실패", e);
            throw new BusinessException(MyCourseErrorCode.JSON_TRANSFORMATION_FAILED);
        }

        //travelCourse 생성
        User user = userService.getUser(userService.getCurrentUserId());
        TravelCourse travelCourse = travelCourseRepository.save(
            TravelCourseMapper.toAICourseEntity(request, courseDto, user));

        //daySchedule, place 생성
        for (GeminiCourseDto.DayScheduleDto dayScheduleDto : courseDto.daySchedules()) {
            DaySchedule daySchedule = dayScheduleRepository.save(
                new DaySchedule(travelCourse, dayScheduleDto.day()));
            travelCourse.getDaySchedules().add(daySchedule);

            //각 place 저장
            for (GeminiCourseDto.PlaceDto placeDto : dayScheduleDto.places()) {
                Place place = placeRepository.save(
                    PlaceMapper.toEntityFromGeminiDto(placeDto, daySchedule));

                updatePlaceFromKakao(request, placeDto, place);

                daySchedule.getPlaces().add(place);
            }
        }

        return new AICourseCreateResponse(travelCourse.getId());
    }

    private void updatePlaceFromKakao(AICourseCreateRequest request, PlaceDto placeDto,
        Place place) {
        Document doc = kakaoLocalClient.findBestPlace(placeDto.placeName(),
            request.location());

        if (doc == null) { //적절한 장소가 카카오맵에 검색되지 않음
            return;
        }

        //placeName, placeLocation, placeUrl, longitude, latitude 업데이트
        String placeName = doc.place_name();
        String placeLocation = doc.road_address_name() != null && !doc.road_address_name().isBlank()
            ? doc.road_address_name()
            : doc.address_name();
        String placeUrl = doc.place_url();
        double longitude = Double.parseDouble(doc.x());
        double latitude = Double.parseDouble(doc.y());

        place.updateKakaoPlace(placeName, placeLocation, placeUrl, latitude, longitude);
    }
}
