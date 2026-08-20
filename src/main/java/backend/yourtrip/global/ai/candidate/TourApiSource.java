package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.tour.TourApiClient;
import backend.yourtrip.global.tour.TourApiResult;
import backend.yourtrip.global.tour.TourPlace;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 한국관광공사 TourAPI를 후보로 바꾸는 어댑터 — <b>관광 슬롯의 커버리지·분류 축</b> (ROADMAP 5-8).
 *
 * <p><b>조회 단위가 슬롯이 아니라 {@code contentTypeId}다.</b> 시더는 슬롯의 {@code searchHint}로
 * 묻지만 TourAPI는 좌표 + 분류로 묻는다. 슬롯마다 부르면 코스당 호출이 설계 예산(≤9회)의 네 배가
 * 되어 개발계정 일 1,000건을 하루 27코스에서 소진한다. 그래서 <b>day의 관광 슬롯들이 요구하는
 * {@code contentTypeId}의 합집합을 한 번씩만</b> 부르고, 받은 목록을 슬롯에 나눠 싣는다
 * ({@link PlaceCandidate#withSlotType}).
 *
 * <p><b>ATTRACTION·VIEWPOINT·STROLL을 한 묶음으로 다룬다.</b> 셋 다 {@code contentTypeId=12}에
 * 담기고 실제로 가를 방법이 없다 — 4-4에서 네이버 분류 판정을 같은 묶음으로 정한 것과 같은 이유다.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TourApiSource {

    /** {@code contentTypeId=12} 관광지 · {@code 14} 문화시설. 둘 다 "볼거리"라 같은 슬롯군에 실린다. */
    private static final Set<Integer> SIGHT_CONTENT_TYPES = Set.of(
        TourApiClient.CONTENT_TYPE_ATTRACTION, TourApiClient.CONTENT_TYPE_CULTURE);

    /** {@code contentTypeId=28} 레포츠. 체험 슬롯의 몫이다. */
    private static final Set<Integer> EXPERIENCE_CONTENT_TYPES = Set.of(
        TourApiClient.CONTENT_TYPE_LEISURE);

    private final TourApiClient tourApiClient;

    /**
     * 그 슬롯이 필요로 하는 {@code contentTypeId}들. <b>빈 집합이면 TourAPI를 쓰지 않는 슬롯</b>이다 —
     * MEAL·CAFE·SHOPPING은 상업 POI라 커버리지가 얇아 시더만 쓴다(설계).
     */
    public static Set<Integer> contentTypeIdsFor(SlotType slotType) {
        return switch (slotType) {
            case ATTRACTION, VIEWPOINT, STROLL -> SIGHT_CONTENT_TYPES;
            case EXPERIENCE -> EXPERIENCE_CONTENT_TYPES;
            case MEAL, CAFE, SHOPPING -> Set.of();
        };
    }

    /** 여러 슬롯이 요구하는 {@code contentTypeId}의 합집합 — day당 실제 호출 목록이다. */
    public static Set<Integer> contentTypeIdsFor(Iterable<SlotType> slotTypes) {
        Set<Integer> union = new LinkedHashSet<>();
        for (SlotType slotType : slotTypes) {
            union.addAll(contentTypeIdsFor(slotType));
        }
        return union;
    }

    /**
     * anchor 좌표 반경의 관광지 목록을 거리순으로.
     *
     * <p>후보의 {@code slotType}은 {@code contentTypeId}의 <b>기본 슬롯</b>으로 채워 둔다.
     * 실제 슬롯 배정은 호출자가 {@code withSlotType}으로 한다.
     */
    public CandidateBatch fetch(double latitude, double longitude, int contentTypeId) {
        TourApiResult result =
            tourApiClient.search(latitude, longitude, contentTypeId, TourApiClient.MAX_ROWS);

        return switch (result) {
            case TourApiResult.Found found ->
                CandidateBatch.of(toCandidates(found.places(), defaultSlotOf(contentTypeId)));
            case TourApiResult.Empty ignored -> CandidateBatch.empty();
            case TourApiResult.Failed failed -> CandidateBatch.failed(failed.cause());
        };
    }

    private static SlotType defaultSlotOf(int contentTypeId) {
        return contentTypeId == TourApiClient.CONTENT_TYPE_LEISURE
            ? SlotType.EXPERIENCE
            : SlotType.ATTRACTION;
    }

    private static List<PlaceCandidate> toCandidates(List<TourPlace> places, SlotType slotType) {
        List<PlaceCandidate> candidates = new ArrayList<>(places.size());
        int withoutCoordinates = 0;
        for (TourPlace place : places) {
            if (!place.hasCoordinates()) {
                withoutCoordinates++;
                continue;
            }
            candidates.add(new PlaceCandidate(
                CandidateSourceType.LISTED,
                place.title(),
                place.address(),
                place.latitude(),
                place.longitude(),
                slotType,
                // cat3 → 스타일 태그는 필터가 아니라 표시다(4-9). 풀을 자르지 않는다.
                TourCategoryMapper.styleTagsOf(place.cat3()),
                null,
                null,
                // dist 는 API 가 계산해 준다 — anchor 좌표로 다시 잴 이유가 없다(4-7 실측의 이득).
                distanceKm(place),
                place.cat3()));
        }
        if (withoutCoordinates > 0) {
            log.debug("좌표 없는 TourAPI 후보 {}건을 제외했다: slot={}", withoutCoordinates, slotType);
        }
        return candidates;
    }

    private static Double distanceKm(TourPlace place) {
        return place.distanceMeters() == null ? null : place.distanceMeters() / 1000.0;
    }
}
