package backend.yourtrip.global.naver;

import java.util.List;

/**
 * 지역검색 호출 1회의 결과. <b>실패를 예외가 아니라 값으로 돌려준다.</b>
 *
 * <p>로드맵 4-1은 반환 타입을 정해두지 않았지만 5-8의 fail-open이 결국 이 형태를 요구한다 —
 * 후보 공급이 실패해도 Curator는 파라메트릭만으로 진행해야 하므로, 호출부가 매번
 * {@code try/catch}로 흐름을 되돌리는 대신 <b>결과를 분기</b>할 수 있어야 한다. 4-8이 카카오
 * 지오코딩에서 같은 이유로 같은 형태를 요구하므로, 두 클라이언트의 계약이 여기서 맞춰진다.
 *
 * <p><b>{@link Empty}를 {@link Failed}와 가르는 것이 이 타입의 핵심</b>이다. 설계는 *"{@code "{area} 카페"}가
 * 0건인 지역은 카페가 실제로 없는 곳"* 이라며 카카오 커버리지를 후보 소스에서 뺐는데, 그 판단이
 * 성립하려면 "0건"과 "못 물어봄"이 구분돼야 한다. 뭉치면 네이버 장애 때 그 지역에 가게가 없는 것처럼
 * 보인다 — 5-6이 {@code no_result}와 {@code failed}를 반드시 갈라야 한다고 못박은 것과 같은 이유다.
 */
public sealed interface NaverLocalResult {

    /** 결과가 있다. {@code places}는 1건 이상이고 {@code seedRank} 오름차순이다. */
    record Found(List<NaverPlace> places) implements NaverLocalResult {

        public Found {
            places = List.copyOf(places);
        }
    }

    /** 호출은 성공했으나 결과가 0건이다. <b>그 지역에 그 업종이 없다는 신호</b>로 읽는다. */
    record Empty() implements NaverLocalResult {

    }

    /** 물어보지 못했다. 후보 공급만 비고 코스 생성은 계속된다(fail-open). */
    record Failed(Cause cause, String detail) implements NaverLocalResult {

    }

    /**
     * 실패 사유. 5단계에서 메트릭 태그가 되므로 처음부터 갈라 둔다.
     *
     * <p>{@link #QUOTA_EXCEEDED}를 따로 두는 이유는 이것만 <b>시간이 지나야 풀리는 실패</b>이기
     * 때문이다. 네이버 지역검색은 일 25,000건 한도이고 코스 1건이 18~30회를 쓰므로 하루 약
     * 830~1,400코스에서 이 분기가 켜진다(ROADMAP 0-5). 다른 실패와 뭉치면 "장애인가 한도인가"를
     * 로그에서 되짚어야 한다.
     */
    enum Cause {
        /** 429. 일일 한도 초과 — 재시도가 아니라 다음 날을 기다려야 하는 실패다. */
        QUOTA_EXCEEDED,
        /** 401/403. 키가 없거나 그 API가 활성화돼 있지 않다(4-2에서 블로그가 이 상태였다). */
        UNAUTHORIZED,
        /** 그 밖의 4xx/5xx. */
        HTTP_ERROR,
        /** 타임아웃·커넥션 실패·풀 고갈. */
        TRANSPORT_ERROR,
        /** 200인데 본문을 읽을 수 없다. */
        MALFORMED
    }

    static NaverLocalResult of(List<NaverPlace> places) {
        return places.isEmpty() ? new Empty() : new Found(places);
    }
}
