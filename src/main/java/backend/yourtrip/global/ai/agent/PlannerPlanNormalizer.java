package backend.yourtrip.global.ai.agent;

import backend.yourtrip.global.ai.agent.dto.PlannerResponse;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;

/**
 * Planner 응답의 구조를 <b>코드로 보정</b>한다 (ROADMAP 6-3).
 *
 * <h2>LLM 을 다시 부르지 않는다</h2>
 * day 수가 하나 모자라거나 슬롯에 오타가 하나 섞인 것은 <b>재호출로 고칠 일이 아니다.</b> 재호출은
 * 3~10초와 토큰을 쓰고도 같은 실수를 반복할 수 있는 반면, 여기서의 보정은 결정론적이고 즉시 끝난다.
 * 어댑터의 의미 재시도는 "JSON 자체가 깨진" 경우를 위한 것이지 "값이 어긋난" 경우를 위한 것이 아니다.
 *
 * <h2>day당 식사 1회가 여기서 불변식이 된다</h2>
 * 기존 프롬프트의 규칙 14("각 day 마다 최소 1개 이상의 식사를 포함")는 <b>지켜졌는지 아무도 확인하지
 * 않는 지시문</b>이었다. 프롬프트에도 남기지만, 실제로 보장하는 것은 이 클래스다.
 *
 * <h2>보정한 것은 로그로 남긴다</h2>
 * 무엇을 얼마나 고쳤는지가 곧 <b>모델이 스키마를 얼마나 지키는가</b>이고, 프롬프트를 손볼지
 * 판단하는 유일한 근거다. 조용히 고치면 프롬프트가 나빠져도 알 수 없다.
 */
@Slf4j
public final class PlannerPlanNormalizer {

    /** day 당 슬롯 하한. 셋보다 적으면 코스라고 부르기 어렵다(3-5 의 드롭 중단선과 같은 값이다). */
    static final int MIN_SLOTS = 3;

    /**
     * day 당 슬롯 상한. <b>취향이 아니라 3-6 벤치마크가 정한 값이다</b> — {@code RouteOptimizer}의
     * 완전탐색이 {@code n=7}에서 3일 1.77ms 인데 {@code n=8}이면 15ms 로 지연 예산을 넘는다.
     */
    static final int MAX_SLOTS = 6;

    /** 시작 시각의 허용 구간. 밖으로 나가면 자르되 버리지는 않는다. */
    static final LocalTime EARLIEST_START = LocalTime.of(7, 0);
    static final LocalTime LATEST_START = LocalTime.of(12, 0);

    /**
     * {@code 9:00} 처럼 한 자리 시각도 받는다. 스키마가 {@code HH:mm} 을 요구하지만 한 자리 표기는
     * <b>뜻이 명백한데 형식만 어긋난</b> 경우라, 이것까지 기본값으로 떨어뜨릴 이유가 없다.
     */
    private static final DateTimeFormatter LENIENT_TIME = DateTimeFormatter.ofPattern("H:mm");

    private PlannerPlanNormalizer() {
    }

    /**
     * @param days 요청의 여행 일수. <b>정본은 이 값이다</b> — 응답의 day 수가 아니다
     */
    public static PlannerPlan normalize(PlannerResponse response, String location, int days) {
        if (response == null) {
            log.warn("Planner 응답이 비어 있다 — 기본 플랜으로 대체한다");
            return DefaultPlannerPlans.of(location, days);
        }

        String title = blankTo(response.title(), DefaultPlannerPlans.defaultTitle(location, days));
        String concept = blankTo(response.concept(), DefaultPlannerPlans.defaultConcept(location));
        return new PlannerPlan(title, concept, normalizeDays(response.days(), location, concept, days));
    }

    // ── day 목록 — 개수와 번호는 요청이 정한다 ──────────────────────────────────

    private static List<PlannerDayPlan> normalizeDays(List<PlannerResponse.Day> raw, String location,
        String concept, int days) {
        List<PlannerResponse.Day> given = raw == null ? List.of() : raw;
        if (given.size() != days) {
            log.warn("Planner 가 낸 day 수({})가 요청({})과 다르다 — 코드로 맞춘다", given.size(), days);
        }

        List<PlannerDayPlan> normalized = new ArrayList<>(days);
        for (int index = 0; index < days; index++) {
            // day 번호는 응답의 값이 아니라 **위치**로 매긴다. 중복(1,1,2)·0-based·비연속을 한
            // 규칙으로 전부 흡수한다 — 번호가 어긋나면 후보 공급의 (day, 슬롯) 조회가 통째로 빗나간다.
            int day = index + 1;
            normalized.add(index < given.size()
                ? normalizeDay(given.get(index), day, location, concept)
                : DefaultPlannerPlans.dayOf(location, day));
        }
        return normalized;
    }

