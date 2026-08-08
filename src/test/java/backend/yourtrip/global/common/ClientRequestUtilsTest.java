package backend.yourtrip.global.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ClientRequestUtilsTest {

    @Test
    @DisplayName("X-Forwarded-For 헤더가 있으면 첫 번째 IP를 사용한다")
    void resolveClientIp_WithForwardedForHeader_ReturnsFirstIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getHeader("X-Forwarded-For")).willReturn("203.0.113.1, 10.0.0.1, 10.0.0.2");

        String clientIp = ClientRequestUtils.resolveClientIp(request);

        assertThat(clientIp).isEqualTo("203.0.113.1");
    }

    @Test
    @DisplayName("X-Forwarded-For 헤더가 없으면 getRemoteAddr로 폴백한다")
    void resolveClientIp_WithoutForwardedForHeader_FallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getHeader("X-Forwarded-For")).willReturn(null);
        given(request.getRemoteAddr()).willReturn("192.168.0.10");

        String clientIp = ClientRequestUtils.resolveClientIp(request);

        assertThat(clientIp).isEqualTo("192.168.0.10");
    }

    @Test
    @DisplayName("X-Forwarded-For 헤더가 빈 문자열이면 getRemoteAddr로 폴백한다")
    void resolveClientIp_WithBlankForwardedForHeader_FallsBackToRemoteAddr() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        given(request.getHeader("X-Forwarded-For")).willReturn("  ");
        given(request.getRemoteAddr()).willReturn("192.168.0.10");

        String clientIp = ClientRequestUtils.resolveClientIp(request);

        assertThat(clientIp).isEqualTo("192.168.0.10");
    }
}
