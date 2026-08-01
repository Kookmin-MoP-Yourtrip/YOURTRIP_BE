package backend.yourtrip.domain.uploadcourse.entity;

import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@SQLRestriction("deleted = false")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadCourse extends BaseEntity {

    @Id
    @Column(name = "upload_course_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "course_id")
    private TravelCourse travelCourse;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String introduction;

    private String thumbnailImageS3Key;

    private int heartCount;

    private int viewCount;

    private boolean deleted;

    @OneToMany(mappedBy = "uploadCourse", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CourseKeyword> keywords;

    private String location;

    private int forkCount;

    @Builder
    public UploadCourse(String title, String introduction, String thumbnailImageS3Key,
        TravelCourse travelCourse, User user, String location) {
        this.title = title;
        this.introduction = introduction;
        this.thumbnailImageS3Key = thumbnailImageS3Key;
        this.travelCourse = travelCourse;
        this.user = user;
        this.location = location;
        keywords = new ArrayList<>();
    }

    public void increaseViewCount() {
        this.viewCount += 1;
    }

    public void increaseForkCount() {
        this.forkCount += 1;
    }

    public void updateUploadCourseInfo(String title, String introduction, String location,
        String thumbnailImageS3Key) {
        this.title = title;
        this.introduction = introduction;
        this.location = location;
        if (thumbnailImageS3Key != null) {
            this.thumbnailImageS3Key = thumbnailImageS3Key;
        }
    }
}
