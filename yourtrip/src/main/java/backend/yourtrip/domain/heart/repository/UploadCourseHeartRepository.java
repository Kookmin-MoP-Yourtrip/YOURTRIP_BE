package backend.yourtrip.domain.heart.repository;

import backend.yourtrip.domain.heart.entity.UploadCourseHeart;
import backend.yourtrip.domain.mypage.dto.response.LikedCourseResponse;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UploadCourseHeartRepository extends JpaRepository<UploadCourseHeart, Long> {

    List<UploadCourseHeart> findByUser(User user);

    boolean existsByUserAndUploadCourse(User user, UploadCourse uploadCourse);

    @Query("""
        SELECT new backend.yourtrip.domain.mypage.dto.response.LikedCourseResponse(
            uc.uploadCourse.id,
            uc.uploadCourse.title,
            uc.uploadCourse.introduction,
            null,
            uc.uploadCourse.keywords
        )
        FROM UploadCourseHeart uc
        WHERE uc.user.id = :userId
        ORDER BY uc.id DESC
        """)
    List<LikedCourseResponse> findLikedCourses(@Param("userId") Long userId);

}