package backend.yourtrip.global.kakao;

import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;

/**
 * 카카오 장소 검색 1회의 결과. {@link backend.yourtrip.global.naver.NaverLocalResult}와 같은 형태다.
 *
 * <h2>왜 이 타입이 필요한가 (ROADMAP 4-8)</h2>
 * {@link KakaoLocalClient#findBestPlace}는 <b>무결과에 {@code null}, 호출 실패에 예외</b>를 낸다.
 * 한 번 부르고 마는 1-2의 실존 검증에는 충분했지만, 4-8의 지오코딩 캐스케이드는 이 둘을
 * <b>정반대로</b> 다뤄야 한다.
 *
 * <pre>
 *   무결과  → 다음 쿼리로 넘어간다 (anchor → area → location)
 *   실패    → 캐스케이드를 즉시 중단한다
 * </pre>
 *
 * 같은 API가 죽었는데 문자열만 바꿔 두 번 더 두드릴 이유가 없다. 예외로 올라오면 호출부가
 * {@code catch} 안에서 "이건 중단이고 저건 계속"을 되짚어야 하고, 무엇보다 <b>{@code null}과 예외는
 * 같은 {@code if}로 다룰 수 없어</b> 캐스케이드가 두 갈래 제어 흐름으로 쪼개진다.
 *
 * <h2>{@link Found}는 이름 게이트를 통과한 결과다</h2>
 * "검색 결과가 있다"가 아니라 <b>"이름이 맞는 결과가 있다"</b>는 뜻이다. 그래서 Planner가 없는
 * 랜드마크를 지어내면 좌표로 굳지 않고 캐스케이드가 다음 단계로 넘어간다 — 환각이 파이프라인에
 * 박히지 않는다(설계 "area → 좌표" 절).
 */
public sealed interface PlaceLookup {

    /** 이름이 일치하는 후보 중 점수가 가장 높은 하나. */
    record Found(Document document) implements PlaceLookup {

    }

    /** 카카오에 아무것도 없다 — <b>순수 환각</b>(지어낸 이름)의 신호다. */
    record NoResult() implements PlaceLookup {

    }

    /**
     * 결과는 있었는데 <b>이름 게이트에서 전멸했다</b> — 비슷한 게 있으나 이름이 안 맞는
     * <b>세탁 위험 구간</b>이다 (ROADMAP 5-2).
     *
     * <p><b>{@link NoResult}와 갈라야 하는 이유는 두 사건이 다른 것을 뜻하기 때문이다.</b>
     * 배경이 비판한 실수가 정확히 이 구간이었다 — 하한선 없는 {@code score()}가 "그 지역의 무관한
     * POI"를 최고점으로 뽑아 환각을 실존 장소로 <b>세탁</b>했다. 1-2가 이름 게이트로 그 경로를
     * 막았고, 이제 그 게이트가 몇 번 발동했는지가 곧 세탁 시도의 빈도다.
     *
     * <p>둘을 한 값으로 뭉치면 5-6의 {@code ai.grounding.match{result}}가
     * {@code no_result}(순수 환각)와 {@code name_mismatch}(세탁 위험)를 구분할 수 없고, 그러면
     * 환각률 프록시가 <b>무엇이 개선됐는지</b>를 말해주지 못한다.
     *
     * <p><b>호출자의 처리는 {@code NoResult}와 같아도 된다</b> — 검증에서는 그 후보만 탈락,
     * 지오코딩에서는 다음 쿼리로. 다른 것은 <b>기록</b>이다.
     *
     * @param bestCandidateName 카카오가 1순위로 준 상호명. 로그에서 "무엇이 왔길래 걸렀나"를
     *                          바로 보기 위한 것이고 판정에는 쓰지 않는다
     */
    record NameMismatch(String bestCandidateName) implements PlaceLookup {

    }

    /** 물어보지 못했다. <b>캐스케이드를 중단하라</b>는 신호다. */
    record Failed(ApiFailureCause cause, String detail) implements PlaceLookup {

    }
}
