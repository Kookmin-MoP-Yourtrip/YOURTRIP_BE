package backend.yourtrip.domain.feed.entity;

import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "feed_like", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"user_id", "feed_id"})
})
public class FeedLike extends BaseEntity {

    @Id
    @Column(name = "feed_like_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feed_id", nullable = false)
    private Feed feed;

    @Builder
    public FeedLike(User user, Feed feed) {
        this.user = user;
        this.feed = feed;
    }
}
