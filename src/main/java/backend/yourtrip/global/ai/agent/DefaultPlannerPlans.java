package backend.yourtrip.global.ai.agent;

import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.ArrayList;
import java.util.List;

/**
 * Planner 없이도 파이프라인이 굴러가는 <b>결정론적 기본 플랜</b> (ROADMAP 6-3·7-3).
 *
 * <p><b>쓰이는 곳이 둘이고, 그래서 여기 하나로 둔다.</b>
 * <ul>
 *   <li>6-3 — Planner 가 day 를 모자라게 냈을 때 빈 자리를 채운다</li>
 *   <li>7-3 — Planner 호출 자체가 실패했을 때의 폴백("degrade, don't fail")</li>
 * </ul>
 * 두 곳이 서로 다른 기본값을 쓰면 "Planner 가 반쯤 실패한 코스"와 "완전히 실패한 코스"의 성격이
 * 달라지는데, 그건 사용자에게 설명할 수 없는 차이다.
 *
 * <p><b>{@code anchor = area = location}이 이 플랜의 핵심이다.</b> 권역을 나눌 근거가 없으므로
 * 나누지 않고, 지오코딩 캐스케이드(4-8)의 마지막 단계인 {@code location}을 그대로 쓴다 —
 * 그 단계에는 이름 게이트가 걸려 있지 않아 <b>반드시 좌표를 얻는다.</b>
 */
public final class DefaultPlannerPlans {

    /**
     * 기본 슬롯 구성. <b>관광 2 · 식사 2 · 카페 1</b>이고 설계 문서의 예시와 같다.
     *
     * <p>다섯 개인 이유는 {@code RouteOptimizer}의 완전탐색 임계값이다 — 3-6 벤치마크에서
     * {@code n=7}은 3일 1.77ms 인데 {@code n=8}은 15ms 로 지연 예산({@code <10ms})을 이미 넘는다.
     * 다섯이면 여유가 크고, 하루에 다섯 곳은 사용자 입장에서도 무리가 없다.
     */
    public static final List<SlotType> DEFAULT_SLOTS = List.of(
        SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE, SlotType.ATTRACTION, SlotType.MEAL);

    private DefaultPlannerPlans() {
    }

    /** 여행 전체의 기본 플랜. */
    public static PlannerPlan of(String location, int days) {
        String area = safeLocation(location);
        List<PlannerDayPlan> dayPlans = new ArrayList<>(Math.max(days, 0));
        for (int day = 1; day <= days; day++) {
            dayPlans.add(dayOf(area, day));
        }
        return new PlannerPlan(defaultTitle(area, days), defaultConcept(area), dayPlans);
    }

    /** day 하나의 기본 계획. 6-3 이 모자란 day 를 채울 때 쓴다. */
    public static PlannerDayPlan dayOf(String location, int day) {
        String area = safeLocation(location);
        return new PlannerDayPlan(day, area, area, defaultTheme(area), null, DEFAULT_SLOTS);
    }

    public static String defaultTitle(String location, int days) {
        return "%s %d일 여행".formatted(safeLocation(location), Math.max(days, 1));
    }

    public static String defaultConcept(String location) {
        return "%s의 대표적인 장소를 둘러보는 코스".formatted(safeLocation(location));
    }

    public static String defaultTheme(String location) {
        return "%s 둘러보기".formatted(safeLocation(location));
    }

    /**
     * {@code location}이 비어 있을 때의 표기.
     *
     * <p>여기까지 빈 값이 올 일은 없다({@code AICourseCreateRequest}가 검증한다). 그럼에도 막는 것은
     * 이 클래스가 <b>"다른 게 다 실패해도 이건 된다"는 마지막 보루</b>라, 여기서 NPE 가 나면 폴백이
     * 폴백의 구실을 못 하기 때문이다.
     */
    private static String safeLocation(String location) {
        return location == null || location.isBlank() ? "국내" : location.trim();
    }
}
