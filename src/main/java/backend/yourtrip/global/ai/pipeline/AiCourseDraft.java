package backend.yourtrip.global.ai.pipeline;

import java.util.List;

/**
 * 파이프라인의 최종 산출 — <b>저장 직전 상태</b>다.
 *
 * <p>영속화하지 않는 이유는 이 타입이 {@code global.ai}에 있고 영속화는
 * {@code domain.mycourse}의 책임이기 때문이다. 8-1이 이 값을 {@code ResolvedDay}/
 * {@code ResolvedPlace}로 옮겨 {@code AiCoursePersister}에 넘긴다.
 *
 * @param title   코스 제목. Planner가 짓는다 — 현재 {@code TravelCourseMapper.toAICourseEntity}가
 *                {@code GeminiCourseDto}에서 쓰는 값이 이것 하나라, 8단계 교체의 접점이 된다
 * @param concept 컨셉 문장. V1에서 저장 대상은 아니지만 Planner 출력의 일부라 함께 나른다
 */
public record AiCourseDraft(
    String title,
    String concept,
    List<AiCourseDay> days
) {

    public AiCourseDraft {
        days = days == null ? List.of() : List.copyOf(days);
    }
}
