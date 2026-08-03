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
import java.time.Period;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

    @Override
    @Transactional
    public MyCourseCreateResponse saveCourse(MyCourseCreateRequest request) {
        User user = userService.getUser(userService.getCurrentUserId());

        //코스 생성
        TravelCourse travelCourse = TravelCourseMapper.toEntity(request, user);
        TravelCourse savedCourse = travelCourseRepository.save(travelCourse);

        //일차 생성
        int days = Period.between(request.startDate(), request.endDate()).getDays() + 1;
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
    @Transactional(readOnly = true)
    public DayScheduleResponse getPlaceListByDay(Long courseId, Long dayId) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());

        DaySchedule daySchedule = dayScheduleRepository.findByIdWithPlaces(courseId,
                dayId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.DAY_SCHEDULE_NOT_FOUND));

        // MyCourseController의 "/{courseId}/days/{dayId}/places" — 작성자만 볼 수 있는
        // 비공개 조회이므로 Signed URL을 발급한다.
        List<PlaceImageResponse> imageIdAndUrls = daySchedule.getPlaces().stream()
            .flatMap(place -> place.getPlaceImages().stream()
                .map(placeImage -> new PlaceImageResponse(
                    place.getId(),
                    placeImage.getId(),
                    cloudFrontService.getSignedUrl(placeImage.getPlaceImageS3Key())
                ))
            )
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
            placeImageS3Key = s3Service.uploadPrivateFile(placeImage).key();
        } catch (IOException e) {
            throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
        }

        PlaceImage savedPlaceImage = placeImageRepository.save(
            new PlaceImage(place, placeImageS3Key));

        return new PlaceImageCreateResponse(savedPlaceImage.getId(),
            cloudFrontService.getSignedUrl(placeImageS3Key));
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

        int days =
            Period.between(copyTravelCourse.getStartDate(), copyTravelCourse.getEndDate())
                .getDays() + 1;
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
                        case FORK -> s3Service.copyToPrivate(originalImage.getPlaceImageS3Key());
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
            Period.between(request.startDate(), request.endDate()).getDays() + 1;

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
