package backend.yourtrip.domain.feed.entity;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
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
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "title", nullable = false, length = 100)
    private String title;

    @Column(name = "location", nullable = false, length = 50)
    private String location;

    @Column(name = "content", nullable = false, length = 1000)
    private String content;

    @OneToMany(mappedBy = "feed", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Hashtag> hashtags;

    @Column(name = "commentCount", nullable = false, columnDefinition = "int default 0")
    private int commentCount;

    @Column(name = "heartCount", nullable = false, columnDefinition = "int default 0")
    private int heartCount;

    @Column(name = "viewCount", nullable = false, columnDefinition = "int default 0")
    private int viewCount;

    @Column(name = "deleted", nullable = false, columnDefinition = "boolean default false")
    private boolean deleted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_course_id")
    private UploadCourse uploadCourse;

    @Builder
    public Feed (User user, String title, String location, String content, UploadCourse uploadCourse) {
        this.user = user;
        this.title = title;
        this.location = location;
        this.content = content;
        hashtags = new ArrayList<>();
        commentCount = 0;
        heartCount = 0;
        viewCount = 0;
        this.uploadCourse = uploadCourse;
    }

    public void updateFeed(String title, String location, String content, UploadCourse uploadCourse) {
        if (title != null && !title.trim().isEmpty()) {
            this.title = title;
        }
        if (location != null && !location.trim().isEmpty()) {
            this.location = location;
        }
        if (content != null && !content.trim().isEmpty()) {
            this.content = content;
        }
        if (uploadCourse != null) {
            this.uploadCourse = uploadCourse;
        }

    }

    public void updateHashtags(List<Hashtag> newHashtags) {
        this.hashtags.clear();
        this.hashtags.addAll(newHashtags);
    }

    public void delete() {
        if (this.deleted) {
            throw new BusinessException(FeedErrorCode.FEED_ALREADY_DELETED);
        }
        this.deleted = true;
    }

    public void increaseViewCount() {
        this.viewCount += 1;
    }
}
