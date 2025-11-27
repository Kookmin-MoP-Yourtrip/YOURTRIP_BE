package backend.yourtrip.global.config;

import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.jwt.JwtAuthenticationFilter;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserRepository userRepository;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(
                session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // 정적 리소스 및 Swagger 허용
                .requestMatchers(
                    "/", "/index.html", "/error",
                    "/favicon.ico", "/css/**", "/js/**", "/images/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/swagger-resources/**",
                    "/swagger-resources",
                    "/webjars/**"
                ).permitAll()

                .requestMatchers(
                    "/api/users/email/send",
                    "/api/users/email/verify",
                    "/api/users/password",
                    "/api/users/profile",
                    "/api/users/login",
                    "/api/users/refresh",

                    // 비밀번호 찾기
                    "/api/users/password/find/email",
                    "/api/users/password/find/verify",
                    "/api/users/password/find/reset",

                    // 카카오
                    "/api/users/login/kakao/callback",
                    "/api/users/login/kakao/complete"
                ).permitAll()

                // Upload-courses GET 허용
                .requestMatchers(
                    HttpMethod.GET, "/api/upload-courses/me"
                ).authenticated()

                .requestMatchers(
                    HttpMethod.GET, "/api/upload-courses/**"
                ).permitAll()

                .requestMatchers(
                    HttpMethod.GET, "/api/feeds/users/*"
                ).authenticated()

                // 피드 전체 조회 허용 (비로그인)
                .requestMatchers(
                    HttpMethod.GET, "/api/feeds/**"
                ).permitAll()

                // 나머지는 인증 필요
                .anyRequest().authenticated()
            )
            .addFilterBefore(
                new JwtAuthenticationFilter(jwtTokenProvider, userRepository),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(List.of("https://yourtrip.site"));
        cfg.addExposedHeader("Authorization");
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource src = new UrlBasedCorsConfigurationSource();
        src.registerCorsConfiguration("/**", cfg);
        return src;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}