package backend.yourtrip.global.ai.candidate;

/**
 * 지오코딩 캐스케이드가 <b>어느 단계에서 좌표를 얻었는지</b> (ROADMAP 4-8).
 *
 * <h2>왜 좌표만 돌려주지 않는가</h2>
 * 로드맵 4-8은 이 값을 메트릭 {@code ai.geocode{result=...}}으로 적어 뒀다. 그런데 <b>메트릭은
 * 5-6이 붙인다</b> — 5-6이 *"이 저장소 최초의 커스텀 Micrometer 메트릭이라 {@code MeterRegistry}
 * 주입 패턴을 여기서 세운다"* 고 못박았기 때문에, 4-8이 먼저 붙이면 그 문장이 거짓이 되고 패턴이
 * 두 곳에서 따로 선다. 그래서 <b>결과 값에 실어만 두고</b> 5단계가 옮기기만 하게 한다.
 *
 * <p>실용적인 값이기도 하다. {@link #FALLBACK_LOCATION}이 잦다는 것은 Planner의 {@code anchor}가
 * 쓸모없다는 뜻이고, 그러면 좌표가 도시 중심으로 뭉뚱그려져 day별 권역 분리가 사라진다 —
 * 좌표만 보면 성공으로 보이지만 설계가 의도한 것과는 다른 결과다.
 *
 * <h2>{@link #NO_RESULT}는 로드맵에 없던 값이다</h2>
 * 로드맵은 {@code hit|fallback_area|fallback_location|failed} 넷만 적었지만, <b>"세 번 다 물어봤는데
 * 없더라"와 "물어보지 못했다"는 다른 사건</b>이다. 전자는 그 지역에 등록된 대표 장소가 없다는
 * 신호이고 후자는 카카오 장애다. 4-1의 {@code Empty}/{@code Failed}, 5-6의 {@code no_result}/
 * {@code failed}가 같은 이유로 갈라져 있으므로 여기서만 뭉치면 어긋난다.
 */
public enum GeocodeOutcome {

    /** Planner의 {@code anchor}로 바로 찾았다. 설계가 의도한 정상 경로다. */
    HIT,

    /** {@code anchor}가 없거나 못 찾아 {@code area} 텍스트로 찾았다. */
    FALLBACK_AREA,

    /** {@code area}로도 못 찾아 여행지 이름으로 찾았다. <b>day별 권역 분리가 사라진 상태다.</b> */
    FALLBACK_LOCATION,

    /** 세 단계 모두 결과가 없었다. 그 day의 TourAPI를 건너뛴다. */
    NO_RESULT,

    /** 카카오 호출이 실패해 캐스케이드를 중단했다. 그 day의 TourAPI를 건너뛴다. */
    FAILED;

    /** 좌표를 얻은 경우인가. {@code true}면 {@code GeocodeResult}의 좌표가 반드시 채워져 있다. */
    public boolean isResolved() {
        return this == HIT || this == FALLBACK_AREA || this == FALLBACK_LOCATION;
    }
}
