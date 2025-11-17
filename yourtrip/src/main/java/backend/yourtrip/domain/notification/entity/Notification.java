package backend.yourtrip.domain.notification.entity;

import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.notification.entity.enums.NotificationType;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Notification extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "receiver_id")
    private User receiver;

    @Enumerated(EnumType.STRING)
    private NotificationType type;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    private boolean readFlag;

    private boolean deleted;

    public void markRead() {
        this.readFlag = true;
    }

    public void softDelete() {
        this.deleted = true;
    }
}