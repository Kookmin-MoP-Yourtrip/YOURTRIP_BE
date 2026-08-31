package backend.yourtrip.global.ai.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI 코스 생성 요청 하나의 전체 예산 (ROADMAP 7-1).
 *
 * <p><b>{@code llm.timeout-ms}와 같은 곳에 두지 않는 이유</b>는 재는 대상이 다르기 때문이다 —
 * 그쪽은 <b>호출 1건</b>의 상한이고 이쪽은 <b>요청 전체</b>의 상한이다. 같은 prefix 아래 두면
 * 둘을 같은 종류의 값으로 착각한 채 튜닝하게 되는데, 실제로는 후자가 전자보다 항상 커야 한다.
 *
 * @param budgetMs {@code CourseDeadline}에 들어가는 값. 기본값 30초는 설계 지연 예산의
 *                 p95 상단(17~24초) 위에 여유를 둔 것이다 — <b>이 값이 p95보다 낮으면 정상
 *                 요청이 504가 되고, 너무 높으면 데드라인이 없는 것과 같아진다.</b>
 *                 설정으로 뺀 것은 8단계 E2E 실측 뒤 조정하기 위해서다
 */
@Validated
@ConfigurationProperties(prefix = "ai.course")
public record AiCourseProperties(

    @Positive
    int budgetMs
) {}
