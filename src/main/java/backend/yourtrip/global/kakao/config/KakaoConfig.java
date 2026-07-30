package backend.yourtrip.global.kakao.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@RequiredArgsConstructor
public class KakaoConfig {

    @Value("${kakao.api-key}")
    private String apiKey;

    @Value("${kakao.local.base-url}")
    private String kakaoBaseUrl;

    @Bean
    public WebClient kakaoWebClient() {
        return WebClient.builder()
            .baseUrl(kakaoBaseUrl)
            .defaultHeader("Authorization", "KakaoAK " + apiKey)
            .build();
    }
}
