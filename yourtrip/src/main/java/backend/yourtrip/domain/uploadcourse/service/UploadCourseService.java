package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.mycourse.entity.MyCourse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.entity.CourseKeyword;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.mapper.UploadCourseMapper;
import backend.yourtrip.domain.uploadcourse.repository.CourseKeywordRepository;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UploadCourseService {

    private final UploadCourseRepository uploadCourseRepository;
    private final CourseKeywordRepository courseKeywordRepository;
    private final MyCourseService myCourseService;
    private final UserService userService;

    @Transactional(readOnly = true)
    public CourseKeywordListResponse getCourseKeywordList() {
        return UploadCourseMapper.toKeywordListResponse();
    }

    @Transactional
    public UploadCourseCreateResponse createUploadCourse(UploadCourseCreateRequest request) {
        MyCourse myCourse = myCourseService.getMyCourseById(request.myCourseId());
        User user = userService.getUser(userService.getCurrentUserId());

        UploadCourse savedUploadCourse = uploadCourseRepository.save(
            UploadCourseMapper.toEntity(request, myCourse, user));

        //업로드 코스에 키워드 연동
        for (KeywordType keyword : request.keywords()) {
            savedUploadCourse.getKeywords().add(new CourseKeyword(savedUploadCourse, keyword));
        }

        return new UploadCourseCreateResponse(savedUploadCourse.getId(), "코스 업로드 완료");
    }

    @Transactional(readOnly = true)
    public UploadCourseDetailResponse getDetail(Long uploadCourseId) {
        UploadCourse uploadCourse = uploadCourseRepository.findUploadCourseWithMyCourseAndUserAndKeywords(
                uploadCourseId)
            .orElseThrow(
                () -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        List<DaySchedule> daySchedules = myCourseService.getDaySchedulesWithPlaces(
            uploadCourse.getMyCourse().getId());

        uploadCourse.increaseViewCount(); //조회 수 증가

        return UploadCourseMapper.toDetailResponse(uploadCourse, daySchedules);
    }

}
