package backend.yourtrip.domain.notification.service;

import backend.yourtrip.domain.notification.dto.response.NotificationResponse;
import backend.yourtrip.domain.notification.entity.Notification;
import backend.yourtrip.domain.notification.entity.enums.NotificationType;
import backend.yourtrip.domain.notification.repository.NotificationRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.NotificationErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    private User getCurrentUser() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));
    }

    @Override
    @Transactional
    public void createNotification(Long receiverId, String message, String type) {
        User receiver = userRepository.findById(receiverId)
            .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        NotificationType notiType = NotificationType.valueOf(type);

        Notification noti = Notification.builder()
            .receiver(receiver)
            .type(notiType)
            .message(message)
            .readFlag(false)
            .deleted(false)
            .build();

        notificationRepository.save(noti);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> getNotifications() {
        User user = getCurrentUser();

        return notificationRepository.findByReceiverAndDeletedFalseOrderByCreatedAtDesc(user)
            .stream()
            .map(n -> new NotificationResponse(
                n.getId(),
                n.getType(),
                n.getMessage(),
                n.isReadFlag(),
                n.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            ))
            .toList();
    }

    @Override
    @Transactional
    public void markRead(Long notificationId) {
        Notification n = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        n.markRead();
    }

    @Override
    @Transactional
    public void deleteNotification(Long notificationId) {
        Notification n = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTIFICATION_NOT_FOUND));

        n.softDelete();
    }

    @Override
    @Transactional
    public void deleteAllNotifications() {
        User user = getCurrentUser();

        notificationRepository.findByReceiverAndDeletedFalseOrderByCreatedAtDesc(user)
            .forEach(Notification::softDelete);
    }
}