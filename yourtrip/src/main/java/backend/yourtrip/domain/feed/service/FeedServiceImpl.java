package backend.yourtrip.domain.feed.service;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.dto.request.FeedUpdateRequest;
import backend.yourtrip.domain.feed.dto.response.*;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.feed.entity.FeedLike;
import backend.yourtrip.domain.feed.entity.FeedMedia;
import backend.yourtrip.domain.feed.entity.Hashtag;
import backend.yourtrip.domain.feed.entity.enums.FeedSortType;
import backend.yourtrip.domain.feed.mapper.FeedMapper;
import backend.yourtrip.domain.feed.repository.FeedLikeRepository;
import backend.yourtrip.domain.feed.repository.FeedRepository;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import backend.yourtrip.global.exception.errorCode.FeedResponseCode;
import backend.yourtrip.global.exception.errorCode.S3ErrorCode;
import backend.yourtrip.global.exception.errorCode.UploadCourseErrorCode;
import backend.yourtrip.global.s3.service.S3Service;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FeedServiceImpl implements FeedService {

    private final FeedRepository feedRepository;
    private final UserService userService;
    private final UploadCourseRepository uploadCourseRepository;
    private final FeedMapper feedMapper;
    private final S3Service s3Service;
    private final FeedLikeRepository feedLikeRepository;

    // ======================================
    // 1. 피드 생성
    // ======================================
    @Override
    @Transactional
    public FeedCreateResponse saveFeed(FeedCreateRequest request, List<MultipartFile> mediaFiles) {

        if (mediaFiles == null || mediaFiles.isEmpty()) {
            throw new BusinessException(FeedErrorCode.MEDIA_FILES_REQUIRED);
        }

        Long userId = userService.getCurrentUserId();
        User user = userService.getUser(userId);

        UploadCourse uploadCourse = null;
        if (request.uploadCourseId() != null) {

            uploadCourse = uploadCourseRepository.findById(request.uploadCourseId())
                    .orElseThrow(() -> new BusinessException(UploadCourseErrorCode.UPLOAD_COURSE_NOT_FOUND));

            if (!uploadCourse.getUser().getId().equals(userId)) {
                throw new BusinessException(FeedErrorCode.UPLOAD_COURSE_FORBIDDEN);
            }
        }

        Feed feed = feedMapper.toEntity(user, request, uploadCourse);
        feedRepository.save(feed);

        // 해시태그 저장
        if (request.hashtags() != null) {
            for (String tag : request.hashtags()) {
                Hashtag hashtag = Hashtag.builder()
                        .feed(feed)
                        .tagName(tag)
                        .build();

                feed.getHashtags().add(hashtag);
            }
        }

        if (mediaFiles != null && !mediaFiles.isEmpty()) {
            List<FeedMedia> mediaList = new ArrayList<>();
            for (int i = 0; i < mediaFiles.size(); i++) {
                MultipartFile file = mediaFiles.get(i);
                try {
                    S3Service.UploadResult result = s3Service.uploadFile(file);
                    String s3Key = result.key();
                    FeedMedia.MediaType mediaType = determineMediaType(s3Key);
                    FeedMedia feedMedia = FeedMedia.builder()
                            .feed(feed)
                            .mediaS3Key(s3Key)
                            .mediaType(mediaType)
                            .displayOrder(i)
                            .build();
                    mediaList.add(feedMedia);
                } catch (IOException e) {
                    throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
                }
            }
            feed.updateMediaList(mediaList);
        }
        return new FeedCreateResponse(feed.getId(), FeedResponseCode.FEED_CREATED.getMessage());
    }

    // ======================================
    // 2. 단건 조회
    // ======================================
    @Override
    @Transactional
    public FeedDetailResponse getFeedByFeedId(Long feedId) {

        Feed feed = feedRepository.findFeedWithHashtag(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        feed.increaseViewCount();

        boolean isLiked = false;
        try {
            Long currentUserId = userService.getCurrentUserId();
            User currentUser = userService.getUser(currentUserId);
            isLiked = feedLikeRepository.existsByUserAndFeed(currentUser, feed);
        } catch (Exception e) {
            log.warn("좋아요 여부 확인 실패 - feedId: {}, error: {}", feedId, e.getMessage());
        }

        return feedMapper.toDetailResponse(feed, isLiked);
    }

    // ======================================
    // 3. 전체 조회
    // ======================================
    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedAll(int page, int size, FeedSortType sortType) {

        Pageable pageable = PageRequest.of(page, size, getSort(sortType));
        Page<Feed> feeds = feedRepository.findAll(pageable);

        return feedMapper.toListResponse(feeds);
    }
    private Sort getSort(FeedSortType sortType) {
        return switch (sortType) {
            case NEW -> Sort.by(Sort.Direction.DESC, "createdAt");
            case POPULAR -> Sort.by(Sort.Direction.DESC, "viewCount");
        };
    }

    // ======================================
    // 4. 유저별 조회
    // ======================================
    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedByUserId(Long userId, int page, int size) {

        userService.getUser(userId);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<Feed> feeds = feedRepository.findByUser_Id(userId, pageable);
        return feedMapper.toListResponse(feeds);
    }

    // ======================================
    // 5. 키워드 검색
    // ======================================
    @Override
    @Transactional(readOnly = true)
    public FeedListResponse getFeedByKeyword(String keyword, int page, int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Feed> feeds = feedRepository.findByKeyword(keyword, pageable);

        return feedMapper.toListResponse(feeds);
    }

    // ======================================
    // 6. 수정
    // ======================================
    @Override
    @Transactional
    public FeedUpdateResponse updateFeed(Long feedId, FeedUpdateRequest request, List<MultipartFile> mediaFiles) {

        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long userId = userService.getCurrentUserId();
        if (!feed.getUser().getId().equals(userId)) {
            throw new BusinessException(FeedErrorCode.FEED_UPDATE_NOT_AUTHORIZED);
        }

        UploadCourse uploadCourse = null;

        if (request.uploadCourseId() != null) {
            uploadCourse = uploadCourseRepository.findById(request.uploadCourseId())
                .orElseThrow(() -> new BusinessException(FeedErrorCode.UPLOAD_COURSE_NOT_FOUND));

            if (!uploadCourse.getUser().getId().equals(userId)) {
                throw new BusinessException(FeedErrorCode.UPLOAD_COURSE_FORBIDDEN);
            }
        }

        feed.updateFeed(request.title(), request.location(), request.content(), uploadCourse);

        // 해시태그 갱신
        feed.getHashtags().clear();
        if (request.hashtags() != null) {
            for (String tag : request.hashtags()) {
                Hashtag hashtag = Hashtag.builder()
                    .feed(feed)
                    .tagName(tag)
                    .build();
                feed.getHashtags().add(hashtag);
            }
        }

        if (mediaFiles != null) {
            for (FeedMedia media : feed.getMediaList()) {
                try {
                    s3Service.deleteFile(media.getMediaS3Key());
                } catch (Exception e) {
                    log.warn("S3 파일 삭제 실패 - feedId: {}, mediaId: {}, s3Key: {}, error: {}",
                            feed.getId(), media.getId(), media.getMediaS3Key(), e.getMessage(), e);
                }
            }

            // 새 미디어 업로드 및 추가
            List<FeedMedia> newMediaList = new ArrayList<>();
            for (int i = 0; i < mediaFiles.size(); i++) {
                MultipartFile file = mediaFiles.get(i);
                try {
                    S3Service.UploadResult result = s3Service.uploadFile(file);
                    String s3Key = result.key();
                    FeedMedia.MediaType mediaType = determineMediaType(s3Key);
                    FeedMedia feedMedia = FeedMedia.builder()
                            .feed(feed)
                            .mediaS3Key(s3Key)
                            .mediaType(mediaType)
                            .displayOrder(i)
                            .build();
                    newMediaList.add(feedMedia);
                } catch (IOException e) {
                    throw new BusinessException(S3ErrorCode.FAIL_UPLOAD_FILE);
                }
            }
            feed.updateMediaList(newMediaList);
        }
        return new FeedUpdateResponse(feed.getId(), FeedResponseCode.FEED_UPDATED.getMessage());
    }

    // ======================================
    // 7. 삭제
    // ======================================
    @Override
    @Transactional
    public void deleteFeed(Long feedId) {

        Feed feed = feedRepository.findById(feedId)
            .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        Long userId = userService.getCurrentUserId();

        if (!feed.getUser().getId().equals(userId)) {
            throw new BusinessException(FeedErrorCode.FEED_DELETE_NOT_AUTHORIZED);
        }

        feed.delete();
    }

    // ======================================
    // 8. 좋아요 토글
    // ======================================
    @Override
    @Transactional
    public FeedLikeResponse toggleLike(Long feedId, Long userId) {

        Feed feed = feedRepository.findById(feedId)
                .orElseThrow(() -> new BusinessException(FeedErrorCode.FEED_NOT_FOUND));

        User user = userService.getUser(userId);

        Optional<FeedLike> existingLike = feedLikeRepository.findByUserAndFeed(user, feed);

        boolean isLiked;

        if (existingLike.isPresent()) {
            feedLikeRepository.delete(existingLike.get());
            feed.decreaseHeartCount();
            isLiked = false;
        } else {
            FeedLike feedLike = FeedLike.builder()
                    .user(user)
                    .feed(feed)
                    .build();
            feedLikeRepository.save(feedLike);
            feed.increaseHeartCount();
            isLiked = true;
        }

        return FeedLikeResponse.builder()
                .feedId(feedId)
                .isLiked(isLiked)
                .heartCount(feed.getHeartCount())
                .build();
    }

    // ======================================
    // 헬퍼 메소드
    // ======================================
    private FeedMedia.MediaType determineMediaType(String s3Key) {
        String lowerKey = s3Key.toLowerCase();
        if (lowerKey.endsWith(".mp4") || lowerKey.endsWith(".mov") || lowerKey.endsWith(".webm")) {
            return FeedMedia.MediaType.VIDEO;
        }
        return FeedMedia.MediaType.IMAGE;
    }
}