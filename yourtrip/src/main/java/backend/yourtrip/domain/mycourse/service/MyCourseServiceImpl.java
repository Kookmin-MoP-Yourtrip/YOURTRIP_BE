package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListItemResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceStartTimeCreateResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.myCourse.CourseParticipant;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.CourseRole;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.mapper.CourseParticipantMapper;
import backend.yourtrip.domain.mycourse.mapper.DayScheduleMapper;
import backend.yourtrip.domain.mycourse.mapper.MyCourseMapper;
import backend.yourtrip.domain.mycourse.mapper.PlaceMapper;
import backend.yourtrip.domain.mycourse.repository.CourseParticipantRepository;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.MyCourseRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import backend.yourtrip.global.s3.service.S3Service;
import java.time.LocalTime;
import java.time.Period;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MyCourseServiceImpl implements MyCourseService {

    private final MyCourseRepository myCourseRepository;
    private final CourseParticipantRepository courseParticipantRepository;
    private final DayScheduleRepository dayScheduleRepository;
    private final PlaceRepository placeRepository;
    private final UserService userService;
    private final S3Service s3Service;

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

        return new MyCourseCreateResponse(savedCourse.getId(), "코스 생성 완료");
    }

    @Override
    @Transactional
    public PlaceCreateResponse savePlace(Long courseId, Long dayId, PlaceCreateRequest request) {
        checkExistCourse(courseId);
        Long userId = userService.getCurrentUserId();

        DaySchedule daySchedule = dayScheduleRepository.findByIdAndUserId(userId,
                dayId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.DAY_SCHEDULE_NOT_FOUND));

        Place savedPlace = placeRepository.save(PlaceMapper.toEntity(request, daySchedule));

        return new PlaceCreateResponse(savedPlace.getId(), "장소 등록 완료");
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

        DaySchedule daySchedule = dayScheduleRepository.findByIdWithPlaces(courseId,
                dayId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.DAY_SCHEDULE_NOT_FOUND));

        List<String> s3Keys = daySchedule.getPlaces().stream()
            .flatMap(place -> place.getPlaceImages().stream())
            .map(placeImage -> placeImage.getPlaceImageS3Key())
            .toList();

        List<String> presignedUrls = s3Keys.stream()
            .map(s3Service::getPresignedUrl)
            .toList();

        return DayScheduleMapper.toDayScheduleResponse(daySchedule, presignedUrls);
    }

    private void checkExistCourse(Long courseId) {
        if (!myCourseRepository.existsById(courseId)) {
            throw new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND);
        }
    }

    private void checkExistDaySchedule(Long dayId) {
        if (!dayScheduleRepository.existsById(dayId)) {
            throw new BusinessException(MyCourseErrorCode.DAY_SCHEDULE_NOT_FOUND);
        }
    }

    private void checkExistPlace(Long placeId) {
        if (!placeRepository.existsById(placeId)) {
            throw new BusinessException(MyCourseErrorCode.PLACE_NOT_FOUND);
        }
    }

    @Override
    @Transactional
    public PlaceStartTimeCreateResponse addPlaceTime(Long courseId, Long dayId, Long placeId,
        LocalTime startTime) {
        checkExistCourse(courseId);
        checkExistDaySchedule(dayId);
        checkExistPlace(placeId);

        Place place = placeRepository.findById(placeId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.PLACE_NOT_FOUND));

        place.setStartTime(startTime);

        return new PlaceStartTimeCreateResponse(startTime, "장소에 시간 추가 완료");
    }


    @Override
    @Transactional(readOnly = true)
    public MyCourseDetailResponse getMyCourseDetail(Long courseId) {
        Long userId = userService.getCurrentUserId();

        MyCourse myCourse = myCourseRepository.findCourseWithDaySchedule(courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));

        CourseRole role = courseParticipantRepository.findRole(userId,
                courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.ROLE_NOT_SPECIFY));

        return MyCourseMapper.toDetailResponse(myCourse, role);
    }


}
