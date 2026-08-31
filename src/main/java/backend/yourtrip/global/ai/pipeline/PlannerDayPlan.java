package backend.yourtrip.global.ai.pipeline;

import backend.yourtrip.global.ai.route.SlotType;
import java.time.LocalTime;
import java.util.List;

/**
 * Planner가 정한 day 하나의 권역과 슬롯 구성 — <b>후보 공급의 입력</b>이다 (ROADMAP 6-2).
 *
 * <p><b>이 record는 5단계가 정의하고 6단계가 채운다.</b> {@code CandidateRetrievalStage}는
 * Planner 앞이 아니라 뒤에 놓이는데 Planner 구현이 6단계라, 계약이 먼저 있어야 스테이지를 만들 수
 * 있기 때문이다. 여기에는 값 타입만 두고 에이전트·프롬프트는 6단계가 가져온다.
 *
 * <p><b>Planner는 장소명을 한 개도 생성하지 않는다.</b> {@code anchor}는 코스에 들어가는 장소가
 * 아니라 <b>권역 중심을 지오코딩하기 위한 검색 기준점</b>일 뿐이다. 없는 랜드마크를 지어내도
 * 카카오가 못 찾으면 캐스케이드가 다음 단계로 넘어가므로 환각이 파이프라인에 박히지 않는다(4-8).
 *
 * @param area   {@code "황리단길·대릉원 일대"} 같은 자연어 권역. <b>시군구로 바꾸지 않는다</b> —
 *               한 도시를 day 단위로 쪼개는 것이 이 필드의 역할이라(황리단길 / 보문단지 / 감포는
 *               전부 "경주시") 행정구역으로 바꾸면 그 locality를 잃는다. 네이버 쿼리 접두사로도 쓰인다
 * @param anchor 권역 안의 구체적 랜드마크 하나("대릉원"). 자연어 {@code area}를 그대로 지오코딩하면
 *               가운뎃점·"일대" 때문에 결과가 흔들려서 따로 받는다
 * @param theme  그 day를 한 줄로 요약한 테마("한옥 골목 산책과 야경"). <b>Curator 프롬프트의 입력</b>이다 —
 *               코스 전체의 {@code concept}만 주면 day마다 무엇이 달라야 하는지가 전달되지 않는다
 * @param dayStartTime 그 day를 시작하는 시각. <b>{@code null}이면 {@code RouteRequest.DEFAULT_DAY_START}</b>.
 *               표시용 값이 아니라 <b>최적화 입력</b>이다 — 시간 모델이 {@code t[0] = dayStartTime}이라,
 *               시작이 밀리면 식사 시간창 벌점이 달라져 <b>같은 장소 집합이라도 최적 순열이 바뀐다</b>.
 *               <p><b>종료 시각은 받지 않는다.</b> 받으면 3-5의 축소→드롭 절차가 살아나 장소가 조용히
 *               사라지는데, 그 대가를 치를 만큼 LLM이 종료 시각을 잘 안다는 근거가 없다
 * @param slots  day의 슬롯 구성. <b>같은 슬롯 타입이 여러 번 올 수 있다</b>
 *               ({@code [ATTRACTION, MEAL, CAFE, ATTRACTION, MEAL]})
 */
public record PlannerDayPlan(int day, String area, String anchor, String theme,
                             LocalTime dayStartTime, List<SlotType> slots) {

    public PlannerDayPlan {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    /**
     * 테마와 시작 시각 없이 권역과 슬롯만 있는 day.
     *
     * <p>테스트와 후보 공급 프로브처럼 <b>권역만 있으면 되는 호출부</b>를 위한 것이다
     * ({@code RouteRequest.of}가 세운 선례). 운영 경로에서는 Planner가 전 필드를 채운다.
     */
    public static PlannerDayPlan of(int day, String area, String anchor, List<SlotType> slots) {
        return new PlannerDayPlan(day, area, anchor, null, null, slots);
    }
}
