package backend.yourtrip.global.ai.pipeline;

import java.util.List;

/**
 * 파이프라인의 최종 산출 — <b>저장 직전 상태</b>다.
 *
 * <p>영속화하지 않는 이유는 이 타입이 {@code global.ai}에 있고 영속화는
 * {@code domain.mycourse}의 책임이기 때문이다. 그 변환은 {@code AiCourseDraftMapper}(8-1)가
 * 맡아 {@code ResolvedDay}/{@code ResolvedPlace}로 옮겨 {@code AiCoursePersister}에 넘긴다.
 *
 * @param title   코스 제목. Planner가 짓는다 — LLM 산출물 중 저장까지 가는 유일한 문자열이라
 *                {@code AiCoursePersister.save}가 DTO가 아니라 이 값 하나를 받는다
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
