package backend.yourtrip.domain.user.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import backend.yourtrip.global.mail.service.MailService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Redis 장애 시 이메일 인증 경로가 fail-closed로 동작하는지 검증한다(#117).
 * <p>
 * fake 오버라이드 없이 실제 RedisEmailVerificationStore를 쓴다 — test 프로필의 Redis가
 * 의도적으로 아무도 듣지 않는 포트(application-test.yml)라, 이 환경 자체가 곧 Redis 장애
 * 상황이다. 인증코드는 Redis가 유일한 원본이라 폴백이 불가능하므로, 실패를 400으로
 * 위장하지 않고 503으로 정직하게 거부해야 하며 메일도 나가면 안 된다(고아 메일 방지).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EmailVerificationFailClosedTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MailService mailService;

    @Test
    @DisplayName("Redis 장애 시 인증번호 발송은 503 VERIFICATION_SERVICE_UNAVAILABLE로 거부되고 메일은 발송되지 않는다")
    void sendVerificationCode_redisDown_returns503AndNoMail() throws Exception {
        mockMvc.perform(post("/api/users/email/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"fail-closed@example.com\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("VERIFICATION_SERVICE_UNAVAILABLE"));

        verify(mailService, never()).sendVerificationMail(anyString(), anyString());
    }

    @Test
    @DisplayName("Redis 장애 시 인증번호 검증도 503으로 거부된다 — 400(사용자 잘못)으로 위장하지 않는다")
    void verifyCode_redisDown_returns503() throws Exception {
        mockMvc.perform(post("/api/users/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"fail-closed@example.com\", \"code\": \"123456\"}"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("VERIFICATION_SERVICE_UNAVAILABLE"));
    }
}
