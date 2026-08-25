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
 * <h2>day당 식사 2회가 여기서 불변식이 된다</h2>
 * 기존 프롬프트의 규칙 14("각 day 마다 최소 1개 이상의 식사를 포함")는 <b>지켜졌는지 아무도 확인하지
 * 않는 지시문</b>이었다. 프롬프트에도 남기지만, 실제로 보장하는 것은 이 클래스다.
 *
 * <p>횟수가 하나에서 둘로 올라간 것은 <b>저녁 시간창을 발동시키기 위해서다</b>(이슈 #135).
 * {@code RouteOptimizer}는 식사가 둘 이상일 때만 이른 쪽을 점심·늦은 쪽을 저녁 창에 배정하는데,
 * 하나뿐이면 가까운 창(사실상 점심)만 보므로 하루가 오후에 끝난다.
 *
 * <h2>보정한 것은 로그로 남긴다</h2>
 * 무엇을 얼마나 고쳤는지가 곧 <b>모델이 스키마를 얼마나 지키는가</b>이고, 프롬프트를 손볼지
 * 판단하는 유일한 근거다. 조용히 고치면 프롬프트가 나빠져도 알 수 없다.
 */
@Slf4j
public final class PlannerPlanNormalizer {

    /**
     * day 당 슬롯 하한. <b>{@code RouteOptimizer}의 드롭 중단선(3)과 다른 값이다</b> — 저쪽은
     * "이보다 줄이면 코스가 아니다"라는 안전장치이고, 이쪽은 "이만큼은 있어야 하루가 저녁까지
     * 이어진다"는 목표치다.
     *
     * <p>다섯인 근거는 탄력 체류다(이슈 #135). 식사 둘을 빼면 볼거리가 셋 남는데, 그 셋의
     * 체류를 상한까지 늘려야 저녁 식사가 17:30 창에 닿는다. 넷이면 늘려도 닿지 못해
     * 이른 저녁이 벌점으로 남는다.
     */
    static final int MIN_SLOTS = 5;

    /**
     * day 당 슬롯 상한. <b>취향이 아니라 3-6 벤치마크가 정한 값이다</b> — {@code RouteOptimizer}의
     * 완전탐색이 {@code n=7}에서 3일 4.0ms 인데 {@code n=8}이면 29ms 로 지연 예산({@code <10ms})을 넘는다
     * (탄력 체류 도입 후 재측정, 이슈 #135).
     * 그래서 {@code RouteOptimizer.MAX_BRUTE_FORCE_PLACES}와 <b>같은 값이어야 한다</b> —
     * 이보다 크면 완전탐색이 꺼진 채로 입력 순서가 그대로 나가는 day 가 생긴다.
     *
     * <p>하루 7곳은 도메인 관행과도 맞는다. 식사 둘을 포함해 5~7곳이 통상의 하루이고,
     * 여덟 곳부터는 체류를 깎아 서두르는 강행군이 된다.
     */
    static final int MAX_SLOTS = 7;

    /**
     * day 당 보장하는 식사 횟수. <b>점심 하나로는 하루가 오후에 끝난다</b> — 저녁 시간창
     * (17:30~19:30)은 식사 슬롯이 둘 이상일 때만 배정 대상이 되므로, 이 값이 1이면
     * {@code RouteOptimizer}의 탄력 체류가 저녁을 향해 밀어 줄 대상 자체가 없다(이슈 #135).
     *
     * <p>셋(아침 포함)은 강제하지 않는다. Planner 가 내면 그대로 두되, 시간창이 둘뿐이라
     * 세 번째 끼니는 가까운 창까지의 거리로만 벌점을 문다.
     */
    static final int REQUIRED_MEALS = 2;

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
        return requireMeals(slots, day);
    }

    /**
     * {@link #REQUIRED_MEALS}회의 식사를 보장한다.
     *
     * <p>자리가 남으면 더하고, 꽉 찼으면 <b>뒤에서부터 식사가 아닌 자리를 바꾼다.</b> 자리 순서는
     * 뒤에서 {@code RouteOptimizer}가 동선 기준으로 다시 배열하므로 <b>어느 위치에 넣는지는
     * 의미가 없다</b> — 여기서 정하는 것은 "몇 개를, 무슨 종류로"뿐이다.
     *
     * <p><b>"마지막 자리"가 아니라 "뒤에서부터 식사가 아닌 자리"인 것이 중요하다.</b> 두 번
     * 채워야 할 때 같은 자리를 두 번 덮으면 영원히 하나에 머문다. 슬롯 수가 {@link #MIN_SLOTS}
     * 이상이고 {@code REQUIRED_MEALS}가 그보다 작으므로 바꿀 자리는 항상 남아 있다.
     */
    private static List<SlotType> requireMeals(List<SlotType> slots, int day) {
        int mealCount = (int) slots.stream().filter(SlotType.MEAL::equals).count();
        if (mealCount >= REQUIRED_MEALS) {
            return List.copyOf(slots);
        }
        log.warn("day {}: MEAL 슬롯이 {}개뿐이라 코드가 {}개로 채운다", day, mealCount, REQUIRED_MEALS);

        while (mealCount < REQUIRED_MEALS) {
            if (slots.size() < MAX_SLOTS) {
                slots.add(SlotType.MEAL);
            } else {
                slots.set(lastNonMealIndex(slots), SlotType.MEAL);
            }
            mealCount++;
        }
        return List.copyOf(slots);
    }

    /** 뒤에서부터 처음 만나는 식사 아닌 자리. 전부 식사면 마지막 자리를 준다(도달하지 않는다). */
    private static int lastNonMealIndex(List<SlotType> slots) {
        for (int i = slots.size() - 1; i >= 0; i--) {
            if (slots.get(i) != SlotType.MEAL) {
                return i;
            }
        }
        return slots.size() - 1;
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
