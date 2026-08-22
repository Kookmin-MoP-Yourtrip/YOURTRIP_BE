package backend.yourtrip.global.ai.agent;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.ai.agent.dto.PlannerResponse;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PlannerPlanNormalizer (ROADMAP 6-3)")
class PlannerPlanNormalizerTest {

    private static final String LOCATION = "경주";

    @Nested
    @DisplayName("멀쩡한 응답")
    class WellFormed {

        @Test
        @DisplayName("손대지 않는다 — 보정기가 정상 계획을 망가뜨리지 않는 것이 첫 계약이다")
        void leavesValidPlanUntouched() {
            PlannerPlan plan = PlannerPlanNormalizer.normalize(new PlannerResponse(
                "천년의 밤", "느긋한 경주",
                List.of(day(1, "황리단길 일대", "대릉원", "한옥 골목", "10:00",
                        List.of("ATTRACTION", "MEAL", "CAFE")),
                    day(2, "보문단지", "보문호", "호수 산책", "09:00",
                        List.of("STROLL", "MEAL", "CAFE")))), LOCATION, 2);

            assertThat(plan.title()).isEqualTo("천년의 밤");
            assertThat(plan.concept()).isEqualTo("느긋한 경주");
            assertThat(plan.days()).hasSize(2);
            assertThat(plan.days().getFirst())
                .extracting(PlannerDayPlan::day, PlannerDayPlan::area, PlannerDayPlan::anchor,
                    PlannerDayPlan::theme, PlannerDayPlan::dayStartTime)
                .containsExactly(1, "황리단길 일대", "대릉원", "한옥 골목", LocalTime.of(10, 0));
            assertThat(plan.days().getFirst().slots())
                .containsExactly(SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE);
        }
    }

    @Nested
    @DisplayName("day 목록")
    class Days {

        @Test
        @DisplayName("모자라면 기본 플랜으로 채운다")
        void fillsMissingDays() {
            PlannerPlan plan = PlannerPlanNormalizer.normalize(
                response(day(1, "황리단길", "대릉원", "산책", "10:00", List.of("MEAL"))), LOCATION, 3);

            assertThat(plan.days()).hasSize(3);
            assertThat(plan.days().get(2).area()).isEqualTo(LOCATION);
            assertThat(plan.days().get(2).anchor()).isEqualTo(LOCATION);
        }

        @Test
        @DisplayName("많으면 뒤를 자른다")
        void truncatesExtraDays() {
            PlannerPlan plan = PlannerPlanNormalizer.normalize(response(
                day(1, "A", "a", "t", "10:00", List.of("MEAL")),
                day(2, "B", "b", "t", "10:00", List.of("MEAL")),
                day(3, "C", "c", "t", "10:00", List.of("MEAL"))), LOCATION, 2);

            assertThat(plan.days()).hasSize(2);
            assertThat(plan.days()).extracting(PlannerDayPlan::area).containsExactly("A", "B");
        }

        @Test
        @DisplayName("day 번호는 응답이 아니라 위치로 매긴다 — 중복·0-based·비연속을 한 규칙으로 흡수한다")
        void renumbersByPosition() {
            PlannerPlan plan = PlannerPlanNormalizer.normalize(response(
                day(0, "A", "a", "t", "10:00", List.of("MEAL")),
                day(0, "B", "b", "t", "10:00", List.of("MEAL")),
                day(7, "C", "c", "t", "10:00", List.of("MEAL"))), LOCATION, 3);

            assertThat(plan.days()).extracting(PlannerDayPlan::day).containsExactly(1, 2, 3);
        }

        @Test
        @DisplayName("응답이 통째로 비면 기본 플랜")
        void fallsBackWhenResponseIsNull() {
            PlannerPlan plan = PlannerPlanNormalizer.normalize(null, LOCATION, 2);

            assertThat(plan.days()).hasSize(2);
            assertThat(plan.title()).contains(LOCATION);
        }
    }

    @Nested
    @DisplayName("슬롯")
    class Slots {

        @Test
        @DisplayName("알 수 없는 값은 그 항목만 뺀다 — 응답 전체를 버리지 않는다")
        void dropsUnknownSlotType() {
            PlannerPlan plan = normalizeSlots("ATTRACTION", "맛집", "MEAL", "CAFE");

            assertThat(plan.days().getFirst().slots())
                .containsExactly(SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE);
        }

        @Test
        @DisplayName("대소문자와 공백은 관용한다")
        void acceptsLooseCasing() {
            PlannerPlan plan = normalizeSlots(" attraction ", "Meal", "cafe");

            assertThat(plan.days().getFirst().slots())
                .containsExactly(SlotType.ATTRACTION, SlotType.MEAL, SlotType.CAFE);
        }

