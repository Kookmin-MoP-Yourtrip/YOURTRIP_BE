package backend.yourtrip.global.exception.errorCode;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * AI 코스 생성 파이프라인이 사용자에게 올리는 실패 (ROADMAP 7-2).
 *
 * <p>{@link ErrorCode} 구현이라 {@code GlobalExceptionHandler}는 수정하지 않는다 — 응답의
 * {@code code}는 핸들러가 {@code Enum#name()}으로 뽑고 상태는 인터페이스 메서드로 읽는다.
 * 부수 효과로 <b>상수 이름이 그대로 공개 API 계약</b>이 되므로 한 번 붙이면 바꾸지 않는다.
 *
 * <h2>설계가 지정한 다섯 중 둘만 만든다</h2>
 * 설계(운영 관심사 "신규 {@code AiCourseErrorCode}")는 다섯 개를 열거했지만, 같은 문서의
 * <b>degrade, don't fail</b> 표가 그중 셋의 발화 경로를 스스로 막는다.
 *
 * <ul>
 *   <li>{@code AI_PLAN_FAILED} — Planner 실패는 {@code DefaultPlannerPlans}의 결정론적 기본
 *       플랜으로 흡수된다(7-3). 기본 플랜을 만들지 못하는 경우가 없으므로 이 코드에 도달할 수 없다</li>
 *   <li>{@code AI_RESPONSE_INVALID} — 깨진 응답은 어댑터 안에서 의미 재시도까지 소진한 뒤
 *       {@code LlmResponseException}으로 올라오는데, 그 예외를 받는 두 지점(Planner·Curator)이
 *       모두 degrade로 끝난다. 결국 위와 같은 경로로 수렴한다</li>
 *   <li>{@code AI_COURSE_BUSY} — 세마포어 포화는 {@code OpenAiLlmClient}의 permit 획득 실패이고,
 *       그 실패가 다른 전송 실패와 <b>같은 {@code LlmTransportException} 타입</b>으로 나와
 *       구분 자체가 되지 않는다. 구분하더라도 위 둘과 같은 degrade에 먹힌다</li>
 * </ul>
 *
 * <p><b>발화하지 않는 상수를 미리 두지 않는 이유</b>는 이 enum이 곧 "이 기능이 사용자에게
 * 실패하는 방식의 전부"라는 목록이기 때문이다. 쓰이지 않는 항목이 섞이면 그 목록이
 * 설계 의도의 기록으로 바뀌고, 실제 동작을 읽는 문서로서의 값을 잃는다. 폴백 정책이 바뀌어
 * 실제로 필요해지면 그때 추가한다.
 */
@Getter
@AllArgsConstructor
public enum AiCourseErrorCode implements ErrorCode {

    /**
     * <b>이 파이프라인의 유일한 hard fail</b> (ROADMAP 7-4). 전 day의 장소가 0개일 때만 —
     * 좌표 없는 코스는 지도 표시·동선이라는 핵심 가치를 잃는다. 좌표 소스가 네이버·TourAPI·카카오
     * 셋이라 카카오 단독 장애로는 여기 오지 않는다.
     */
    AI_GROUNDING_FAILED("장소 정보를 확인하지 못했습니다. 잠시 후 다시 시도해주세요",
        HttpStatus.SERVICE_UNAVAILABLE),

    /**
     * 위와 같은 지점에서 갈린다 — 장소가 0개인데 <b>예산이 소진돼 있었다면</b> 이쪽이다.
     * 사용자에게 보이는 결과는 같지만 "시간이 모자랐다"와 "바깥 세상에 데이터가 없었다"를 뭉치면
     * 운영에서 어느 쪽을 고쳐야 할지 알 수 없다.
     */
    AI_COURSE_TIMEOUT("AI 코스 생성이 지연되고 있습니다. 잠시 후 다시 시도해주세요",
        HttpStatus.GATEWAY_TIMEOUT);

    private final String message;
    private final HttpStatus status;

}
