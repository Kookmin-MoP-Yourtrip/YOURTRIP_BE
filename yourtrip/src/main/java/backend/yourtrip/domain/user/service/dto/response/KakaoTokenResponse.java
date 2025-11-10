package backend.yourtrip.domain.user.service.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KakaoTokenResponse(
    @JsonProperty("access_token") String accessToken,
    @JsonProperty("token_type") String tokenType,
    @JsonProperty("refresh_token") String refreshToken,
    @JsonProperty("expires_in") int expiresIn,
    @JsonProperty("scope") String scope,
    @JsonProperty("refresh_token_expires_in") int refreshTokenExpiresIn
) {}
