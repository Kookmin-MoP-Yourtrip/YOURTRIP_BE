package backend.yourtrip.domain.notification.repository;

import backend.yourtrip.domain.notification.entity.NotificationSetting;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {

    Optional<NotificationSetting> findByUser(User user);
}