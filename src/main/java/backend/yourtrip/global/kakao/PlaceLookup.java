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
 * 랜드마크를 지어내면 {@link NoResult}가 되어 캐스케이드가 다음 단계로 넘어간다 — 환각이
 * 좌표로 굳어 파이프라인에 박히지 않는다(설계 "area → 좌표" 절).
 */
public sealed interface PlaceLookup {

    /** 이름이 일치하는 후보 중 점수가 가장 높은 하나. */
    record Found(Document document) implements PlaceLookup {

    }

    /** 호출은 성공했으나 쓸 수 있는 후보가 없다. <b>다음 쿼리로 넘어가라</b>는 신호다. */
    record NoResult() implements PlaceLookup {

    }

    /** 물어보지 못했다. <b>캐스케이드를 중단하라</b>는 신호다. */
    record Failed(ApiFailureCause cause, String detail) implements PlaceLookup {

    }
}
