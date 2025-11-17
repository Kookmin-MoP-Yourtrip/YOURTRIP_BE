package backend.yourtrip.domain.notification.service;

import backend.yourtrip.domain.notification.dto.response.NotificationSettingResponse;

public interface NotificationSettingService {

    NotificationSettingResponse getSettings();
    NotificationSettingResponse toggleComment();
    NotificationSettingResponse toggleCourseRemind();
    NotificationSettingResponse toggleCourseInvite();
}
