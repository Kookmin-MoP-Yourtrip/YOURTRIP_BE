package backend.yourtrip.global.config;

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

                // 이메일 회원가입 단계 허용
                .requestMatchers(
                    "/api/users/email/send",
                    "/api/users/email/verify",
                    "/api/users/password",
                    "/api/users/profile",
                    "/api/users/login",
                    "/api/users/refresh"
                ).permitAll()

                // 🔥 카카오 로그인 + 회원가입 완료 허용 (중요)
                .requestMatchers(
                    "/api/users/login/kakao/callback",
                    "/api/users/login/kakao/complete"
                ).permitAll()

                // Upload-courses GET 허용
                .requestMatchers(
                    HttpMethod.GET, "/api/upload-courses/**"
                ).permitAll()
                .requestMatchers(
                    HttpMethod.GET, "/api/upload-courses/keywords"
                ).permitAll()

                // 나머지는 인증 필요
                .anyRequest().authenticated()
            )
            .addFilterBefore(new JwtAuthenticationFilter(jwtTokenProvider),
                UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
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