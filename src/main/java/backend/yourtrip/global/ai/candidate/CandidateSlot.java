package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.ai.route.SlotType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 한 day의 한 슬롯에 대한 후보 목록 (ROADMAP 5-8). Curator 입력의 단위이자 6-7 위조 검증의 대조본이다.
 *
 * <p><b>{@code listIndex}를 필드로 두지 않고 리스트 인덱스를 그대로 쓴다.</b> 별도 필드를 두면
 * 정렬·cap 이후 둘이 어긋날 수 있고, 그 어긋남은 아무 오류 없이 6-7의 위조 검증을 무력화한다 —
 * "인덱스가 범위 안이고 이름도 맞는데 실제로는 다른 후보"가 되기 때문이다.
 */
public record CandidateSlot(int day, SlotType slotType, List<PlaceCandidate> candidates) {

    public CandidateSlot {
        Objects.requireNonNull(slotType, "slotType은 필수다");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static CandidateSlot empty(int day, SlotType slotType) {
        return new CandidateSlot(day, slotType, List.of());
    }

    /**
     * Curator가 지목한 {@code listIndex}의 후보. <b>범위 밖이면 빈 값</b>이고, 그때 호출자는
     * 그 후보를 버리는 게 아니라 {@code SUGGESTED}로 강등한다(6-7) — 이름이 실존할 수 있다.
     */
    public Optional<PlaceCandidate> at(Integer listIndex) {
        if (listIndex == null || listIndex < 0 || listIndex >= candidates.size()) {
            return Optional.empty();
        }
        return Optional.of(candidates.get(listIndex));
    }

    public boolean isEmpty() {
        return candidates.isEmpty();
    }
}
