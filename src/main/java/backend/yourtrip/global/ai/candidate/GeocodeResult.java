package backend.yourtrip.global.ai.candidate;

/**
 * 권역 중심 좌표 조회 결과 (ROADMAP 4-8).
 *
 * <p><b>좌표는 nullable이다</b> — {@code NaverPlace}가 같은 이유로 그렇게 두었다. 못 찾은 것을
 * {@code 0.0/0.0}으로 위장하면 아프리카 앞바다를 중심으로 TourAPI를 조회하게 된다.
 * {@link GeocodeOutcome#isResolved()}가 좌표 유무와 항상 일치하도록 생성자에서 강제한다.
 */
public record GeocodeResult(Double latitude, Double longitude, GeocodeOutcome outcome) {

    public GeocodeResult {
        boolean hasCoordinate = latitude != null && longitude != null;
        if (hasCoordinate != outcome.isResolved()) {
            // 값과 상태가 어긋난 결과는 만들어질 수 없어야 한다. 이 불변식이 깨지면
            // 호출부가 outcome 만 보고 분기했을 때 NullPointerException 으로 드러난다.
            throw new IllegalArgumentException(
                "좌표 유무와 outcome이 어긋난다: outcome=" + outcome
                    + ", latitude=" + latitude + ", longitude=" + longitude);
        }
    }

    public static GeocodeResult resolved(double latitude, double longitude,
        GeocodeOutcome outcome) {
        return new GeocodeResult(latitude, longitude, outcome);
    }

    /** 세 단계 모두 결과가 없었다. */
    public static GeocodeResult noResult() {
        return new GeocodeResult(null, null, GeocodeOutcome.NO_RESULT);
    }

    /** 호출이 실패해 캐스케이드를 중단했다. */
    public static GeocodeResult failed() {
        return new GeocodeResult(null, null, GeocodeOutcome.FAILED);
    }

    public boolean hasCoordinate() {
        return outcome.isResolved();
    }
}
