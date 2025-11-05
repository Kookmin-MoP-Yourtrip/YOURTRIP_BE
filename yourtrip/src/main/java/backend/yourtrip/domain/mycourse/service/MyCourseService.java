package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.request.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.request.PlaceCreateRequest;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseDetailResponse;
import backend.yourtrip.domain.mycourse.dto.response.MyCourseListResponse;
import backend.yourtrip.domain.mycourse.dto.response.PlaceCreateResponse;

public interface MyCourseService {

    MyCourseCreateResponse saveCourse(MyCourseCreateRequest request);

    PlaceCreateResponse savePlace(Long courseId, int day, PlaceCreateRequest request);

    MyCourseDetailResponse getMyCourseDetail(Long courseId);

    MyCourseListResponse getMyCourseList();
}
