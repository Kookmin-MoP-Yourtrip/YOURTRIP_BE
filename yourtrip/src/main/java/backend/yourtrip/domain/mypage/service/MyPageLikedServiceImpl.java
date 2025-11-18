package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.mypage.dto.response.LikedCourseResponse;
import backend.yourtrip.domain.mypage.dto.response.LikedFeedResponse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.mycourse.service.MyCourseService;
import backend.yourtrip.domain.heart.repository.FeedHeartRepository;
import backend.yourtrip.domain.heart.repository.UploadCourseHeartRepository;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageLikedServiceImpl implements MyPageLikedService {

    private final UploadCourseHeartRepository uploadCourseHeartRepository;
    private final FeedHeartRepository feedHeartRepository;
    private final UploadCourseRepository uploadCourseRepository;

    private final MyCourseService myCourseService;
    private final UserService userService;

    @Override
    @Transactional(readOnly = true)
    public List<LikedCourseResponse> getLikedCourses() {
        Long userId = userService.getCurrentUserId();
        return uploadCourseHeartRepository.findLikedCourses(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<LikedFeedResponse> getLikedFeeds() {
        Long userId = userService.getCurrentUserId();
        return feedHeartRepository.findLikedFeeds(userId);
    }

    @Override
    @Transactional
    public void forkCourse(Long uploadCourseId) {
        Long userId = userService.getCurrentUserId();

        UploadCourse uploadCourse = uploadCourseRepository.findById(uploadCourseId)
            .orElseThrow(() -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

        // 업로드 코스를 나의 코스로 복사 (추후 MyCourseServiceImpl에서 구현)
        myCourseService.forkCourse(userId, uploadCourse);
    }
}