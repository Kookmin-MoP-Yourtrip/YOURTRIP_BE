package backend.yourtrip.domain.user.service.util;

import backend.yourtrip.domain.user.dto.response.KakaoTokenResponse;
import backend.yourtrip.domain.user.dto.response.KakaoProfileResponse;
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
        params.add("redirect_uri", redirectUri);
        params.add("code", code);

        RestTemplate restTemplate = new RestTemplate();
        HttpEntity<MultiValueMap<String, String>> entity = new HttpEntity<>(params, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(reqUrl, HttpMethod.POST, entity, String.class);
            return objectMapper.readValue(response.getBody(), KakaoTokenResponse.class);

        } catch (HttpClientErrorException e) {
            throw new BusinessException(KakaoErrorCode.TOKEN_REQUEST_FAILED);
        } catch (JsonProcessingException e) {
            throw new BusinessException(KakaoErrorCode.TOKEN_REQUEST_FAILED);
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
            ResponseEntity<String> response = restTemplate.exchange(reqUrl, HttpMethod.POST, entity, String.class);
            return objectMapper.readValue(response.getBody(), KakaoProfileResponse.class);

        } catch (HttpClientErrorException e) {
            throw new BusinessException(KakaoErrorCode.USERINFO_REQUEST_FAILED);
        } catch (JsonProcessingException e) {
            throw new BusinessException(KakaoErrorCode.USERINFO_REQUEST_FAILED);
        }
    }
}