        @Test
        @DisplayName("3개 미만이면 기본 구성으로 채운다")
        void fillsBelowMinimum() {
            PlannerPlan plan = normalizeSlots("VIEWPOINT");

            assertThat(plan.days().getFirst().slots())
                .hasSize(PlannerPlanNormalizer.MIN_SLOTS)
                .startsWith(SlotType.VIEWPOINT);
        }

        @Test
        @DisplayName("6개를 넘으면 자른다 — 상한의 근거는 RouteOptimizer 완전탐색 벤치마크다")
        void trimsAboveMaximum() {
            PlannerPlan plan = normalizeSlots("ATTRACTION", "MEAL", "CAFE", "STROLL", "VIEWPOINT",
                "SHOPPING", "EXPERIENCE", "ATTRACTION");

            assertThat(plan.days().getFirst().slots()).hasSize(PlannerPlanNormalizer.MAX_SLOTS);
        }

        @Test
        @DisplayName("MEAL 이 없으면 코드가 채운다 — 프롬프트 규칙이 아니라 불변식이다")
        void guaranteesMeal() {
            PlannerPlan plan = normalizeSlots("ATTRACTION", "CAFE", "STROLL");

            assertThat(plan.days().getFirst().slots()).contains(SlotType.MEAL).hasSize(4);
        }

        @Test
        @DisplayName("자리가 꽉 찬 채로 MEAL 이 없으면 마지막 자리를 바꾼다")
        void replacesLastSlotWhenFull() {
            PlannerPlan plan = normalizeSlots("ATTRACTION", "CAFE", "STROLL", "VIEWPOINT",
                "SHOPPING", "EXPERIENCE");

            assertThat(plan.days().getFirst().slots())
                .hasSize(PlannerPlanNormalizer.MAX_SLOTS)
                .endsWith(SlotType.MEAL);
        }
    }

    @Nested
    @DisplayName("빈 값 대체")
    class Blanks {

        @Test
        @DisplayName("area 는 location 으로, anchor 는 area 로, theme 는 concept 로")
        void substitutesBlanks() {
            PlannerPlan plan = PlannerPlanNormalizer.normalize(new PlannerResponse(
                "제목", "느긋한 경주",
                List.of(day(1, " ", null, "", "10:00", List.of("MEAL", "CAFE", "STROLL")))),
                LOCATION, 1);

            PlannerDayPlan first = plan.days().getFirst();
            assertThat(first.area()).isEqualTo(LOCATION);
            assertThat(first.anchor()).isEqualTo(LOCATION);
            assertThat(first.theme()).isEqualTo("느긋한 경주");
        }

        @Test
        @DisplayName("title 과 concept 가 비면 결정론적 기본값")
        void substitutesTitleAndConcept() {
            PlannerPlan plan = PlannerPlanNormalizer.normalize(new PlannerResponse(null, " ",
                List.of(day(1, "A", "a", "t", "10:00", List.of("MEAL", "CAFE", "STROLL")))),
                LOCATION, 1);

            assertThat(plan.title()).isEqualTo("경주 1일 여행");
            assertThat(plan.concept()).isEqualTo("경주의 대표적인 장소를 둘러보는 코스");
        }
    }

    @Nested
    @DisplayName("시작 시각")
    class StartTime {

        @Test
        @DisplayName("읽지 못하면 null — RouteRequest 기본값에 맡긴다")
        void nullsUnparsable() {
            assertThat(startTimeOf("아침 일찍")).isNull();
            assertThat(startTimeOf(null)).isNull();
        }

        @Test
        @DisplayName("한 자리 시각도 받는다 — 뜻이 명백한데 형식만 어긋난 경우다")
        void acceptsSingleDigitHour() {
            assertThat(startTimeOf("9:30")).isEqualTo(LocalTime.of(9, 30));
        }

        @Test
        @DisplayName("범위 밖은 버리지 않고 클램프한다")
        void clampsOutOfRange() {
            assertThat(startTimeOf("05:00")).isEqualTo(PlannerPlanNormalizer.EARLIEST_START);
            assertThat(startTimeOf("18:00")).isEqualTo(PlannerPlanNormalizer.LATEST_START);
        }
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private static PlannerResponse.Day day(int number, String area, String anchor, String theme,
        String startTime, List<String> slots) {
        return new PlannerResponse.Day(number, area, anchor, theme, startTime, slots);
    }

    private static PlannerResponse response(PlannerResponse.Day... days) {
        return new PlannerResponse("제목", "컨셉", List.of(days));
    }

    private static PlannerPlan normalizeSlots(String... slots) {
        return PlannerPlanNormalizer.normalize(
            response(day(1, "황리단길", "대릉원", "산책", "10:00", List.of(slots))), LOCATION, 1);
    }

    private static LocalTime startTimeOf(String raw) {
        return PlannerPlanNormalizer.normalize(
            response(day(1, "A", "a", "t", raw, List.of("MEAL", "CAFE", "STROLL"))), LOCATION, 1)
            .days().getFirst().dayStartTime();
    }
}
