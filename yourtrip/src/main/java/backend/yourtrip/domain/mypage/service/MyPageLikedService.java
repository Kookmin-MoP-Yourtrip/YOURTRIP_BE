package backend.yourtrip.domain.mypage.service;

import backend.yourtrip.domain.mypage.dto.response.LikedCourseResponse;
import backend.yourtrip.domain.mypage.dto.response.LikedFeedResponse;

import java.util.List;

public interface MyPageLikedService {

    List<LikedCourseResponse> getLikedCourses();

    List<LikedFeedResponse> getLikedFeeds();

    void forkCourse(Long uploadCourseId);
}