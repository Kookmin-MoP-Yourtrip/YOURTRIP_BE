package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.ProfileCreateRequest;
import backend.yourtrip.domain.user.dto.request.UserLoginRequest;
import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
import org.springframework.web.multipart.MultipartFile;

public interface UserService {

    void sendVerificationCode(String email);

    void verifyCode(String email, String code);

    void setPassword(String email, String password);

    void findPasswordSendEmail(String email);

    void findPasswordVerify(String email, String code);

    void resetPassword(String email, String newPassword);

    UserSignupResponse completeSignup(ProfileCreateRequest request, MultipartFile profileImage);

    UserLoginResponse login(UserLoginRequest request);

    UserLoginResponse refresh(String refreshToken);

    User getUser(Long userId);

    Long getCurrentUserId();
}