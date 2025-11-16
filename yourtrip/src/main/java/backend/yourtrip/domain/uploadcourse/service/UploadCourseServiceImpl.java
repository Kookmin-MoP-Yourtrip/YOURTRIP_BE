package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.uploadcourse.dto.request.UploadCourseCreateRequest;
import backend.yourtrip.domain.uploadcourse.dto.response.CourseKeywordListResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseCreateResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseDetailResponse;
import backend.yourtrip.domain.uploadcourse.dto.response.UploadCourseListResponse;
import backend.yourtrip.domain.uploadcourse.entity.CourseKeyword;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.entity.enums.UploadCourseSortType;
import backend.yourtrip.domain.uploadcourse.mapper.UploadCourseMapper;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import backend.yourtrip.global.s3.service.S3Service;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class UploadCourseServiceImpl implements UploadCourseService {

    private final UploadCourseRepository uploadCourseRepository;
    private final MyCourseService myCourseService;
    private final UserService userService;
    private final S3Service s3Service;

    @Override
    @Transactional(readOnly = true)
    public CourseKeywordListResponse getCourseKeywordList() {
        return UploadCourseMapper.toKeywordListResponse();
    }

    @Override
    @Transactional
    public UploadCourseCreateResponse createUploadCourse(UploadCourseCreateRequest request,
        MultipartFile thumbnailImage) {
        MyCourse myCourse = myCourseService.getMyCourseById(request.myCourseId());

        // 연동된 나의 코스가 이미 업로드됐을 때 예외 throw
        uploadCourseRepository.findByMyCourse(myCourse)
            .ifPresent(existing -> {
                throw new BusinessException(UploadCourseErrorCode.COURSE_ALREADY_UPLOAD);
            });

        User user = userService.getUser(userService.getCurrentUserId());

        String thumbnailS3Key;
        try {
            thumbnailS3Key = s3Service.uploadFile(thumbnailImage).key();
        } catch (IOException e) {
            throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
        }

        UploadCourse savedUploadCourse = uploadCourseRepository.save(
            UploadCourseMapper.toEntity(request, myCourse, user, thumbnailS3Key));

        //업로드 코스에 키워드 연동
        for (KeywordType keyword : request.keywords()) {
            savedUploadCourse.getKeywords().add(new CourseKeyword(savedUploadCourse, keyword));
        }

        return new UploadCourseCreateResponse(savedUploadCourse.getId(), "코스 업로드 완료");
    }

    @Override
    @Transactional
    public UploadCourseDetailResponse getDetail(Long uploadCourseId) {
        UploadCourse uploadCourse = uploadCourseRepository.findUploadCourseWithMyCourseAndUserAndKeywords(
                uploadCourseId)
            .orElseThrow(
                () -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        List<DaySchedule> daySchedules = myCourseService.getDaySchedulesWithPlaces(
            uploadCourse.getMyCourse().getId());

        uploadCourse.increaseViewCount(); //조회 수 증가

        getThumbnailAndProfileUrl urls = getGetThumbnailAndProfileUrl(
            uploadCourse);

        return UploadCourseMapper.toDetailResponse(uploadCourse, daySchedules, urls.thumbnailUrl,
            urls.profileUrl);
    }

    @Override
    @Transactional(readOnly = true)
    public UploadCourseListResponse getAllList(UploadCourseSortType sortType) {
        List<UploadCourse> uploadCourses = switch (sortType) {
            case NEW -> uploadCourseRepository.findAllWithUserOrerByCreatedAtDesc();
            case POPULAR -> uploadCourseRepository.findAllWithUserOrderByViewCountDesc();
//            default -> throw new BusinessException(UploadCourseErrorCode.INVALID_SORT_TYPE);
        };

        return new UploadCourseListResponse(uploadCourses.stream()
            .map(uploadCourse -> {
                getThumbnailAndProfileUrl urls = getGetThumbnailAndProfileUrl(uploadCourse);

                return UploadCourseMapper.toListItemResponse(uploadCourse, urls.thumbnailUrl,
                    urls.profileUrl);
            })
            .toList()
        );
    }

    private record getThumbnailAndProfileUrl(String thumbnailUrl, String profileUrl) {

    }

    private getThumbnailAndProfileUrl getGetThumbnailAndProfileUrl(UploadCourse uploadCourse) {
        String thumbnailUrl = null;
        if (uploadCourse.getThumbnailImageS3Key() != null) {
            thumbnailUrl = s3Service.getPresignedUrl(
                uploadCourse.getThumbnailImageS3Key());//썸네일 프리사인드 URL 생성
        }

        String profileUrl = null;
        if (uploadCourse.getUser().getProfileImageS3Key() != null) {
            profileUrl = s3Service.getPresignedUrl(
                uploadCourse.getUser().getProfileImageS3Key());
        }

        return new getThumbnailAndProfileUrl(thumbnailUrl, profileUrl);
    }

}
