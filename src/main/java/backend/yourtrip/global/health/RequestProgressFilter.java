package backend.yourtrip.global.health;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 메인 포트에서 워커가 요청을 마칠 때마다 {@link ProgressTracker}에 기록한다.
 *
 * <p><b>성공·실패를 가리지 않는다.</b> 401이든 500이든 응답을 돌려줬다는 것은 워커가 멈추지 않았다는 뜻이다.
 * 이 기록이 답하는 질문은 "요청이 잘 처리되는가"가 아니라 "워커가 일을 끝내고 있는가"다. 그래서
 * {@code finally}에서 기록하고, 예외로 끝난 요청도 센다.
 *
 * <p><b>비동기 요청은 워커를 반납하는 순간을 완료로 센다.</b> {@code CompletableFuture}를 반환하는 컨트롤러
 * (AI 코스 생성)는 첫 디스패치가 끝나면 워커를 돌려주고, 그때 이 {@code finally}가 실행된다. 응답이 실제로
 * 나가는 재디스패치는 {@link OncePerRequestFilter}의 기본값대로 이 필터를 다시 타지 않는다. 판정이 지키려는
 * 것이 "8080의 워커"이고 비동기 대기는 워커를 쓰지 않으므로 이것이 맞는 경계다(설계 문서 3-2절).
 *
 * <p><b>관리 포트 요청은 세지 않는다.</b> 헬스체크 요청이 여기 기록되면 8080이 멈춰도 30초마다 기록이 갱신돼
 * 영원히 UP이 나온다 — 판정 전체가 무력화되는 결함이다. 관리 포트의 필터 구성은 Spring Boot의 자식 컨텍스트
 * 동작에 달려 있으므로, 그것에 기대지 않고 <b>경로로 막는다</b>. 로컬처럼 관리 포트를 나누지 않은 환경에서는
 * 헬스체크가 같은 8080으로 들어오므로 이 방어가 유일한 차단이다. "관리 포트 요청이 기록을 갱신하지 않는다"는
 * 결과는 {@code RequestProgressLivenessIntegrationTest}가 확인한다(어느 쪽 장치가 막았는지는 가리지 않는다).
 *
 * <p>가장 바깥에 둔다({@link Ordered#HIGHEST_PRECEDENCE}). 보안 필터가 거절한 요청도 워커가 응답을 끝낸
 * 것이므로 세야 하고, 안쪽에 두면 그 경우를 놓친다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class RequestProgressFilter extends OncePerRequestFilter {

    /**
     * actuator 기본 경로. {@code management.endpoints.web.base-path}를 바꾸지 않는 이 저장소의 설정을 전제한다 —
     * 바꾸면 이 상수도 함께 바꿔야 한다.
     */
    static final String ACTUATOR_PATH = "/actuator";

    private final ProgressTracker tracker;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.equals(ACTUATOR_PATH) || uri.startsWith(ACTUATOR_PATH + "/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
        FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            tracker.markCompleted();
        }
    }
}
