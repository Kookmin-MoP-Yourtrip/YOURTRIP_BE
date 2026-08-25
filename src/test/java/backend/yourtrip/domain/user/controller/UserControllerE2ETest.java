package backend.yourtrip.domain.user.controller;

import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * SecurityConfig의 {@code anyRequest().authenticated()}가 /api/users/logout에도
 * 화이트리스트 없이 그대로 적용돼, 인증 실패 시 403(Spring Security 기본 응답)을
 * 반환하는지 검증한다 — Swagger 문서를 401이 아니라 403으로 고친 근거가 되는 테스트다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerE2ETest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @Test
    @DisplayName("POST /api/users/logout - 인증된 사용자는 200 OK를 받고 UserService.logout()이 호출된다")
    @WithMockUser
    void logout_authenticated_returnsOk() throws Exception {
        mockMvc.perform(post("/api/users/logout"))
            .andDo(print())
            .andExpect(status().isOk());

        verify(userService).logout();
    }

    @Test
    @DisplayName("POST /api/users/logout - Authorization 헤더가 없으면 403 Forbidden을 받는다")
    void logout_unauthenticated_returnsForbidden() throws Exception {
        mockMvc.perform(post("/api/users/logout"))
            .andDo(print())
            .andExpect(status().isForbidden());
    }
}
