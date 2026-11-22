package backend.yourtrip.global.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 진행 여부 판정의 직접 확인 대상. 메인 포트의 워커가 요청 하나를 받아 끝낼 수 있는지만 본다.
 *
 * <p><b>DB·Redis를 건드리지 않는다.</b> 의존성을 확인하면 재시작으로 고쳐지지 않는 장애(RDS 순간 장애 등)로
 * 인스턴스를 교체하게 된다 — liveness에서 의존성을 빼 둔 설계와 같은 이유다.
 *
 * <p>외부에서는 닿지 않는다. 호출자는 같은 JVM의 {@link HttpMainPortProbe}뿐이고, ALB 리스너 규칙이 {@code /internal}을
 * 차단한다(terraform/prod/alb.tf). 이 요청은 {@code http.server.requests} 지표에서 뺀다({@link HealthConfig}) — 한가할 때
 * 30초마다 들어오는 요청이 부하테스트 수치와 대시보드에 섞이지 않게 하기 위해서다.
 */
@RestController
public class InternalPingController {

    @GetMapping(HttpMainPortProbe.PING_PATH)
    public ResponseEntity<Void> ping() {
        return ResponseEntity.noContent().build();
    }
}
