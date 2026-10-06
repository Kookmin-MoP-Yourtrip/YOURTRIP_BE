package backend.yourtrip.global.ai.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 코스 생성 요청의 서버당 동시 입장 상한 (#192).
 *
 * <p><b>{@link AiCourseProperties}에 필드를 더하지 않고 따로 둔 이유</b>는 재는 대상이 다르기
 * 때문이다 — 그쪽은 받아들인 요청 하나가 쓸 수 있는 <b>시간</b>이고, 이쪽은 동시에 받아들일 요청의
 * <b>수</b>다. 또 그쪽은 파이프라인이 읽고 이쪽은 서비스 진입부가 읽는다. 파이프라인을 직접
 * 조립하는 벤치마크·프로브 테스트가 입장 제한과 무관하게 남는 것도 이 분리 덕분이다.
 *
 * @param maxConcurrentRequests 서버 한 대가 동시에 처리하는 AI 코스 생성 요청 수. 넘치면 기다리지 않고
 *                              바로 429로 거절한다. <b>{@code llm.max-concurrent-calls}와 함께 움직인다</b> —
 *                              슬롯이 늘면 받아들일 수 있는 요청도 는다. 값의 근거는 {@code application.yml}
 *                              주석에 있다
 * @param retryAfterSeconds     거절 응답의 {@code Retry-After}. 클라이언트에게 주는 재시도 간격의 힌트다
 */
@Validated
@ConfigurationProperties(prefix = "ai.course.admission")
public record AiAdmissionProperties(

    @Positive
    int maxConcurrentRequests,

    @Positive
    int retryAfterSeconds
) {}
