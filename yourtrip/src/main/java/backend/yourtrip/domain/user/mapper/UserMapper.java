package backend.yourtrip.domain.user.mapper;

import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.AuthProvider;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.entity.UserRole;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserMapper {

    public static UserSignupResponse toSignupResponse(User user, String imageUrl) {
        return new UserSignupResponse(
            user.getId(),
            user.getEmail(),
            user.getNickname(),
            imageUrl,
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
            .profileImageS3Key(profileImageUrl)
            .emailVerified(true)
            .deleted(false)
            .role(UserRole.TEMP)
            .provider(AuthProvider.KAKAO)
            .socialId(kakaoId)
            .build();
    }
}