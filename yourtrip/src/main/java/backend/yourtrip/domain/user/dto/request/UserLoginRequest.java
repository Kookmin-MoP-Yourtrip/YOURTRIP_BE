package backend.yourtrip.domain.user.dto.request;

public record UserLoginRequest(
        String email,
        String password
) {}