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
 * @param budgetMs {@code CourseDeadline}에 들어가는 값(운영 35초, #189). <b>낮추면 응답은 빨라지지만
 *                 Curator 폴백이 늘고, 높이면 폴백은 줄지만 마감에 걸린 사용자가 오래 기다린다</b> —
 *                 예산을 넘긴 요청은 실패가 아니라 품질이 떨어진 200 으로 나가기 때문이다.
 *                 값의 근거와 불변식(예산 + 후처리 &lt; 클라이언트·ALB 60초)은 {@code application.yml} 주석에 있다
 */
@Validated
@ConfigurationProperties(prefix = "ai.course")
public record AiCourseProperties(

    @Positive
    int budgetMs
) {}
