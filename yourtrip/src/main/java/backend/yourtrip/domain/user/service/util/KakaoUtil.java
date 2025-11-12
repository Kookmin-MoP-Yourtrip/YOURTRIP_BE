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
import java.util.List;

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
        headers.setAccept(List.of(MediaType.APPLICATION_JSON)); // (추가) 응답 JSON 명시

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
            String body = e.getResponseBodyAsString();
            log.error("[KakaoUtil] 토큰 요청 실패: {}", body);

            if (body.contains("invalid_grant") || body.contains("KOE320")) {
                // 우리 서비스 에러코드 매핑
                throw new BusinessException(KakaoErrorCode.INVALID_AUTH_CODE);
            } else if (body.contains("invalid_client")) {
                throw new BusinessException(KakaoErrorCode.INVALID_CLIENT);
            } else {
                throw new BusinessException(KakaoErrorCode.TOKEN_REQUEST_FAILED);
            }

        } catch (JsonProcessingException e) {
            log.error("[KakaoUtil] JSON 파싱 실패: {}", e.getMessage());
            throw new BusinessException(KakaoErrorCode.RESPONSE_PARSE_FAILED);
        }
    }

    public KakaoProfileResponse requestProfile(String accessToken) {
        String reqUrl = "https://kapi.kakao.com/v2/user/me";

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));

        RestTemplate restTemplate = new RestTemplate();
        HttpEntity<?> entity = new HttpEntity<>(null, headers);

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