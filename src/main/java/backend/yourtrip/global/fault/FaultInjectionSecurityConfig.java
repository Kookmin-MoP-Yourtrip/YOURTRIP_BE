package backend.yourtrip.global.fault;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 8080 결함 주입 경로의 인증 면제를 <b>장치와 함께</b> 둔다.
 *
 * <p>공통 {@code SecurityConfig}에 {@code /internal/fault/**}를 permitAll로 넣지 않는 이유: 그러면 장치가 꺼진 운영에서도
 * 면제 규칙이 남는다. 지금은 핸들러가 없어 404일 뿐이지만, 나중에 그 경로에 무언가 생기면 인증 없이 열린다. 여기 두면
 * 장치가 꺼질 때 면제도 함께 사라진다 — 꺼진 상태에서 이 경로는 공통 체인의 {@code anyRequest().authenticated()}로 거부된다
 * (이 앱은 인증 진입점을 두지 않아 익명 요청이 403이다).
 *
 * <p>공통 체인보다 앞에 둔다({@code @Order(0)}). 공통 체인은 순서를 지정하지 않아 가장 뒤에 오고, 경로를 제한하지
 * 않으므로 먼저 오면 모든 요청을 가져간다.
 */
@Configuration
@ConditionalOnFaultInjection
public class FaultInjectionSecurityConfig {

    @Bean
    @Order(0)
    SecurityFilterChain faultInjectionFilterChain(HttpSecurity http) throws Exception {
        return http
            .securityMatcher("/internal/fault/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .build();
    }
}
