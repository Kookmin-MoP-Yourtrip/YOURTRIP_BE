package backend.yourtrip.domain.mycourse.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.mycourse.dto.ai.ResolvedDay;
import backend.yourtrip.domain.mycourse.dto.ai.ResolvedPlace;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.StyleTag;
import backend.yourtrip.global.ai.grounding.GroundedPlace;
import backend.yourtrip.global.ai.pipeline.AiCourseDay;
import backend.yourtrip.global.ai.pipeline.AiCourseDraft;
import backend.yourtrip.global.ai.pipeline.AiCoursePlace;
import backend.yourtrip.global.ai.route.SlotType;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 파이프라인 산출물 → 저장용 중간 표현 변환의 계약을 지킨다.
 *
 * <p>가장 중요한 계약은 <b>day 안의 장소 순서 보존</b>이다(ROADMAP 8-3) —
 * {@code DaySchedule.places}에 {@code @OrderBy("id ASC")}만 있고 sequence 컬럼이 없어,
 * 변환에서 순서가 흐트러지면 최적화된 동선이 DB에서 재현되지 않는다.
 */
class AiCourseDraftMapperTest {

    @Test
    @DisplayName("day 안의 장소 순서가 파이프라인이 정한 방문 순서 그대로 보존된다")
    void placesKeepVisitOrderWithinDay() {
        // 이름 정렬·해시 순서와 겹치지 않는 배치로, 우연히 통과할 여지를 없앤다
        AiCourseDraft draft = draft(List.of(
            day(1, place("황리단길 카페", SlotType.CAFE), place("대릉원", SlotType.ATTRACTION),
                place("교리김밥", SlotType.MEAL)),
            day(2, place("불국사", SlotType.ATTRACTION), place("석굴암", SlotType.ATTRACTION))));

        List<ResolvedDay> resolved = AiCourseDraftMapper.toResolvedDays(draft);

        assertThat(resolved).hasSize(2);
        assertThat(resolved.get(0).day()).isEqualTo(1);
        assertThat(resolved.get(0).places())
            .extracting(ResolvedPlace::placeName)
            .containsExactly("황리단길 카페", "대릉원", "교리김밥");
        assertThat(resolved.get(1).places())
            .extracting(ResolvedPlace::placeName)
            .containsExactly("불국사", "석굴암");
    }

    @Test
    @DisplayName("이름·시각·좌표·URL·주소가 필드 그대로 옮겨진다")
    void fieldsAreMappedOneToOne() {
        GroundedPlace grounded = new GroundedPlace("대릉원", SlotType.ATTRACTION,
            35.8383, 129.2113, "경북 경주시 계림로 9", "http://place.map.kakao.com/1",
            CandidateSourceType.SEEDED, StyleTag.HANOK);

        AiCourseDraft draft = draft(List.of(new AiCourseDay(1,
            LocalTime.of(10, 0), LocalTime.of(23, 59),
            List.of(new AiCoursePlace(grounded, LocalTime.of(10, 30), 90)))));

        ResolvedPlace place = AiCourseDraftMapper.toResolvedDays(draft).get(0).places().get(0);

        assertThat(place.placeName()).isEqualTo("대릉원");
        assertThat(place.startTime()).isEqualTo(LocalTime.of(10, 30));
        assertThat(place.latitude()).isEqualTo(35.8383);
        assertThat(place.longitude()).isEqualTo(129.2113);
        assertThat(place.placeUrl()).isEqualTo("http://place.map.kakao.com/1");
        assertThat(place.placeLocation()).isEqualTo("경북 경주시 계림로 9");
    }

    @Test
    @DisplayName("placeUrl이 null인 장소(SEEDED/LISTED 보강 실패)는 null 그대로 통과한다")
    void nullPlaceUrlPassesThrough() {
        AiCourseDraft draft = draft(List.of(day(1, place("대릉원", SlotType.ATTRACTION))));

        ResolvedPlace place = AiCourseDraftMapper.toResolvedDays(draft).get(0).places().get(0);

        assertThat(place.placeUrl()).isNull();
    }

    @Test
    @DisplayName("빈 주소(\"\")는 저장 관례에 맞춰 null로 옮긴다")
    void blankAddressBecomesNull() {
        // GroundedPlace는 null 주소를 ""로 정규화하지만 저장 계층의 관례는 null이다
        GroundedPlace grounded = new GroundedPlace("대릉원", SlotType.ATTRACTION,
            35.8383, 129.2113, null, null, CandidateSourceType.SEEDED, null);

        AiCourseDraft draft = draft(List.of(new AiCourseDay(1,
            LocalTime.of(10, 0), LocalTime.of(23, 59),
            List.of(new AiCoursePlace(grounded, LocalTime.of(10, 0), 90)))));

        ResolvedPlace place = AiCourseDraftMapper.toResolvedDays(draft).get(0).places().get(0);

        assertThat(place.placeLocation()).isNull();
    }

    @Test
    @DisplayName("장소가 없는 day도 빈 목록으로 옮겨진다 — 부분 실패는 통과가 원칙이다")
    void emptyDayIsPreserved() {
        AiCourseDraft draft = draft(List.of(
            day(1, place("대릉원", SlotType.ATTRACTION)),
            new AiCourseDay(2, LocalTime.of(10, 0), LocalTime.of(23, 59), List.of())));

        List<ResolvedDay> resolved = AiCourseDraftMapper.toResolvedDays(draft);

        assertThat(resolved.get(1).places()).isEmpty();
    }

    private AiCourseDraft draft(List<AiCourseDay> days) {
        return new AiCourseDraft("경주 코스", "컨셉", days);
    }

    private AiCourseDay day(int day, AiCoursePlace... places) {
        return new AiCourseDay(day, LocalTime.of(10, 0), LocalTime.of(23, 59), List.of(places));
    }

    private AiCoursePlace place(String name, SlotType slotType) {
        return new AiCoursePlace(
            new GroundedPlace(name, slotType, 35.83, 129.22, "경북 경주시", null,
                CandidateSourceType.SEEDED, null),
            LocalTime.of(10, 0), 90);
    }
}
