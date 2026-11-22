package backend.yourtrip.global.health;

/**
 * 메인 포트(8080)가 지금 요청을 받아 처리할 수 있는지 직접 확인한다.
 *
 * <p>인터페이스로 둔 이유는 {@link RequestProgressHealthIndicator}의 판정 규칙을 네트워크 없이 단위 테스트하기
 * 위해서다. 실제 구현은 {@link HttpMainPortProbe} 하나뿐이다.
 */
public interface MainPortProbe {

    /** 제한 시간 안에 2xx 응답을 받으면 true. 연결 실패·타임아웃·그 밖의 응답은 모두 false다. */
    boolean ping();
}
