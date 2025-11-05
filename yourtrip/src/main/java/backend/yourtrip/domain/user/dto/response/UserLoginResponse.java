package backend.yourtrip.domain.user.dto.response;

public record UserLoginResponse(
        Long userId,
        String nickname,
        String accessToken
) {}