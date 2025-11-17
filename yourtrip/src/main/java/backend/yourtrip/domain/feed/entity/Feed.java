package backend.yourtrip.domain.feed.entity;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@SQLRestriction("deleted = false")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Feed extends BaseEntity {

    @Id
    @Column(name = "feed_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String title;

    private String location;

    private String contentUrl;

    @OneToMany(mappedBy = "feed", cascade = CascadeType.PERSIST)
    private List<Hashtag> hashtags = new ArrayList<>();

    private int commentCount;

    private int heartCount;

    private int viewCount;

    private boolean deleted;

    @Column(nullable = false)
    private boolean isPublic = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_course_id")
    private UploadCourse uploadCourse;

    @Builder
    public Feed(User user, String title, String location, String contentUrl, UploadCourse uploadCourse) {
        this.user = user;
        this.title = title;
        this.location = location;
        this.contentUrl = contentUrl;
        this.uploadCourse = uploadCourse;
        this.commentCount = 0;
        this.heartCount = 0;
        this.viewCount = 0;
        this.deleted = false;
        this.isPublic = true;
        this.hashtags = new ArrayList<>();
    }

    public void increaseViewCount() {
        this.viewCount += 1;
    }

    public void updateVisibility() {
        this.isPublic = !this.isPublic;
    }

    public void updateFeed(String title, String location, String contentUrl) {
        this.title = title;
        this.location = location;
        this.contentUrl = contentUrl;
    }

    public void increaseCommentCount() {
        this.commentCount += 1;
    }

    public void decreaseCommentCount() {
        if (this.commentCount > 0) {
            this.commentCount -= 1;
        }
    }

    public void softDelete() {
        this.deleted = true;
    }
}
