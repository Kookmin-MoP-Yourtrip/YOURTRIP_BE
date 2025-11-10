package backend.yourtrip.domain.user.service.util;

import backend.yourtrip.domain.user.service.dto.response.KakaoProfileResponse;
import backend.yourtrip.domain.user.service.dto.response.KakaoTokenResponse;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.KakaoErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class KakaoUtil {

    @Value("${kakao.auth.client-id}")
    private String clientId;

    @Value("${kakao.auth.client-secret}")
    private String clientSecret;

    @Value("${kakao.auth.redirect-uri}")
    private String redirectUri;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public KakaoTokenResponse requestToken(String code) {
        String reqUrl = "https://kauth.kakao.com/oauth/token";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
        params.add("grant_type", "authorization_code");
        params.add("client_id", clientId);
        params.add("client_secret", clientSecret);
        params.add("redirect_uri", redirectUri);
        params.add("code", code);

        RestTemplate restTemplate = new RestTemplate();
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<String> response =
                restTemplate.exchange(reqUrl, HttpMethod.POST, entity, String.class);

            log.info("[KakaoUtil] 토큰 요청 성공: {}", response.getBody());
            return objectMapper.readValue(response.getBody(), KakaoTokenResponse.class);

        } catch (HttpClientErrorException e) {
            // invalid_grant 등 카카오 서버 반환 시
            String body = e.getResponseBodyAsString();
            log.error("[KakaoUtil] 토큰 요청 실패: {}", body);

            // invalid_grant, invalid_client 구분
            if (body.contains("invalid_grant")) {
                throw new BusinessException(KakaoErrorCode.INVALID_AUTH_CODE);
            } else if (body.contains("invalid_client")) {
                throw new BusinessException(KakaoErrorCode.INVALID_CLIENT);
            } else {
                throw new BusinessException(KakaoErrorCode.TOKEN_REQUEST_FAILED);
            }

        } catch (JsonProcessingException e) {
            // JSON 필드 파싱 실패 (id_token 등)
            log.error("[KakaoUtil] JSON 파싱 실패: {}", e.getMessage());
            throw new BusinessException(KakaoErrorCode.RESPONSE_PARSE_FAILED);
        }
    }

    public KakaoProfileResponse requestProfile(String accessToken) {
        String reqUrl = "https://kapi.kakao.com/v2/user/me";

        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer " + accessToken);
        headers.add("Content-type", "application/x-www-form-urlencoded;charset=utf-8");

        RestTemplate restTemplate = new RestTemplate();
        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response =
                restTemplate.exchange(reqUrl, HttpMethod.POST, entity, String.class);

            log.info("[KakaoUtil] 프로필 요청 성공: {}", response.getBody());
            return objectMapper.readValue(response.getBody(), KakaoProfileResponse.class);

        } catch (HttpClientErrorException e) {
            log.error("[KakaoUtil] 프로필 요청 실패: {}", e.getResponseBodyAsString());
            throw new BusinessException(KakaoErrorCode.USERINFO_REQUEST_FAILED);
        } catch (JsonProcessingException e) {
            log.error("[KakaoUtil] JSON 파싱 실패: {}", e.getMessage());
            throw new BusinessException(KakaoErrorCode.USERINFO_REQUEST_FAILED);
        }
    }
}