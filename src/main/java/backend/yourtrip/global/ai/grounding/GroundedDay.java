package backend.yourtrip.global.ai.grounding;

import java.util.List;

/** 그라운딩을 마친 day 하나 (ROADMAP 5-2). 슬롯 자리 순서가 유지된다. */
public record GroundedDay(int day, List<GroundedSlot> slots) {

    public GroundedDay {
        slots = slots == null ? List.of() : List.copyOf(slots);
    }
}
