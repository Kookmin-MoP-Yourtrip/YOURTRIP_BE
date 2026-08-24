package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.ai.ResolvedDay;
import backend.yourtrip.domain.mycourse.dto.ai.ResolvedPlace;
import backend.yourtrip.global.ai.grounding.GroundedPlace;
import backend.yourtrip.global.ai.pipeline.AiCourseDay;
import backend.yourtrip.global.ai.pipeline.AiCourseDraft;
import backend.yourtrip.global.ai.pipeline.AiCoursePlace;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * 파이프라인 산출물({@link AiCourseDraft})을 저장용 중간 표현({@link ResolvedDay})으로 옮긴다.
 *
 * <p>변환을 domain 쪽이 소유하는 이유: 파이프라인은 {@code domain}을 모른다는 경계(ROADMAP 7-1)를
 * 유지하기 위해서다. {@code global.ai}가 {@code ResolvedDay}를 알게 되면 경계가 반대로 뚫린다.
 *
 * <p><b>day 안의 리스트 순서를 그대로 보존한다</b> — {@code AiCourseDay.places}의 순서가 곧
 * 방문 순서이고, {@code AiCoursePersister}가 그 순서대로 save해야 {@code @OrderBy("id ASC")}로
 * 동선이 재현된다(ROADMAP 8-3).
 *
 * <p>{@code draft.concept()}은 옮기지 않는다 — {@code TravelCourse}에 받을 필드가 없고,
 * 스위치 커밋에서 엔티티를 늘리지 않는다(STEP-8 착수 시점 결정).
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AiCourseDraftMapper {

    public static List<ResolvedDay> toResolvedDays(AiCourseDraft draft) {
        List<ResolvedDay> resolvedDays = new ArrayList<>();
        for (AiCourseDay day : draft.days()) {
            List<ResolvedPlace> places = new ArrayList<>();
            for (AiCoursePlace coursePlace : day.places()) {
                places.add(toResolvedPlace(coursePlace));
            }
            resolvedDays.add(new ResolvedDay(day.day(), places));
        }
        return resolvedDays;
    }

    private static ResolvedPlace toResolvedPlace(AiCoursePlace coursePlace) {
        GroundedPlace grounded = coursePlace.place();
        return new ResolvedPlace(
            grounded.name(),
            coursePlace.startTime(),
            grounded.latitude(),
            grounded.longitude(),
            grounded.placeUrl(),
            // GroundedPlace는 없는 주소를 ""로 정규화하지만, 저장 계층의 관례는 null이다
            // (기존 경로가 매칭 실패 시 null을 저장했고 응답 DTO도 nullable로 계약돼 있다)
            grounded.address().isBlank() ? null : grounded.address());
    }
}
