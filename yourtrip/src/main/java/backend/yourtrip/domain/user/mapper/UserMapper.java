package backend.yourtrip.domain.user.mapper;

import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
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
}