    private static PlannerDayPlan normalizeDay(PlannerResponse.Day raw, int day, String location,
        String concept) {
        if (raw == null) {
            return DefaultPlannerPlans.dayOf(location, day);
        }
        // area 가 비면 도시 전체를 권역으로 본다. anchor 가 비면 area 텍스트로 대체하고,
        // 그 뒤는 지오코딩 캐스케이드(4-8)가 anchor → area → location 순으로 이어받는다.
        String area = blankTo(raw.area(), location);
        String anchor = blankTo(raw.anchor(), area);
        String theme = blankTo(raw.theme(), concept);
        return new PlannerDayPlan(day, area, anchor, theme, parseStartTime(raw.dayStartTime(), day),
            normalizeSlots(raw.slots(), day));
    }

    // ── 슬롯 — 알 수 없는 값 제거 → 개수 clamp → MEAL 보장 ────────────────────

    private static List<SlotType> normalizeSlots(List<String> raw, int day) {
        List<SlotType> slots = new ArrayList<>(MAX_SLOTS);
        for (String name : raw == null ? List.<String>of() : raw) {
            SlotType slotType = parseSlotType(name);
            if (slotType == null) {
                log.warn("day {}: 알 수 없는 슬롯 타입 '{}' 을 제외한다", day, name);
                continue;
            }
            slots.add(slotType);
        }

        if (slots.size() > MAX_SLOTS) {
            log.warn("day {}: 슬롯이 {}개라 {}개로 자른다", day, slots.size(), MAX_SLOTS);
            slots = new ArrayList<>(slots.subList(0, MAX_SLOTS));
        }
        for (int i = 0; slots.size() < MIN_SLOTS; i++) {
            slots.add(DefaultPlannerPlans.DEFAULT_SLOTS.get(i % DefaultPlannerPlans.DEFAULT_SLOTS.size()));
        }
        return requireMeal(slots, day);
    }

    /**
     * "day 당 식사 1회"를 보장한다.
     *
     * <p>자리가 남으면 더하고, 꽉 찼으면 <b>마지막 자리를 바꾼다.</b> 자리 순서는 뒤에서
     * {@code RouteOptimizer}가 동선 기준으로 다시 배열하므로 <b>어느 위치에 넣는지는 의미가 없다</b> —
     * 여기서 정하는 것은 "몇 개를, 무슨 종류로"뿐이다.
     */
    private static List<SlotType> requireMeal(List<SlotType> slots, int day) {
        if (slots.contains(SlotType.MEAL)) {
            return List.copyOf(slots);
        }
        log.warn("day {}: MEAL 슬롯이 없어 코드가 채운다", day);
        if (slots.size() < MAX_SLOTS) {
            slots.add(SlotType.MEAL);
        } else {
            slots.set(slots.size() - 1, SlotType.MEAL);
        }
        return List.copyOf(slots);
    }

    /** 대소문자·앞뒤 공백만 관용한다. 그 이상 추측하면 "비슷한 이름"을 잘못 매핑한다. */
    private static SlotType parseSlotType(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return SlotType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ── 시작 시각 — 파싱 실패는 기본값, 범위 밖은 클램프 ──────────────────────

    /**
     * {@code HH:mm} 파싱.
     *
     * <p><b>실패와 범위 밖을 다르게 다룬다.</b> 못 읽은 값은 {@code null}로 떨어뜨려
     * {@code RouteRequest}의 기본값(09:30)에 맡기고, 읽었는데 상식 밖인 값은 <b>클램프</b>한다 —
     * 후자는 "새벽 5시 출발"처럼 의도가 읽히는 값이라 통째로 버리는 것보다 당기는 편이 낫다.
     */
    private static LocalTime parseStartTime(String raw, int day) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LocalTime parsed;
        try {
            parsed = LocalTime.parse(raw.trim());
        } catch (DateTimeParseException e) {
            parsed = parseLeniently(raw.trim());
        }
        if (parsed == null) {
            log.warn("day {}: dayStartTime '{}' 을 읽지 못해 기본값을 쓴다", day, raw);
            return null;
        }
        if (parsed.isBefore(EARLIEST_START)) {
            log.warn("day {}: dayStartTime {} 이 너무 일러 {} 로 당긴다", day, parsed, EARLIEST_START);
            return EARLIEST_START;
        }
        if (parsed.isAfter(LATEST_START)) {
            log.warn("day {}: dayStartTime {} 이 너무 늦어 {} 로 민다", day, parsed, LATEST_START);
            return LATEST_START;
        }
        return parsed;
    }

    private static LocalTime parseLeniently(String raw) {
        try {
            return LocalTime.parse(raw, LENIENT_TIME);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
