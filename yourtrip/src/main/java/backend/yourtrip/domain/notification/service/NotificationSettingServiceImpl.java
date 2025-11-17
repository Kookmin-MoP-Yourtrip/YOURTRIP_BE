package backend.yourtrip.domain.notification.service;

import backend.yourtrip.domain.notification.dto.response.NotificationSettingResponse;
import backend.yourtrip.domain.notification.entity.NotificationSetting;
import backend.yourtrip.domain.notification.repository.NotificationSettingRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.NotificationErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationSettingServiceImpl implements NotificationSettingService {

    private final NotificationSettingRepository settingRepository;
    private final UserRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;

    private User getCurrentUser() {
        Long userId = jwtTokenProvider.getCurrentUserId();
        return userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(NotificationErrorCode.NOTI_SETTING_NOT_FOUND));
    }

    private NotificationSetting getSetting(User user) {
        return settingRepository.findByUser(user)
            .orElseGet(() -> settingRepository.save(
                NotificationSetting.builder().user(user).build()
            ));
    }

    @Override
    @Transactional(readOnly = true)
    public NotificationSettingResponse getSettings() {
        User user = getCurrentUser();
        NotificationSetting setting = getSetting(user);

        return new NotificationSettingResponse(
            setting.isCommentNoti(),
            setting.isCourseRemindNoti(),
            setting.isCourseInviteNoti()
        );
    }

    @Override
    @Transactional
    public NotificationSettingResponse toggleComment() {
        User user = getCurrentUser();
        NotificationSetting setting = getSetting(user);
        setting.toggleComment();
        return getSettings();
    }

    @Override
    @Transactional
    public NotificationSettingResponse toggleCourseRemind() {
        User user = getCurrentUser();
        NotificationSetting setting = getSetting(user);
        setting.toggleCourseRemind();
        return getSettings();
    }

    @Override
    @Transactional
    public NotificationSettingResponse toggleCourseInvite() {
        User user = getCurrentUser();
        NotificationSetting setting = getSetting(user);
        setting.toggleCourseInvite();
        return getSettings();
    }
}