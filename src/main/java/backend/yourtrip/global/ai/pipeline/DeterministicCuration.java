package backend.yourtrip.global.ai.pipeline;

import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * Curator가 비운 슬롯을 <b>후보 목록에서 결정론적으로 채운다</b> (ROADMAP 7-3).
 *
 * <p>설계의 degrade 표가 "Curator 일부/전 day 실패 → 정렬된 목록의 상위 3개"라고 규정한 자리다.
 * LLM을 다시 부르지 않는다 — 이 폴백이 발동한 상황은 대개 LLM이 느리거나 죽은 상황이고,
 * 거기서 같은 것을 한 번 더 부르면 남은 예산만 태운다.
 *
 * <h2>슬롯 구조를 다시 만들지 않는다</h2>
 * {@code CuratedChoiceValidator}가 이미 <b>Planner의 자리 구성 그대로</b> {@code CuratedSlot}을
 * 만들어 두고, 선택이 없는 자리는 빈 {@code choices}로 남긴다(그 코드의 주석이 "7-3의 결정론적
 * 채움이 그 자리를 메운다"고 지목한다). 그래서 여기서 {@code PlannerPlan}을 다시 받아 자리를
 * 재구성하면 <b>같은 책임을 두 곳이 갖게 된다</b> — 이 함수는 자리 구성이 아니라 <b>빈 자리의
 * 내용물</b>만 소유한다.
 *
 * <h2>외부 호출을 늘리지 않는다</h2>
 * 채워 넣는 후보는 전부 {@code SEEDED}·{@code LISTED}라 좌표·주소를 코드가 승계한다 —
 * {@code GroundingStage}에서 <b>카카오를 한 번도 부르지 않고</b> 통과한다. 폴백이 장애 상황에서
 * 오히려 외부 호출을 늘리는 흔한 실수를 구조적으로 피한다.
 *
 * <h2>후보마저 없으면 빈 채로 둔다</h2>
 * 설계는 여기서 카카오 카테고리 검색으로 한 번 더 보충하라고 했으나 <b>보류했다</b> —
 * 5-9 실측에서 빈 슬롯이 0%였다. 같은 이유로 "0건일 때만 카카오" 폴백이 이미 조건 미발동으로
 * 기각된 선례가 있고(5-9), 발동하지 않는 경로는 테스트로만 살아 있게 된다.
 *
 * <h2>집계를 값으로 돌려준다</h2>
 * 순수 함수로 남기 위해 {@code MeterRegistry}를 만지지 않고 {@link Filled#slotCounts()}로
 * 넘긴다 — 6-7의 {@code CuratedChoiceValidator}가 강등 집계에 대해 세운 패턴 그대로다.
 * 기록은 호출자인 {@code AiCoursePipeline}이 한다.
 */
@Slf4j
public final class DeterministicCuration {

    /**
     * 슬롯당 채우는 최대 개수. {@code CuratedChoiceValidator}의 같은 상수와 맞춘 값이다 —
     * 후보 3개는 1순위가 그라운딩에서 탈락했을 때 2·3순위가 승격하기 위한 것이라, 폴백으로
     * 채운 자리도 같은 여유를 가져야 한다.
     */
    static final int MAX_CHOICES = 3;

    private DeterministicCuration() {
    }

    /**
     * 채움 결과와 슬롯 집계.
     *
     * @param slotCounts {@link SlotFillOutcome}별 슬롯 수. <b>세 값이 전체를 나누므로 분모를 따로
     *                   들고 다닐 필요가 없다</b> — 이 맵의 합이 곧 전체 슬롯 수다
     */
    public record Filled(List<CuratedDay> days, Map<SlotFillOutcome, Integer> slotCounts) {

        public Filled {
            days = days == null ? List.of() : List.copyOf(days);
            slotCounts = slotCounts == null ? Map.of() : Map.copyOf(slotCounts);
        }

        /** 폴백이 채운 슬롯 수. 0이면 Curator가 자기 몫을 다 했다는 뜻이다. */
        public int fallbackSlots() {
            return slotCounts.getOrDefault(SlotFillOutcome.FALLBACK, 0);
        }
    }

    /**
     * 선택이 비어 있는 슬롯만 후보 목록 상위 {@value #MAX_CHOICES}개로 채운다.
     *
     * <p>목록 순서를 그대로 쓰는 것이 이 함수의 전부다 — 5-8이 이미 사전식으로 정렬해
     * 시드 그룹을 앞에 뒀으므로, 상위 N개를 자르면 자연히 인기 후보가 온다. 여기서 다시
     * 점수를 매기면 <b>정렬 규칙이 두 곳으로 갈린다</b>.
     *
     * @param curated Curator 결과. 자리 구성은 이미 확정돼 있고 일부 자리의 선택이 비어 있다
     * @param pool    후보 풀. {@code null}이거나 비어 있으면 채울 것이 없어 입력이 그대로 나온다
     * @return 같은 day 수·같은 자리 구성의 새 목록과 슬롯 집계
     */
    public static Filled fill(List<CuratedDay> curated, CandidatePool pool) {
        if (curated == null || curated.isEmpty()) {
            return new Filled(List.of(), Map.of());
        }
        CandidatePool candidatePool = pool == null ? CandidatePool.empty() : pool;

        Map<SlotFillOutcome, Integer> counts = new EnumMap<>(SlotFillOutcome.class);
        List<CuratedDay> result = new ArrayList<>(curated.size());

        for (CuratedDay day : curated) {
            List<CuratedSlot> slots = new ArrayList<>(day.slots().size());
            for (CuratedSlot slot : day.slots()) {
                if (!slot.choices().isEmpty()) {
                    count(counts, SlotFillOutcome.CURATOR);
                    slots.add(slot);
                    continue;
                }
                CuratedSlot filled = fillSlot(day.day(), slot.slotType(), candidatePool);
                count(counts, filled.choices().isEmpty()
                    ? SlotFillOutcome.UNFILLED : SlotFillOutcome.FALLBACK);
                slots.add(filled);
            }
            result.add(new CuratedDay(day.day(), slots));
        }

        Filled filled = new Filled(result, counts);
        if (filled.fallbackSlots() > 0) {
            log.warn("Curator 가 비운 슬롯 {}개를 후보 목록 상위 {}개로 채웠다 (ROADMAP 7-3). 집계: {}",
                filled.fallbackSlots(), MAX_CHOICES, counts);
        }
        return filled;
    }

    private static void count(Map<SlotFillOutcome, Integer> counts, SlotFillOutcome outcome) {
        counts.merge(outcome, 1, Integer::sum);
    }

    private static CuratedSlot fillSlot(int day, SlotType slotType, CandidatePool pool) {
        List<PlaceCandidate> candidates = pool.findOrEmpty(day, slotType).candidates();
        int limit = Math.min(MAX_CHOICES, candidates.size());

        List<CuratedPlace> choices = new ArrayList<>(limit);
        for (int listIndex = 0; listIndex < limit; listIndex++) {
            // listIndex 는 0-based 리스트 인덱스 그대로여야 한다 — CandidateSlot.at 의 계약이고,
            // 어긋나면 GroundingStage 가 좌표를 승계하지 못해 이 후보가 SUGGESTED 로 강등된다.
            PlaceCandidate candidate = candidates.get(listIndex);
            choices.add(new CuratedPlace(candidate.source(), listIndex, candidate.name()));
        }
        return new CuratedSlot(slotType, choices);
    }
}
