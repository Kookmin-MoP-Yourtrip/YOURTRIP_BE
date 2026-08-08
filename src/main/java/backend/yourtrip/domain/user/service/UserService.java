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

    /**
     * getCurrentUserId()와 달리 비로그인 상태에서도 예외를 던지지 않고 null을 반환한다.
     * 로그인 여부에 따라 분기해야 하는 곳(예: 비로그인도 허용된 API)에서 사용한다.
     */
    Long getCurrentUserIdOrNull();
}