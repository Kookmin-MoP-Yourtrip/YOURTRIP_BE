package backend.yourtrip.global.ai.grounding;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.List;
import java.util.Optional;

/**
 * 슬롯 한 자리에서 그라운딩을 통과한 장소들 — <b>Curator의 선호 순서가 유지된다</b> (ROADMAP 5-2).
 *
 * <p>비어 있을 수 있다(후보 전멸). 그 자리를 드롭할지 보충할지는 7단계가 정한다.
 */
public record GroundedSlot(SlotType slotType, List<GroundedPlace> survivors) {

    public GroundedSlot {
        survivors = survivors == null ? List.of() : List.copyOf(survivors);
    }

    /** 배치될 장소 — 1순위가 통과했으면 그것, 아니면 차순위. */
    public Optional<GroundedPlace> preferred() {
        return survivors.isEmpty() ? Optional.empty() : Optional.of(survivors.get(0));
    }

    public boolean isEmpty() {
        return survivors.isEmpty();
    }
}
