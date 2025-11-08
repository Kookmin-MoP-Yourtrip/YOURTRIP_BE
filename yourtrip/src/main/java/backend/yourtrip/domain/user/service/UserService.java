package backend.yourtrip.domain.user.service;

import backend.yourtrip.domain.user.dto.request.UserLoginRequest;
import backend.yourtrip.domain.user.dto.request.UserSignupRequest;
import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;

public interface UserService {

    UserSignupResponse signup(UserSignupRequest request);

    UserLoginResponse login(UserLoginRequest request);

    UserLoginResponse refresh(String refreshToken);

    UserLoginResponse kakaoLoginOrSignup(String kakaoId, String email, String nickname, String profileImageUrl);

    User getUser(Long userId);

    Long getCurrentUserId();
}