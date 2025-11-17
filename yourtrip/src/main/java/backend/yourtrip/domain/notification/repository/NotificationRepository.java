package backend.yourtrip.domain.notification.repository;

import backend.yourtrip.domain.notification.entity.Notification;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    List<Notification> findByReceiverAndDeletedFalseOrderByCreatedAtDesc(User user);
}