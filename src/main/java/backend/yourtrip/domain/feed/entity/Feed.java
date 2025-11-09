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
    private List<Hashtag> hashtags;

    private int commentCount;

    private int heartCount;

    private boolean deleted;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_course_id")
    private UploadCourse tagCourse;

    @Builder
    public Feed (User user, String title, String location, String contentUrl, UploadCourse tagCourse) {
        this.user = user;
        this.title = title;
        this.location = location;
        this.contentUrl = contentUrl;
        hashtags = new ArrayList<>();
        commentCount = 0;
        heartCount = 0;
        this.tagCourse = tagCourse;
    }
}
