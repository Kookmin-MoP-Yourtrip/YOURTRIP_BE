package backend.yourtrip.global.ai.agent;

import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSlot;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Curator 프롬프트에 실을 <b>슬롯 구성과 후보 목록</b>을 만든다 (ROADMAP 6-4).
 *
 * <h2>목록의 순서 자체가 신호다</h2>
 * LLM은 목록 앞쪽을 더 자주 고르는 위치 편향이 있다. <b>이걸 억누르지 않고 이용한다</b> —
 * {@code CandidateOrdering}이 이미 "시드 → 스타일 일치 → 거리" 사전식으로 줄을 세워 뒀으므로,
 * 그 순서를 그대로 실으면 점수 계산 없이도 인기도가 선별에 반영된다. 여기서 다시 정렬하지 않는다.
 *
 * <h2>번호는 0부터다</h2>
 * {@code CandidateSlot.at(listIndex)}가 리스트 인덱스를 그대로 쓰기 때문이다. 5단계가
 * {@code listIndex}를 <b>별도 필드로 두지 않기로</b> 한 것이 그 결정이고(필드와 위치가 어긋나면
 * 6-7의 위조 검증이 아무 오류 없이 무력화된다), 여기서 1-based로 표기하면 그 결정이 깨진다.
 *
 * <h2>같은 슬롯 타입이 여러 자리면 목록은 한 번만 싣는다</h2>
 * day 구성에 {@code [ATTRACTION, MEAL, CAFE, ATTRACTION, MEAL]}처럼 같은 타입이 두 번 올 수 있는데,
 * 후보 풀은 <b>자리 단위가 아니라 타입 단위</b>다(같은 쿼리를 두 번 던지면 쿼터만 두 배다).
 * 목록을 자리마다 반복해 실으면 입력 토큰도 그대로 두 배가 되므로, 한 번 싣고 <b>어느 자리들이
 * 이 목록을 공유하는지</b>를 제목에 적는다.
 */
public final class CandidateListRenderer {

    /** 후보가 하나도 없는 슬롯. <b>빈 목록은 실패가 아니라 degrade다</b>(설계의 부분 실패 전략). */
    private static final String EMPTY_SLOT_NOTICE = "(후보 없음 — 확실히 아는 곳을 직접 제안한다)";

    private static final String FIELD_SEPARATOR = " · ";

    private CandidateListRenderer() {
    }

    /**
     * 채울 자리 목록. 예:
     * <pre>
     * 0. ATTRACTION
     * 1. MEAL
     * 2. CAFE
     * </pre>
     *
     * <p><b>자리를 타입이 아니라 번호로 지목하게 하는 이유</b>는 같은 타입이 여러 자리에 올 수 있어서다.
     * 타입으로 참조하면 두 자리 중 어느 쪽을 말하는지 표현할 방법이 없다.
     */
    public static String renderSlots(PlannerDayPlan day) {
        List<SlotType> slots = day == null ? List.of() : day.slots();
        StringJoiner joiner = new StringJoiner("\n");
        for (int index = 0; index < slots.size(); index++) {
            joiner.add("%d. %s".formatted(index, slots.get(index).name()));
        }
        return joiner.toString();
    }

    /**
     * 슬롯 타입별 후보 목록. 예:
     * <pre>
     * ### ATTRACTION (슬롯 0, 3)
     * 0. [seed 1위·관광] 대릉원 · 역사/한옥 · 0.4km
     * 1. [관광] 계림 · 자연 · 1.2km
     * </pre>
     */
    public static String renderCandidates(PlannerDayPlan day, CandidatePool pool) {
        if (day == null || day.slots().isEmpty()) {
            return "";
        }
        CandidatePool candidatePool = pool == null ? CandidatePool.empty() : pool;

        StringJoiner blocks = new StringJoiner("\n\n");
        slotPositions(day).forEach((slotType, positions) -> {
            CandidateSlot slot = candidatePool.findOrEmpty(day.day(), slotType);
            blocks.add(renderBlock(slotType, positions, slot.candidates()));
        });
        return blocks.toString();
    }

    /**
     * 슬롯 타입 → 그 타입이 놓인 자리 번호들. <b>{@code LinkedHashMap}이라 첫 등장 순서가 유지된다</b> —
     * 프롬프트가 요청마다 달라지면 같은 입력에 다른 코스가 나오는 원인이 하나 늘어난다.
     */
    private static Map<SlotType, List<Integer>> slotPositions(PlannerDayPlan day) {
        Map<SlotType, List<Integer>> positions = new LinkedHashMap<>();
        List<SlotType> slots = day.slots();
        for (int index = 0; index < slots.size(); index++) {
            positions.computeIfAbsent(slots.get(index), key -> new ArrayList<>()).add(index);
        }
        return positions;
    }

