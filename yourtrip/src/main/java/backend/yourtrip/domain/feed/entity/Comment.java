package backend.yourtrip.domain.feed.entity;

import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.FeedCommentErrorCode;
import backend.yourtrip.global.exception.errorCode.FeedErrorCode;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import software.amazon.awssdk.services.s3.endpoints.internal.Value;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Comment extends BaseEntity {

    @Id
    @Column(name = "feed_comment_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feed_id", nullable = false)
    private Feed feed;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "sentence", nullable = false, length = 500)
    private String sentence;

    @Column(name = "deleted", nullable = false, columnDefinition = "boolean default false")
    private boolean deleted;

    @Builder
    public Comment (Feed feed, User user, String sentence) {
        this.feed = feed;
        this.user = user;
        this.sentence = sentence;
    }

    public void updateSentence(String sentence) {
        this.sentence = sentence;
    }

    public void delete() {
        if (this.deleted) {
            throw new BusinessException(FeedCommentErrorCode.COMMENT_ALREADY_DELETED);
        }
        this.deleted = true;
    }
}
