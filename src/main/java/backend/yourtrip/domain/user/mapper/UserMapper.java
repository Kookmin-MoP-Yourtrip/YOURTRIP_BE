package backend.yourtrip.domain.user.mapper;

import backend.yourtrip.domain.user.dto.response.UserLoginResponse;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
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
            user.getCreatedAt()
        );
    }


    public static UserLoginResponse toLoginResponse(User user, String profileImageUrl, String accessToken) {
        return new UserLoginResponse(
            user.getId(),
            user.getNickname(),
            profileImageUrl,
            accessToken
        );
    }

}