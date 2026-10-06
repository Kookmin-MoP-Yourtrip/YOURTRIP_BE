package backend.yourtrip.global.ai.agent;

import backend.yourtrip.global.ai.agent.dto.CuratorResponse;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Curator 응답을 검증해 자리별 선택으로 바꾼다 (ROADMAP 6-7, #194).
 *
 * <h2>목록 선택은 번호가 전부다</h2>
 * {@code SEEDED}·{@code LISTED}는 그라운딩에서 <b>카카오를 부르지 않고</b> 목록 항목의 좌표·주소를
 * 승계한다. 그 생략이 안전한 이유는 "목록에 있는 것은 실존이 이미 확인됐다"이다. 예전 응답은 번호와
 * 상호명을 함께 받아 둘을 대조했는데, 그건 <b>상호명을 적게 한 탓에 생긴 위조 통로</b>(목록에 없는
 * 이름에 아무 번호나 붙이기)를 다시 막는 장치였다. #194 에서 목록 선택은 번호만 받도록 바꿔
 * <b>통로 자체를 닫았다</b> — 번호가 범위 안이면 그 항목은 정의상 목록에 있다. 출처·이름·좌표를
 * 전부 그 항목에서 가져오므로 모델이 적은 값은 남지 않는다.
 *
 * <h2>범위 밖 번호는 이름이 있을 때만 강등한다</h2>
 * 이름이 함께 왔다면 {@code SUGGESTED}로 내려 카카오 이름 게이트에 맡긴다 — 실존하면 살아남고
 * 환각이면 거기서 걸린다. 이름이 없으면 물어볼 검색어가 없어 버린다.
 *
 * <h2>순수 함수 — 메트릭을 직접 올리지 않는다</h2>
 * 집계를 {@link CurationOutcome}에 실어 돌려주고 올리는 것은 호출자가 한다. 그래야 이 클래스가
 * {@code MeterRegistry} 없이 완전히 결정론적으로 테스트되고, 태그 문자열도 한 곳
 * ({@code AiCourseMetrics})에만 남는다(5-6 이 세운 주입 패턴).
 */
@Slf4j
public final class CuratedChoiceValidator {

    /** 슬롯 하나가 가질 수 있는 선택의 수. 넘치면 앞에서부터 자른다 — 순서가 곧 선호도다. */
    static final int MAX_CHOICES = 3;

    private CuratedChoiceValidator() {
    }

    /**
     * @param day      Planner 가 정한 자리 구성. <b>자리의 개수와 종류는 여기가 정본이다</b>
     * @param pool     그 day 의 후보 풀. {@code listIndex}가 가리키는 대상을 여기서 찾는다
     * @param response Curator 응답. {@code null}이면 빈 day 로 degrade 한다
     */
    public static CurationOutcome validate(PlannerDayPlan day, CandidatePool pool,
        CuratorResponse response) {
        List<SlotType> slotTypes = day.slots();
        CandidatePool candidatePool = pool == null ? CandidatePool.empty() : pool;
        Map<DemotionReason, Integer> demotions = new EnumMap<>(DemotionReason.class);

        // 자리 번호 → 그 자리에 대한 응답. 자리 구성은 Planner 가 정하므로, 응답에 없는 자리는
        // 빈 선택으로 남는다(7-3 의 결정론적 채움이 그 자리를 메운다).
        Map<Integer, CuratorResponse.Slot> byPosition = indexByPosition(response, slotTypes.size());

        List<CuratedSlot> slots = new ArrayList<>(slotTypes.size());
        for (int position = 0; position < slotTypes.size(); position++) {
            SlotType slotType = slotTypes.get(position);
            CuratorResponse.Slot raw = byPosition.get(position);
            slots.add(new CuratedSlot(slotType,
                validateChoices(raw, slotType, candidatePool, day.day(), demotions)));
        }
        return new CurationOutcome(new CuratedDay(day.day(), slots), Map.copyOf(demotions));
    }

    // ── ① 자리 매핑 — 범위 밖·중복은 강등이 아니라 폐기다 ─────────────────────

    private static Map<Integer, CuratorResponse.Slot> indexByPosition(CuratorResponse response,
        int slotCount) {
        Map<Integer, CuratorResponse.Slot> byPosition = new LinkedHashMap<>();
        if (response == null || response.slots() == null) {
            return byPosition;
        }
        Set<Integer> seen = new HashSet<>();
        for (CuratorResponse.Slot slot : response.slots()) {
            Integer position = slot == null ? null : slot.slotIndex();
            if (position == null || position < 0 || position >= slotCount) {
                // 어느 자리의 선택인지 알 수 없으면 놓을 자리가 없다. 강등해도 갈 곳이 없어 버린다.
                log.warn("Curator 가 없는 자리 {} 를 지목했다 — 그 슬롯을 버린다(자리 수: {})",
                    position, slotCount);
                continue;
            }
            if (!seen.add(position)) {
                // 먼저 온 쪽을 남긴다. 나중 것을 남기면 "응답 순서"가 결과를 바꾸는데,
                // 그건 모델이 통제하는 축이라 우리가 재현할 수 없다.
                log.warn("Curator 가 자리 {} 를 두 번 채웠다 — 먼저 온 것을 쓴다", position);
                continue;
            }
            byPosition.put(position, slot);
        }
        return byPosition;
    }

    // ── ② 선택 검증 ──────────────────────────────────────────────────────────

    private static List<CuratedPlace> validateChoices(CuratorResponse.Slot raw, SlotType slotType,
        CandidatePool pool, int day, Map<DemotionReason, Integer> demotions) {
        if (raw == null || raw.choices() == null || raw.choices().isEmpty()) {
            return List.of();
        }

        // 번호가 가리키는 목록은 Planner 의 자리 종류로 찾는다 — 정본이 Planner 이기 때문이다.
        CandidateSlot candidates = pool.findOrEmpty(day, slotType);
        List<CuratedPlace> validated = new ArrayList<>(MAX_CHOICES);
        for (CuratorResponse.Choice choice : raw.choices()) {
            if (validated.size() >= MAX_CHOICES) {
                log.debug("day {} 자리 {}: 선택이 {}개를 넘어 뒤를 자른다", day, raw.slotIndex(),
                    MAX_CHOICES);
                break;
            }
            validateChoice(choice, candidates, demotions).ifPresent(validated::add);
        }
        return validated;
    }

    private static Optional<CuratedPlace> validateChoice(CuratorResponse.Choice choice,
        CandidateSlot candidates, Map<DemotionReason, Integer> demotions) {
        if (choice == null) {
            return Optional.empty();
        }
        String placeName = trimmed(choice.placeName());

        if (choice.listIndex() == null) {
            if (placeName == null) {
                // 번호도 이름도 없으면 목록에서도 카카오에서도 찾을 수 없다.
                log.warn("Curator 가 번호도 상호명도 없는 선택을 냈다 — 버린다");
                return Optional.empty();
            }
            return Optional.of(new CuratedPlace(CandidateSourceType.SUGGESTED, null, placeName));
        }

        Optional<PlaceCandidate> referenced = candidates.at(choice.listIndex());
        if (referenced.isEmpty()) {
            if (placeName == null) {
                log.warn("Curator 가 목록 범위 밖 번호 {} 를 냈다(목록 {}건) — 검색어가 없어 버린다",
                    choice.listIndex(), candidates.candidates().size());
                return Optional.empty();
            }
            return Optional.of(demote(placeName, DemotionReason.INDEX_OUT_OF_RANGE, demotions));
        }

        // 출처·이름은 모델이 아니라 **목록**이 정한다. 출처는 5-6 메트릭의 source 태그가 되므로
        // 틀리면 지표가 오염되고, 이름은 그라운딩·로그가 그대로 쓴다.
        PlaceCandidate candidate = referenced.get();
        return Optional.of(
            new CuratedPlace(candidate.source(), choice.listIndex(), candidate.name()));
    }

    private static CuratedPlace demote(String placeName, DemotionReason reason,
        Map<DemotionReason, Integer> demotions) {
        demotions.merge(reason, 1, Integer::sum);
        return new CuratedPlace(CandidateSourceType.SUGGESTED, null, placeName);
    }

    private static String trimmed(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    /**
     * 검증 결과와 <b>강등 집계</b>.
     *
     * <p>집계를 값으로 돌려주는 이유는 위의 "순수 함수" 절에 있다 — 메트릭을 여기서 올리면
     * 이 클래스를 테스트하는 데 레지스트리 조립이 딸려 온다.
     */
    public record CurationOutcome(CuratedDay day, Map<DemotionReason, Integer> demotions) {

        public CurationOutcome {
            demotions = demotions == null ? Map.of() : Map.copyOf(demotions);
        }

        /** 강등이 한 건이라도 있었는가. 로그를 남길지 판단할 때 쓴다. */
        public boolean hasDemotions() {
            return !demotions.isEmpty();
        }
    }
}