    private static String renderBlock(SlotType slotType, List<Integer> positions,
        List<PlaceCandidate> candidates) {
        StringJoiner block = new StringJoiner("\n");
        block.add("### %s (슬롯 %s)".formatted(slotType.name(), join(positions)));
        if (candidates.isEmpty()) {
            block.add(EMPTY_SLOT_NOTICE);
            return block.toString();
        }
        for (int index = 0; index < candidates.size(); index++) {
            block.add(renderCandidate(index, candidates.get(index)));
        }
        return block.toString();
    }

    private static String renderCandidate(int listIndex, PlaceCandidate candidate) {
        StringJoiner fields = new StringJoiner(FIELD_SEPARATOR);
        fields.add("%d. %s%s".formatted(listIndex, marker(candidate), candidate.name()));

        String tags = renderTags(candidate.styleTags());
        if (!tags.isEmpty()) {
            fields.add(tags);
        }
        if (candidate.distanceKm() != null) {
            fields.add("%.1fkm".formatted(candidate.distanceKm()));
        }
        return fields.toString();
    }

    /**
     * 출처 표식. <b>{@code seedRank}를 점수가 아니라 표식으로만 쓴다</b> — 쿼리 하나 안에서의 상대
     * 순위일 뿐이라 스타일 쿼리의 3위와 기본 쿼리의 3위는 같은 등급이 아니다. 숫자를 보여주되
     * 계산에 넣지 않는 것이 그 거친 신호를 다루는 안전한 방식이다.
     *
     * <h3>그 경고에는 축이 하나 더 있다 (이슈 #113)</h3>
     * 지명 캐스케이드(이슈 #110)가 생기면서 순위는 <b>지리적 범위</b>로도 갈렸다. {@code location}
     * 단계에서 온 후보의 1위는 <b>도시 전역의 1위</b>이고, 그 후보의 거리 중앙값은 6.34km로 권역
     * 질의(1.20km)의 다섯 배다. 그런데 모델은 질의가 무엇이었는지 모르므로 {@code [seed 1위]}를
     * <b>"이 권역 인기 1위"로 읽고</b>, 프롬프트의 선별 기준 2가 그 잘못된 근거로 발동한다.
     *
     * <p>그래서 그 후보만 <b>순위 숫자를 빼고 범위를 밝힌다</b>({@code [seed·광역]}).
     * <ul>
     *   <li><b>표식 자체를 없애지는 않는다</b> — {@code seed}가 사라지면 모델이 {@code source}를
     *       {@code SUGGESTED}로 적을 근거가 생겨 6-7의 {@code unknown_source} 강등을 부른다</li>
     *   <li><b>{@code anchor} 단계는 그대로 둔다</b> — 추가분 거리 중앙값이 1.07km로 권역 질의와
     *       다르지 않아 등급을 나눌 근거가 없다</li>
     *   <li><b>여기서 순서를 바꾸지는 않는다</b> — 목록 순서가 선택을 얼마나 좌우하는지는 아직
     *       측정된 바 없고, {@code CandidateOrdering} 수정은 그 실측 뒤의 일이다</li>
     * </ul>
     */
    private static String marker(PlaceCandidate candidate) {
        StringJoiner marks = new StringJoiner("·");
        if (candidate.seeded()) {
            // 순위는 그 질의 안에서만 뜻이 있다. 도시 전역 질의의 순위를 그대로 실으면
            // "이 권역 인기 n위"라는 없는 사실을 주장하게 된다.
            marks.add(candidate.fromCityWideQuery()
                ? "seed·광역"
                : "seed %d위".formatted(candidate.seedRank()));
        }
        if (candidate.official()) {
            marks.add("관광");
        }
        return marks.length() == 0 ? "" : "[%s] ".formatted(marks);
    }

    /**
     * 스타일 태그를 {@code 역사/한옥} 형태로.
     *
     * <p><b>enum 순서로 정렬하는 것이 필수다.</b> {@code PlaceCandidate.styleTags}는
     * {@code Set.copyOf(...)}로 만든 불변 집합인데, 그 구현은 <b>JVM 실행마다 반복 순서를 바꾼다</b>
     * (해시 salt). 정렬하지 않으면 같은 입력에 프롬프트가 매번 달라져, 재현 불가능한 차이가
     * 코스 결과에 섞인다.
     */
    private static String renderTags(Set<StyleTag> tags) {
        return tags.stream()
            .sorted(Comparator.comparing(Enum::ordinal))
            .map(StyleTag::getLabel)
            .reduce((left, right) -> left + "/" + right)
            .orElse("");
    }

    private static String join(List<Integer> positions) {
        return positions.stream()
            .map(String::valueOf)
            .reduce((left, right) -> left + ", " + right)
            .orElse("");
    }
}
