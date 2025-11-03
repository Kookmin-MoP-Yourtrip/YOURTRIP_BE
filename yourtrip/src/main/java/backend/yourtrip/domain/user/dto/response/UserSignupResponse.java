package backend.yourtrip.domain.user.dto.response;

public record UserSignupResponse(
        Long userId,
        String email,
        String nickname,
        String createdAt
) {}