package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.entity.User;

public interface UserService {

    void sendVerificationCode(String email);

    void verifyCode(String email, String code);

    void setPassword(String email, String password);

    void findPasswordSendEmail(String email);

    void findPasswordVerify(String email, String code);

    void resetPassword(String email, String newPassword);

    UserSignupResponse completeSignup(ProfileCreateRequest request);

    UserLoginResponse login(UserLoginRequest request);

    UserLoginResponse refresh(String refreshToken);

    UserLoginResponse kakaoLoginOrSignup(String kakaoId, String email, String nickname, String profileImageUrl);

    User getUser(Long userId);

    Long getCurrentUserId();
}