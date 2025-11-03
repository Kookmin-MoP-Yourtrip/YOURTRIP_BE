package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.dayschedule.Place;
import backend.yourtrip.domain.mycourse.mapper.CourseParticipantMapper;
import backend.yourtrip.domain.mycourse.mapper.MyCourseMapper;
import backend.yourtrip.domain.mycourse.mapper.PlaceMapper;
import backend.yourtrip.domain.mycourse.repository.CourseParticipantRepository;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.MyCourseRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.errorCode.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MyCourseService {

    private final MyCourseRepository myCourseRepository;
    private final CourseParticipantRepository courseParticipantRepository;
    private final DayScheduleRepository dayScheduleRepository;
    private final PlaceRepository placeRepository;
    private final UserService userService;

    //TODO: 코스 생성 시 daySchedule 생성
    //코스 생성
    @Transactional
    public MyCourseCreateResponse saveCourse(MyCourseCreateRequest request, Long userId) {
        User user = userService.getUser(userId);

        //코스 생성
        MyCourse myCourse = MyCourseMapper.toEntity(request);

        //일차 생성
        for (int i = 1; i <= request.days(); i++) {
            dayScheduleRepository.save(new DaySchedule(myCourse, i));
        }

        //코스 참여자 생성
        courseParticipantRepository.save(
            CourseParticipantMapper.toEntityWithOwner(user, myCourse)
        );
        MyCourse savedCourse = myCourseRepository.save(myCourse);

        //TODO: 이거 ID 잘 반환되나??
        return new MyCourseCreateResponse(savedCourse.getId(), "코스 등록 완료");
    }

    //장소 추가
    @Transactional
    public PlaceCreateResponse savePlace(Long courseId, int day, PlaceCreateRequest request,
        Long userId) {
        User user = userService.getUser(userId);
        MyCourse course = getCourseById(courseId);

        course.updateBudget(request.budget()); //총예산 업데이트

        DaySchedule daySchedule = dayScheduleRepository.findByCourseAndDay(course, day)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.PLACE_NOT_FOUND));

        Place savedPlace = placeRepository.save(PlaceMapper.toEntity(request, daySchedule));

        return new PlaceCreateResponse(savedPlace.getId(), "장소 등록 완료");
    }

    public MyCourse getCourseById(Long courseId) {
        return myCourseRepository.findById(courseId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND));
    }

}
