package backend.yourtrip.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record KakaoTokenRequest(
    @Schema(description = "카카오 로그인 인가 코드")
    String code
) {}
