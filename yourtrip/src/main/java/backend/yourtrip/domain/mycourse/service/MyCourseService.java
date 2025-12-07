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
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceImageCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceMemoUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceStartTimeUpdateResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceUpdateResponse;
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

    PlaceStartTimeUpdateResponse updatePlaceTime(Long courseId, Long dayId, Long placeId,
        LocalTime startTime);

    PlaceMemoUpdateResponse updatePlaceMemo(Long courseId, Long dayId, Long placeId, String memo);

    PlaceImageCreateResponse addPlaceImage(Long courseId, Long dayId, Long placeId,
        MultipartFile placeImage);

    PlaceUpdateResponse updatePlace(Long courseId, Long dayId, Long placeId,
        PlaceUpdateRequest request);

    void deletePlaceImage(Long courseId, Long dayId, Long placeId, Long imageId);

    void deletePlace(Long courseId, Long dayId, Long placeId);

    MyCourseDetailResponse getMyCourseDetail(Long courseId);

    CourseForkResponse forkCourse(Long uploadCourseId);

    List<DayScheduleResponse> getAllDaySchedulesByOwnedCourse(Long courseId);

    List<DayScheduleResponse> getAllDaySchedulesByCourse(Long courseId);

    AICourseCreateResponse createAICourse(AICourseCreateRequest request);
}