package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.DayScheduleResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import java.time.LocalTime;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface MyCourseService {

    MyCourseCreateResponse saveCourse(MyCourseCreateRequest request);

    PlaceCreateResponse savePlace(Long courseId, Long dayId, PlaceCreateRequest request);

    MyCourseListResponse getMyCourseList();

    MyCourse getMyCourseById(Long courseId);

    List<DaySchedule> getDaySchedulesWithPlaces(Long courseId);

    DayScheduleResponse getPlaceListByDay(Long courseId, Long dayId);

    LocalTime addPlaceTime(Long courseId, Long dayId, Long placeId,
        LocalTime startTime);

    String addPlaceMemo(Long courseId, Long dayId, Long placeId, String memo);

    String addPlaceImage(Long courseId, Long dayId, Long placeId, MultipartFile placeImage);
}
