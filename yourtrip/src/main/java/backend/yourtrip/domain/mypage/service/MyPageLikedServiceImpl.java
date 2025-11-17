package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.heart.entity.FeedHeart;
import backend.yourtrip.domain.heart.entity.UploadCourseHeart;
import backend.yourtrip.domain.heart.repository.FeedHeartRepository;
import backend.yourtrip.domain.heart.repository.UploadCourseHeartRepository;
import backend.yourtrip.domain.mypage.dto.response.LikedCourseResponse;
import backend.yourtrip.domain.mypage.dto.response.LikedFeedResponse;
import backend.yourtrip.domain.mycourse.entity.CourseParticipant;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.dayschedule.Place;
import backend.yourtrip.domain.mycourse.entity.enums.CourseRole;
import backend.yourtrip.domain.mycourse.entity.enums.MyCourseType;
import backend.yourtrip.domain.mycourse.repository.CourseParticipantRepository;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.MyCourseRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.LikedErrorCode;
import backend.yourtrip.global.exception.errorCode.UserErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MyPageLikedServiceImpl implements MyPageLikedService {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;
    private final UploadCourseHeartRepository uploadCourseHeartRepository;
    private final FeedHeartRepository feedHeartRepository;
    private final UploadCourseRepository uploadCourseRepository;

    private final MyCourseRepository myCourseRepository;
    private final DayScheduleRepository dayScheduleRepository;
    private final PlaceRepository placeRepository;
    private final CourseParticipantRepository courseParticipantRepository;

    private User getCurrentUser() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(UserErrorCode.USER_NOT_FOUND));
    }

    // =========================================================
    // 좋아요 코스 조회
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public List<LikedCourseResponse> getLikedCourses() {
        User user = getCurrentUser();

        List<UploadCourseHeart> hearts = uploadCourseHeartRepository.findByUser(user);

        return hearts.stream()
            .map(heart -> {
                UploadCourse c = heart.getUploadCourse();
                return LikedCourseResponse.builder()
                    .uploadCourseId(c.getId())
                    .title(c.getTitle())
                    .introduction(c.getIntroduction())
                    .thumbnailImage(c.getThumbnailImageUrl())
                    .keywords(c.getKeywords().stream()
                        .map(k -> k.getKeywordType().name())
                        .toList())
                    .build();
            })
            .toList();
    }

    // =========================================================
    // 좋아요 피드 조회
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public List<LikedFeedResponse> getLikedFeeds() {
        User user = getCurrentUser();

        List<FeedHeart> hearts = feedHeartRepository.findByUser(user);

        return hearts.stream()
            .map(heart -> {
                var f = heart.getFeed();
                return LikedFeedResponse.builder()
                    .feedId(f.getId())
                    .title(f.getTitle())
                    .location(f.getLocation())
                    .contentUrl(f.getContentUrl())
                    .heartCount(f.getHeartCount())
                    .commentCount(f.getCommentCount())
                    .build();
            })
            .toList();
    }

    // =========================================================
    // Deep Copy Fork
    // =========================================================
    @Override
    @Transactional
    public void forkCourse(Long uploadCourseId) {
        User user = getCurrentUser();

        UploadCourse origin = uploadCourseRepository.findById(uploadCourseId)
            .orElseThrow(() -> new BusinessException(LikedErrorCode.COURSE_NOT_FOUND));

        MyCourse originMyCourse = origin.getMyCourse();
        if (originMyCourse == null) {
            throw new BusinessException(LikedErrorCode.COURSE_NOT_FOUND);
        }

        try {
            // 1) MyCourse 생성
            MyCourse newCourse = MyCourse.builder()
                .title(origin.getTitle())
                .location(origin.getLocation())
                .startDate(null)
                .endDate(null)
                .build();
            newCourse.setType(MyCourseType.FORK);
            myCourseRepository.save(newCourse);

            // OWNER 참여자 생성
            CourseParticipant owner = CourseParticipant.builder()
                .user(user)
                .course(newCourse)
                .role(CourseRole.OWNER)
                .build();
            courseParticipantRepository.save(owner);

            // 2) DaySchedule 복사
            List<DaySchedule> originDaySchedules =
                dayScheduleRepository.findDaySchedulesWithPlaces(originMyCourse.getId());

            for (DaySchedule ods : originDaySchedules) {

                DaySchedule newDs = DaySchedule.builder()
                    .course(newCourse)
                    .day(ods.getDay())
                    .build();
                dayScheduleRepository.save(newDs);

                // 3) Place deep copy
                for (Place op : ods.getPlaces()) {
                    Place np = Place.builder()
                        .daySchedule(newDs)
                        .name(op.getName())
                        .startTime(op.getStartTime())
                        .memo(op.getMemo())
                        .budget(op.getBudget())
                        .latitude(op.getLatitude())
                        .longitude(op.getLongitude())
                        .placeUrl(op.getPlaceUrl())
                        .build();
                    placeRepository.save(np);
                }
            }

        } catch (Exception e) {
            throw new BusinessException(LikedErrorCode.FORK_FAILED);
        }
    }
}