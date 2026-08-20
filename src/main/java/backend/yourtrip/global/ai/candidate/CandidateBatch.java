package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.global.common.ApiFailureCause;
import java.util.List;

/**
 * 후보 소스 호출 하나의 결과 (ROADMAP 5-8).
 *
 * <p><b>예외를 던지지 않는다.</b> 후보 공급은 전부 fail-open이라 개별 실패가 코스를 죽이면 안 되고,
 * 실패 사유는 메트릭 태그로 남아야 한다 — 예외로 올리면 호출부가 사유를 다시 복원해야 한다.
 * {@code NaverLocalResult}·{@code TourApiResult}·{@code PlaceLookup}이 세운 관례를 따른다.
 *
 * @param cause {@link CandidateOutcome#FAILED}일 때만 채워진다
 */
public record CandidateBatch(List<PlaceCandidate> candidates, CandidateOutcome outcome,
                             ApiFailureCause cause) {

    public CandidateBatch {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static CandidateBatch of(List<PlaceCandidate> candidates) {
        return candidates == null || candidates.isEmpty()
            ? empty()
            : new CandidateBatch(candidates, CandidateOutcome.HIT, null);
    }

    public static CandidateBatch empty() {
        return new CandidateBatch(List.of(), CandidateOutcome.EMPTY, null);
    }

    public static CandidateBatch failed(ApiFailureCause cause) {
        return new CandidateBatch(List.of(), CandidateOutcome.FAILED, cause);
    }

    public static CandidateBatch skipped() {
        return new CandidateBatch(List.of(), CandidateOutcome.SKIPPED, null);
    }
}
