package backend.yourtrip.global.config;

import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.jwt.JwtAuthenticationFilter;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import jakarta.servlet.DispatcherType;
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
                // 비동기 응답을 쓰는 재디스패치. 인증·인가는 첫 디스패치에서 끝났고 이 타입은 컨테이너만 만든다.
                // JwtAuthenticationFilter(OncePerRequestFilter)가 재디스패치를 건너뛰어 컨텍스트가 비므로,
                // 허용하지 않으면 저장까지 끝난 요청이 403 으로 나간다(AiCourseAsyncDispatchTest).
                .dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()

                // 정적 리소스 및 Swagger 허용
                .requestMatchers(
                    "/", "/index.html", "/error",
                    "/favicon.ico", "/css/**", "/js/**", "/images/**",
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/swagger-resources/**",
                    "/swagger-resources",
                    "/webjars/**",
                    "/actuator/**"
                ).permitAll()

                // 헬스체크의 8080 직접 확인(global/health). 인증을 요구하면 403으로 끝나 "워커가 응답했다"는
                // 사실은 같지만 HttpMainPortProbe가 2xx만 성공으로 보므로 멀쩡한 서버가 DOWN이 된다.
                // 외부 노출은 ALB 리스너 규칙이 /internal을 차단해 막는다. 와일드카드로 열지 않는 이유는
                // 나중에 생길 /internal 하위 경로가 의도치 않게 인증 없이 열리는 것을 막기 위해서다.
                .requestMatchers(HttpMethod.GET, "/internal/ping").permitAll()

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
                    "/api/users/password/find/reset"
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
        cfg.setAllowedOrigins(List.of("https://yourtrip.cloud"));
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