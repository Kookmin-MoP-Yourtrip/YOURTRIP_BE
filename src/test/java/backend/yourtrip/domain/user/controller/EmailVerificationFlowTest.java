package backend.yourtrip.domain.user.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import backend.yourtrip.domain.user.store.EmailVerificationStore;
import backend.yourtrip.global.mail.service.MailService;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 이메일 인증 발송→확인→비밀번호 설정 흐름을 컨트롤러부터 관통해 검증한다(#117).
 * <p>
 * test 프로필의 Redis는 의도적으로 죽은 포트라(application-test.yml), 실제
 * RedisEmailVerificationStore 대신 인메모리 fake를 @Primary로 꽂아 흐름 로직만 본다.
 * TTL 만료는 fake에서 코드를 제거하는 것으로 시뮬레이션한다 — Redis에서 키가 사라진 것과
 * 저장소 관점에서 동일한 상태다. Redis 장애 시 fail-closed 응답은
 * EmailVerificationFailClosedTest가 실제 구현체로 검증한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationFlowTest {

    private static final String EMAIL = "flow-test@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryEmailVerificationStore emailVerificationStore;

    @MockitoBean
    private MailService mailService;

    @BeforeEach
    void resetStore() {
        emailVerificationStore.reset();
    }

    @Test
    @DisplayName("발송→틀린 코드 400→맞는 코드 200→비밀번호 설정 200 전체 흐름이 이어진다")
    void sendVerifySetPassword_fullFlow() throws Exception {
        // 1. 발송 — 메일 mock에 전달된 코드가 곧 사용자가 받았을 코드다
        mockMvc.perform(post("/api/users/email/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\"}".formatted(EMAIL)))
            .andExpect(status().isOk());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerificationMail(eq(EMAIL), codeCaptor.capture());
        String code = codeCaptor.getValue();

        // 2. 틀린 코드 — 400 + INVALID_VERIFICATION_CODE
        String wrongCode = code.equals("000000") ? "111111" : "000000";
        mockMvc.perform(post("/api/users/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"code\": \"%s\"}".formatted(EMAIL, wrongCode)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));

        // 3. 맞는 코드 — 200, 인증 완료
        mockMvc.perform(post("/api/users/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"code\": \"%s\"}".formatted(EMAIL, code)))
            .andExpect(status().isOk());

        // 4. 비밀번호 설정 — 인증 완료 상태에서만 통과한다
        mockMvc.perform(post("/api/users/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"Abcd1234!\"}".formatted(EMAIL)))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("코드가 만료(저장소에서 소실)되면 INVALID_VERIFICATION_CODE 400을 받는다")
    void verify_afterCodeExpired_returnsInvalidCode() throws Exception {
        mockMvc.perform(post("/api/users/email/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\"}".formatted(EMAIL)))
            .andExpect(status().isOk());

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(mailService).sendVerificationMail(eq(EMAIL), codeCaptor.capture());

        // TTL 만료 시뮬레이션 — Redis에서 키가 사라진 상태와 동일
        emailVerificationStore.expireCode(EMAIL);

        mockMvc.perform(post("/api/users/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"code\": \"%s\"}".formatted(EMAIL, codeCaptor.getValue())))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_VERIFICATION_CODE"));
    }

    @Test
    @DisplayName("인증 없이 비밀번호 설정을 시도하면 EMAIL_NOT_VERIFIED 400을 받는다")
    void setPassword_withoutVerification_returnsEmailNotVerified() throws Exception {
        mockMvc.perform(post("/api/users/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"Abcd1234!\"}".formatted(EMAIL)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    }

    @TestConfiguration
    static class FakeStoreConfig {

        @Bean
        @Primary
        InMemoryEmailVerificationStore inMemoryEmailVerificationStore() {
            return new InMemoryEmailVerificationStore();
        }
    }

    /** TTL 없는 인메모리 fake. 만료는 expireCode()로 명시적으로 일으킨다. */
    static class InMemoryEmailVerificationStore implements EmailVerificationStore {

        private final Map<String, String> codes = new HashMap<>();
        private final Set<String> verified = new HashSet<>();
        private final Map<String, String> tempPasswords = new HashMap<>();

        @Override
        public void saveCode(String email, String code) {
            codes.put(email, code);
        }

        @Override
        public Optional<String> findCode(String email) {
            return Optional.ofNullable(codes.get(email));
        }

        @Override
        public void markVerified(String email) {
            verified.add(email);
        }

        @Override
        public boolean isVerified(String email) {
            return verified.contains(email);
        }

        @Override
        public void saveTempPassword(String email, String encodedPassword) {
            tempPasswords.put(email, encodedPassword);
        }

        @Override
        public Optional<String> findTempPassword(String email) {
            return Optional.ofNullable(tempPasswords.get(email));
        }

        @Override
        public void clear(String email) {
            codes.remove(email);
            verified.remove(email);
            tempPasswords.remove(email);
        }

        void expireCode(String email) {
            codes.remove(email);
        }

        void reset() {
            codes.clear();
            verified.clear();
            tempPasswords.clear();
        }
    }
}
