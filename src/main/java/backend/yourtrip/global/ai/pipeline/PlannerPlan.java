package backend.yourtrip.global.ai.pipeline;

import java.util.List;

/**
 * Planner 에이전트의 출력 전체 (ROADMAP 6-2). <b>계약만 5단계가 정의하고 채우는 것은 6단계다.</b>
 *
 * <p>Planner를 별도 단계로 두는 이유는 "단계를 잘게 나눠서"가 아니라 <b>Curator를 day별로 병렬
 * 실행하려면 day별 권역이 먼저 확정돼야 하기 때문</b>이다. 후보 공급도 같은 이유로 여기에 의존한다 —
 * 어느 권역에서 무엇을 검색할지가 정해져야 시더와 TourAPI를 부를 수 있다.
 */
public record PlannerPlan(String title, String concept, List<PlannerDayPlan> days) {

    public PlannerPlan {
        days = days == null ? List.of() : List.copyOf(days);
    }
}
