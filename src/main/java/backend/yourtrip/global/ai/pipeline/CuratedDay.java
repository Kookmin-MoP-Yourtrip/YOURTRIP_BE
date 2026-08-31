package backend.yourtrip.global.ai.pipeline;

import java.util.List;

/**
 * Curator 한 번 호출의 출력 — day 하나의 슬롯 전부 (ROADMAP 6-4).
 *
 * <p>Curator는 day별로 병렬 실행되고 <b>다른 day는 모른다.</b> 전 day에 걸친 중복 제거는 그래서
 * 프롬프트가 아니라 코드(그라운딩)의 몫이다.
 */
public record CuratedDay(int day, List<CuratedSlot> slots) {

    public CuratedDay {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}
