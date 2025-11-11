package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.*;
import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.entity.User;

public interface UserService {

    void sendVerificationCode(String email);

    void verifyCode(String email, String code);

    void setPassword(String email, String password);

    UserSignupResponse completeSignup(ProfileCreateRequest request);

    UserLoginResponse login(UserLoginRequest request);

    UserLoginResponse refresh(String refreshToken);

    User getUser(Long userId);

    Long getCurrentUserId();
}