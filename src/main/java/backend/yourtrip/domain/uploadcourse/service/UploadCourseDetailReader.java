package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// UploadCourseServiceImpl.getDetail이 캐시 미스일 때의 DB 조회만 짧은 트랜잭션으로 묶기
// 위해 분리한 협력 빈. Spring @Transactional은 프록시 기반이라 같은 클래스 안에서의
// this.메서드() 호출(self-invocation)에는 적용되지 않아, 이 부분만 별도 빈으로 뺐다
// (TASK-PRESIGN-BOTTLENECK-FIX.md 0단계). 캐시 조회(Redis)와 URL 조립(CloudFront)은
// 이 트랜잭션 밖에서 실행된다 — 특히 캐시 히트 경로는 이 빈을 아예 호출하지 않으므로
// DB 커넥션을 전혀 쓰지 않는다.
@Service
@RequiredArgsConstructor
public class UploadCourseDetailReader {

    private final UploadCourseRepository uploadCourseRepository;
    private final MyCourseService myCourseService;

    @Transactional(readOnly = true)
    public UploadCourseDetailReadResult read(Long uploadCourseId) {
        UploadCourse uploadCourse = uploadCourseRepository.findWithTravelCourseAndKeywords(uploadCourseId)
            .orElseThrow(() -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        List<DaySchedule> daySchedules = myCourseService.getDaySchedulesWithPlaces(
            uploadCourse.getTravelCourse().getId());

        // Place.placeImages는 @Fetch(FetchMode.SUBSELECT)라 첫 접근 시 지연 로딩된다.
        // open-in-view가 꺼진 상태에서 이 트랜잭션이 끝난 뒤(URL 조립 단계에서) 접근하면
        // LazyInitializationException이 나므로, 트랜잭션이 살아있는 지금 강제로 초기화해 둔다.
        daySchedules.forEach(daySchedule ->
            daySchedule.getPlaces().forEach(place -> place.getPlaceImages().size()));

        return new UploadCourseDetailReadResult(uploadCourse, daySchedules);
    }

    public record UploadCourseDetailReadResult(UploadCourse uploadCourse, List<DaySchedule> daySchedules) {

    }
}
