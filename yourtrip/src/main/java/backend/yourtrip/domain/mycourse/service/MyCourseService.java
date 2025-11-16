package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceStartTimeCreateResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import java.time.LocalTime;
import java.util.List;

public interface MyCourseService {

    MyCourseCreateResponse saveCourse(MyCourseCreateRequest request);

    PlaceCreateResponse savePlace(Long courseId, Long dayId, PlaceCreateRequest request);

    MyCourseDetailResponse getMyCourseDetail(Long courseId);

    MyCourseListResponse getMyCourseList();

    MyCourse getMyCourseById(Long courseId);

    List<DaySchedule> getDaySchedulesWithPlaces(Long courseId);

    DayScheduleResponse getPlaceListByDay(Long courseId, Long dayId);

    PlaceStartTimeCreateResponse addPlaceTime(Long courseId, Long dayId, Long placeId,
        LocalTime startTime);
}
