package backend.yourtrip.global.common;

/**
 * 외부 API 호출이 <b>실패한 이유</b>. 후보 공급 클라이언트들이 공유하는 어휘다.
 *
 * <h2>왜 클라이언트마다 따로 두지 않는가</h2>
 * 4-1에서 이 목록을 {@code NaverLocalResult.Cause}로 처음 만들었는데, 4-8의 카카오 지오코딩과
 * 4-7의 TourAPI가 같은 목록을 그대로 필요로 한다. 세 벌이 되면 <b>drift가 생긴다</b> —
 * {@code KakaoLocalClient.normalize}를 4-5가 공용으로 끌어올리기로 한 것과 같은 이유다.
 *
 * <p>더 실질적인 이유는 <b>5-6이 이 값을 메트릭 태그로 쓴다</b>는 것이다. 후보 공급 실패를
 * {@code ai.candidate.fetch{provider=..., cause=...}} 한 지표로 보려면 provider가 달라도 태그 값의
 * 어휘가 같아야 한다. 클라이언트마다 enum이 다르면 대시보드에서 provider별로 쿼리를 따로 짜야 한다.
 *
 * <h2>같은 어휘를 쓰되 <b>분류하는 방법은 클라이언트마다 다르다</b></h2>
 * 이 enum은 "무엇이 실패인가"만 정하고 "어떤 응답이 어느 실패인가"는 각 클라이언트가 정한다.
 * 실제로 판정 채널이 서로 다르다 — 네이버·카카오는 HTTP 상태코드로 가르지만, TourAPI는
 * <b>200 응답 본문에 담긴 {@code resultCode}</b>로 실패를 알린다
 * ({@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR} 등). 어휘를 공유해도 각자의 {@code classify()}는
 * 그대로 남는다.
 */
public enum ApiFailureCause {

    /**
     * 일일·초당 호출 한도 초과. <b>이것만 시간이 지나야 풀리는 실패</b>라서 따로 둔다.
     *
     * <p>네이버 지역검색은 일 25,000건이고 코스 1건이 18~30회를 쓰므로 하루 약 830~1,400코스에서,
     * TourAPI 개발계정은 일 1,000건이므로 하루 약 110코스에서 이 분기가 켜진다(ROADMAP 0-5).
     * 다른 실패와 뭉치면 "장애인가 한도인가"를 로그에서 되짚어야 한다.
     */
    QUOTA_EXCEEDED,

    /** 키가 없거나, 있어도 그 API가 활성화돼 있지 않다(4-2에서 네이버 블로그 검색이 이 상태였다). */
    UNAUTHORIZED,

    /** 그 밖의 4xx/5xx. */
    HTTP_ERROR,

    /** 타임아웃·커넥션 실패·풀 고갈. 응답 자체를 받지 못했다. */
    TRANSPORT_ERROR,

    /** 응답은 받았는데 본문을 읽을 수 없다. */
    MALFORMED
}
