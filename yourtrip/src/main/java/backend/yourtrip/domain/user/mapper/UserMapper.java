package backend.yourtrip.domain.user.mapper;

import backend.yourtrip.domain.user.dto.request.UserSignupRequest;
import backend.yourtrip.domain.user.dto.response.UserSignupResponse;
import backend.yourtrip.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class UserMapper {

    public static User toEntity(UserSignupRequest request, PasswordEncoder encoder) {
        return User.builder()
            .email(request.email())
            .password(encoder.encode(request.password()))
            .nickname(request.nickname())
            .deleted(false)
            .build();
    }

    public static UserSignupResponse toSignupResponse(User user) {
        return new UserSignupResponse(
            user.getId(),
            user.getEmail(),
            user.getNickname(),
            user.getCreatedAt().toString()
        );
    }
}