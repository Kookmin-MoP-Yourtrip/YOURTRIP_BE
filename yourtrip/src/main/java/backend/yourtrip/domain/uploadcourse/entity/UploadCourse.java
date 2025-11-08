package backend.yourtrip.domain.uploadcourse.entity;

import backend.yourtrip.domain.mycourse.entity.MyCourse;
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
    private MyCourse myCourse;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String introduction;

    private String thumbnailImageUrl;

    private int commentCount;

    private int heartCount;

    private int viewCount;

    private boolean deleted;

    @OneToMany(mappedBy = "uploadCourse", cascade = CascadeType.PERSIST)
    private List<CourseKeyword> keywords;

    @Builder
    public UploadCourse(String title, String introduction, String thumbnailImageUrl,
        MyCourse myCourse, User user) {
        this.title = title;
        this.introduction = introduction;
        this.thumbnailImageUrl = thumbnailImageUrl;
        this.myCourse = myCourse;
        this.user = user;
        keywords = new ArrayList<>();
    }

}
