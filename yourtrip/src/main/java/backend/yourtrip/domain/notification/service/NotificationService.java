package backend.yourtrip.domain.notification.service;

import backend.yourtrip.domain.notification.dto.response.NotificationResponse;

import java.util.List;

public interface NotificationService {

    void createNotification(Long receiverId, String message, String type);

    List<NotificationResponse> getNotifications();

    void markRead(Long notificationId);

    void deleteNotification(Long notificationId);

    void deleteAllNotifications();
}