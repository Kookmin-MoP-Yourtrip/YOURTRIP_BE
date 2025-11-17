package backend.yourtrip.domain.notification.entity;

import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class NotificationSetting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "noti_setting_id")
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", unique = true)
    private User user;

    private boolean commentNoti = true;
    private boolean courseRemindNoti = true;
    private boolean courseInviteNoti = true;

    public void toggleComment() {
        commentNoti = !commentNoti;
    }

    public void toggleCourseRemind() {
        courseRemindNoti = !courseRemindNoti;
    }

    public void toggleCourseInvite() {
        courseInviteNoti = !courseInviteNoti;
    }
}