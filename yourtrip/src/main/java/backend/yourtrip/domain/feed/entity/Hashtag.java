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
public class Hashtag extends BaseEntity {

    @Id
    @Column(name = "hashtag_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private Feed feed;

    private String tag_name;

    @Builder
    public Hashtag (Feed feed, String tag_name) {
        this.feed = feed;
        this.tag_name = tag_name;
    }
}
