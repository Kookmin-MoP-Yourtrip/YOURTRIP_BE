package backend.yourtrip.global.common;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientRequestUtils {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";

    private ClientRequestUtils() {
    }

    /**
     * 리버스 프록시(로드밸런서 등)를 거치면 소켓 연결 정보(getRemoteAddr)가 프록시 주소로 잡히므로,
     * X-Forwarded-For 헤더가 있으면 그 첫 번째 값(최초 클라이언트 IP)을 우선 사용하고 없을 때만 폴백한다.
     */
    public static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader(FORWARDED_FOR_HEADER);
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
