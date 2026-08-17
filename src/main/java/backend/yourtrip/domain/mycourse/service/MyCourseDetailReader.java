package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.TravelCourseRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// MyCourseServiceImpl.getPlaceListByDay가 존재확인·소유권확인·DB조회만 짧은 트랜잭션으로
// 묶기 위해 분리한 협력 빈. Spring @Transactional은 프록시 기반이라 같은 클래스 안에서의
// this.메서드() 호출(self-invocation)에는 적용되지 않아, 이 부분만 별도 빈으로 뺐다
// (PRESIGN-BOTTLENECK-FIX.md 0단계). checkExistCourse/checkOwnedCourse는
// MyCourseServiceImpl의 다른 미변경 메서드도 계속 쓰므로 여기서는 복제해서 둔다.
@Service
@RequiredArgsConstructor
public class MyCourseDetailReader {

    private final TravelCourseRepository travelCourseRepository;
    private final DayScheduleRepository dayScheduleRepository;

    @Transactional(readOnly = true)
    public DaySchedule readDaySchedule(Long courseId, Long dayId, Long userId) {
        checkExistCourse(courseId);
        checkOwnedCourse(courseId, userId);

        DaySchedule daySchedule = dayScheduleRepository.findByIdWithPlaces(courseId, dayId)
            .orElseThrow(() -> new BusinessException(MyCourseErrorCode.DAY_SCHEDULE_NOT_FOUND));

        // Place.placeImages는 @Fetch(FetchMode.SUBSELECT)라 첫 접근 시 지연 로딩된다.
        // open-in-view가 꺼진 상태에서 이 트랜잭션이 끝난 뒤(서명 단계에서) 접근하면
        // LazyInitializationException이 나므로, 트랜잭션이 살아있는 지금 강제로 초기화해 둔다.
        daySchedule.getPlaces().forEach(place -> place.getPlaceImages().size());

        return daySchedule;
    }

    private void checkExistCourse(Long courseId) {
        if (!travelCourseRepository.existsById(courseId)) {
            throw new BusinessException(MyCourseErrorCode.COURSE_NOT_FOUND);
        }
    }

    private void checkOwnedCourse(Long courseId, Long userId) {
        if (!travelCourseRepository.existsByIdAndUser_Id(courseId, userId)) {
            throw new BusinessException(MyCourseErrorCode.NOT_OWNED_COURSE);
        }
    }
}
