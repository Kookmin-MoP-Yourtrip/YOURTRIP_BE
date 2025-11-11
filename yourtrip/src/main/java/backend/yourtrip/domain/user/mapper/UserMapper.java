package backend.yourtrip.domain.user.mapper;

import backend.yourtrip.domain.user.dto.response.*;
import backend.yourtrip.domain.user.entity.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserMapper {
    public static UserSignupResponse toSignupResponse(User user) {
        return new UserSignupResponse(
            user.getId(),
            user.getEmail(),
            user.getNickname(),
            user.getProfileImageUrl(),
            user.getCreatedAt().toString()
        );
    }

    public static UserLoginResponse toLoginResponse(User user, String accessToken) {
        return new UserLoginResponse(user.getId(), user.getNickname(), accessToken);
    }

    public static User toKakaoTemp(String kakaoId, String email, String profileImageUrl) {
        return User.builder()
            .email(email)
            .password(null)
            .nickname(null)
            .profileImageUrl(profileImageUrl)
            .emailVerified(true)
            .deleted(false)
            .role(UserRole.TEMP)
            .provider(AuthProvider.KAKAO)
            .socialId(kakaoId)
            .build();
    }
}