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
import backend.yourtrip.domain.mycourse.entity.myCourse.CourseParticipant;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.CourseRole;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.place.PlaceImage;
import backend.yourtrip.domain.mycourse.mapper.CourseParticipantMapper;
import backend.yourtrip.domain.mycourse.mapper.DayScheduleMapper;
import backend.yourtrip.domain.mycourse.mapper.MyCourseMapper;
import backend.yourtrip.domain.mycourse.mapper.PlaceMapper;
import backend.yourtrip.domain.mycourse.repository.CourseParticipantRepository;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.MyCourseRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceImageRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
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
    private final GeminiService geminiService;

    private final ObjectMapper objectMapper;

    private final MyCourseRepository myCourseRepository;
    private final CourseParticipantRepository courseParticipantRepository;
    private final DayScheduleRepository dayScheduleRepository;
    private final PlaceRepository placeRepository;
    private final PlaceImageRepository placeImageRepository;
    private final UploadCourseRepository uploadCourseRepository;
    private final KakaoLocalClient kakaoLocalClient;

    @Override
    @Transactional
    public MyCourseCreateResponse saveCourse(MyCourseCreateRequest request) {
        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        //코스 생성
        MyCourse myCourse = MyCourseMapper.toEntity(request);
        MyCourse savedCourse = myCourseRepository.save(myCourse);

        //코스 참여자 생성
        courseParticipantRepository.save(
            CourseParticipantMapper.toEntityWithOwner(user, myCourse)
        );

        //일차 생성
        int days = Period.between(request.startDate(), request.endDate()).getDays() + 1;
        for (int i = 1; i <= days; i++) {
            dayScheduleRepository.save(new DaySchedule(myCourse, i));
        }

        return MyCourseMapper.toCreateResponse(savedCourse);
    }

    @Override
    @Transactional(readOnly = true)
    public MyCourseListResponse getMyCourseList() {
        Long userId = userService.getCurrentUserId();
        // 해당 유저가 참여 중인 CourseParticipant 목록 조회 (course updatedAt 순)
        List<CourseParticipant> courseParticipants = courseParticipantRepository.findByUserOrderByCourseUpdatedAtDesc(
            userService.getUser(userId));

        List<MyCourseListItemResponse> listItems = courseParticipants.stream()
            .map(CourseParticipant::getCourse)
            .map(MyCourseMapper::toListItemResponse)
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
    public MyCourse getMyCourseById(Long courseId) {
        return myCourseRepository.findById(courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));
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

        List<PlaceImageResponse> imageIdAndUrls = daySchedule.getPlaces().stream()
            .flatMap(place -> place.getPlaceImages().stream()
                .map(placeImage -> new PlaceImageResponse(
                    place.getId(),
                    placeImage.getId(),
                    s3Service.getPresignedUrl(placeImage.getPlaceImageS3Key())
                ))
            )
            .toList();

        return DayScheduleMapper.toDayScheduleResponse(daySchedule, imageIdAndUrls);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DayScheduleResponse> getAllDaySchedulesByCourse(Long courseId) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userService.getCurrentUserId());

        List<DaySchedule> daySchedules = getDaySchedulesWithPlaces(courseId);

        return daySchedules.stream()
            .map(daySchedule -> {
                List<PlaceImageResponse> imageIdAndUrls = daySchedule.getPlaces().stream()
                    .flatMap(place -> place.getPlaceImages().stream()
                        .map(placeImage -> new PlaceImageResponse(
                            place.getId(),
                            placeImage.getId(),
                            s3Service.getPresignedUrl(placeImage.getPlaceImageS3Key())
                        ))
                    )
                    .toList();

                return DayScheduleMapper.toDayScheduleResponse(daySchedule, imageIdAndUrls);
            })
            .toList();
    }

    private void checkExistCourse(Long courseId) {
        if (!myCourseRepository.existsById(courseId)) {
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
            placeImageS3Key = s3Service.uploadFile(placeImage).key();
        } catch (IOException e) {
            throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
        }

        PlaceImage savedPlaceImage = placeImageRepository.save(
            new PlaceImage(place, placeImageS3Key));

        return new PlaceImageCreateResponse(savedPlaceImage.getId(),
            s3Service.getPresignedUrl(placeImageS3Key));
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

        MyCourse myCourse = myCourseRepository.findCourseWithDaySchedule(courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));

        CourseRole role = courseParticipantRepository.findRole(userId,
                courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.ROLE_NOT_SPECIFY));

        return MyCourseMapper.toDetailResponse(myCourse, role);
    }

    private void checkOwnedCourse(Long courseId, Long userId) {
        if (!courseParticipantRepository.existsByUser_IdAndCourse_Id(userId, courseId)) {
            throw new BusinessException(MyCourseErrorCode.NOT_OWNED_COURSE);
        }
    }

    @Override
    @Transactional
    public CourseForkResponse forkCourse(Long uploadCourseId) {
        UploadCourse uploadCourse = uploadCourseRepository.findWithMyCourseById(uploadCourseId)
            .orElseThrow(
                () -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        Long userId = userService.getCurrentUserId();

        if (uploadCourse.getUser().getId().equals(userId)) {
            throw new BusinessException(MyCourseErrorCode.CANNOT_FORK_OWNED_COURSE);
        }

        MyCourse originalMyCourse = uploadCourse.getMyCourse();

        uploadCourse.increaseForkCount();

        MyCourse copyMyCourse = MyCourseMapper.toCopyEntity(uploadCourse.getMyCourse());
        MyCourse savedCourse = myCourseRepository.save(copyMyCourse);

        //코스 참여자 생성
        User user = userService.getUser(userId);
        courseParticipantRepository.save(
            CourseParticipantMapper.toEntityWithOwner(user, copyMyCourse)
        );

        //일차별 일정 복사
        int days =
            Period.between(copyMyCourse.getStartDate(), copyMyCourse.getEndDate()).getDays() + 1;
        for (int i = 1; i <= days; i++) {
            DaySchedule copiedDaySchedule = new DaySchedule(copyMyCourse, i);
            dayScheduleRepository.save(copiedDaySchedule);

            originalMyCourse.getDaySchedules().get(i - 1).getPlaces().forEach(originalPlace -> {
                // 장소 복사
                Place copiedPlace = PlaceMapper.toCopyEntity(originalPlace, copiedDaySchedule);
                copiedDaySchedule.getPlaces().add(copiedPlace);

                // 장소 이미지 복사
                originalPlace.getPlaceImages().forEach(originalImage -> {
                    PlaceImage copiedImage = new PlaceImage(
                        copiedPlace,
                        originalImage.getPlaceImageS3Key()
                    );
                    copiedPlace.getPlaceImages().add(copiedImage);
                });
            });
        }

        return new CourseForkResponse(savedCourse.getId());
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

        //myCourse 생성
        MyCourse myCourse = myCourseRepository.save(
            MyCourseMapper.toAICourseEntity(request, courseDto));

        //courseParticipant 생성
        User user = userService.getUser(userService.getCurrentUserId());
        courseParticipantRepository.save(CourseParticipantMapper.toEntityWithOwner(user, myCourse));

        //daySchedule, place 생성
        for (GeminiCourseDto.DayScheduleDto dayScheduleDto : courseDto.daySchedules()) {
            DaySchedule daySchedule = dayScheduleRepository.save(
                new DaySchedule(myCourse, dayScheduleDto.day()));
            myCourse.getDaySchedules().add(daySchedule);

            //각 place 저장
            for (GeminiCourseDto.PlaceDto placeDto : dayScheduleDto.places()) {
                Place place = placeRepository.save(
                    PlaceMapper.toEntityFromGeminiDto(placeDto, daySchedule));

                updatePlaceFromKakao(request, placeDto, place);

                daySchedule.getPlaces().add(place);
            }
        }

        return new AICourseCreateResponse(myCourse.getId());
    }

    private void updatePlaceFromKakao(AICourseCreateRequest request, PlaceDto placeDto,
        Place place) {
        Document doc = kakaoLocalClient.findBestPlace(placeDto.placeName(),
            request.location(), placeDto.placeLocation());

        if (doc == null) {
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
