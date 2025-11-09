package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListItemResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.entity.CourseParticipant;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.dayschedule.Place;
import backend.yourtrip.domain.mycourse.entity.enums.CourseRole;
import backend.yourtrip.domain.mycourse.mapper.CourseParticipantMapper;
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
import java.time.Period;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MyCourseServiceImpl implements MyCourseService {

    private final MyCourseRepository myCourseRepository;
    private final CourseParticipantRepository courseParticipantRepository;
    private final DayScheduleRepository dayScheduleRepository;
    private final PlaceRepository placeRepository;
    private final UserService userService;

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

        return new MyCourseCreateResponse(savedCourse.getId(), "코스 등록 완료");
    }

    @Override
    @Transactional
    public PlaceCreateResponse savePlace(Long courseId, int day, PlaceCreateRequest request) {
//        course.updateBudget(request.budget()); //총예산 업데이트 (추후 확장)
        Long userId = userService.getCurrentUserId();

        DaySchedule daySchedule = dayScheduleRepository.findOwnedByCourseIdAndDay(courseId, userId,
                day)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_OR_DAY_NOT_FOUND));

        Place savedPlace = placeRepository.save(PlaceMapper.toEntity(request, daySchedule));

        return new PlaceCreateResponse(savedPlace.getId(), "장소 등록 완료");
    }

    @Override
    @Transactional(readOnly = true)
    public MyCourseDetailResponse getMyCourseDetail(Long courseId) {
        Long userId = userService.getCurrentUserId();

        CourseRole role = courseParticipantRepository.findRole(userId,
                courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.ROLE_NOT_SPECIFY));

        MyCourse myCourse = myCourseRepository.findCourseWithDaySchedule(courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));

        return MyCourseMapper.toDetailResponse(myCourse, role);
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

}
