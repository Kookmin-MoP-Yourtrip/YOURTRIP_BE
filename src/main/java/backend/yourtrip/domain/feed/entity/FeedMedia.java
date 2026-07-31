package backend.yourtrip.domain.feed.entity;

import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class FeedMedia extends BaseEntity {

    @Id
    @Column(name = "feed_media_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "feed_id", nullable = false)
    private Feed feed;

    @Column(name = "media_s3_key", nullable = false)
    private String mediaS3Key;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", nullable = false)
    private MediaType mediaType;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Builder
    public FeedMedia(Feed feed, String mediaS3Key, MediaType mediaType, int displayOrder) {
        this.feed = feed;
        this.mediaS3Key = mediaS3Key;
        this.mediaType = mediaType;
        this.displayOrder = displayOrder;
    }

    public enum MediaType {
        IMAGE, VIDEO
    }
}
