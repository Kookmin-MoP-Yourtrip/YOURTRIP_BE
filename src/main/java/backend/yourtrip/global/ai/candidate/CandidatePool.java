package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 코스 한 건의 후보 풀 전체 — {@code (day, slotType)}로 조회한다 (ROADMAP 5-8).
 *
 * <p><b>슬롯 타입 단위이지 슬롯 자리 단위가 아니다.</b> day의 슬롯 구성에 같은 타입이 여러 번 올 수
 * 있는데({@code [ATTRACTION, MEAL, CAFE, ATTRACTION, MEAL]}) 그때마다 같은 쿼리를 다시 던지면
 * 네이버 쿼터만 두 배로 쓰고 결과는 같다. Curator는 두 자리에서 같은 목록을 보고 서로 다른 후보를
 * 고른다.
 *
 * <p><b>빈 풀은 실패가 아니다.</b> 네이버·TourAPI가 동시에 죽으면 여기가 비고, Curator는 파라메트릭
 * 제안만 내는 초안 구조로 degrade한다 — 새 hard fail 지점이 생기지 않는다(설계의 부분 실패 전략).
 */
public record CandidatePool(List<CandidateSlot> slots) {

    public CandidatePool {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }

    public static CandidatePool empty() {
        return new CandidatePool(List.of());
    }

    public Optional<CandidateSlot> find(int day, SlotType slotType) {
        return slots.stream()
            .filter(slot -> slot.day() == day && slot.slotType() == slotType)
            .findFirst();
    }

    /** 5-2가 목록 승계에 쓰는 조회 — 슬롯이 없으면 빈 슬롯으로 다뤄 분기를 하나 줄인다. */
    public CandidateSlot findOrEmpty(int day, SlotType slotType) {
        return find(day, slotType).orElseGet(() -> CandidateSlot.empty(day, slotType));
    }

    public boolean isEmpty() {
        return slots.stream().allMatch(CandidateSlot::isEmpty);
    }

    /** 로그·실측용 요약 — day별 슬롯 수와 후보 수. */
    public Map<Integer, Integer> candidateCountByDay() {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (CandidateSlot slot : slots) {
            counts.merge(slot.day(), slot.candidates().size(), Integer::sum);
        }
        return counts;
    }
}
