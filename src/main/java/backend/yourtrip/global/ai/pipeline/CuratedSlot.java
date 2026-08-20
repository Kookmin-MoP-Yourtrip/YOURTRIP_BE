package backend.yourtrip.global.ai.pipeline;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.List;

/**
 * 슬롯 한 자리에 대한 Curator의 선택 — <b>선호 순서로 3개</b> (ROADMAP 6-4).
 *
 * <p><b>Curator의 출력 순서가 최종 순위다.</b> 사후 재정렬 층(PlaceSignal)이 V1에 없으므로 이
 * 순서가 곧 "어느 후보가 코스에 들어가는가"를 정한다 — 1순위가 그라운딩을 통과하면 배치되고,
 * 탈락하면 2순위가 올라온다.
 *
 * <p><b>슬롯 자리는 리스트 위치로 구분하고 타입으로 구분하지 않는다.</b> day 구성에 같은 타입이
 * 여러 번 올 수 있어서다({@code [ATTRACTION, MEAL, CAFE, ATTRACTION, MEAL]}).
 */
public record CuratedSlot(SlotType slotType, List<CuratedPlace> choices) {

    public CuratedSlot {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }
}
