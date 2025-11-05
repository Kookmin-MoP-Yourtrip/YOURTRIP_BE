package backend.yourtrip.domain.user.dto.request;

public record UserSignupRequest(
        String email,
        String password,
        String nickname
) {}