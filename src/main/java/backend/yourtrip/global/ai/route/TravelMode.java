package backend.yourtrip.global.ai.route;

import lombok.Getter;

/**
 * 이동수단 — 두 장소 사이 이동에 몇 분이 걸리는지를 결정한다(설계 문서 §5-2).
 *
 * <p><b>haversine은 직선거리라 실제 도로 거리보다 짧다</b>(도심 우회계수 통상 1.2~1.4). 우회계수를
 * 별도 파라미터로 두는 대신 <b>유효속도를 낮춰 흡수했다</b> — 파라미터 하나를 아끼고, 두 값이 서로
 * 상쇄되는 방향으로 움직여 튜닝이 혼란스러워지는 것을 피한다. 그래서 아래 속도는 "실제 이동속도"가
 * 아니라 "직선거리에 곱했을 때 현실적인 소요시간이 나오는 값"이다.
 *
 * <p><b>고정 오버헤드가 따로 있는 이유.</b> 거리가 0이어도 이동에는 시간이 든다 — 환승 대기, 주차
 * 자리 찾기, 건물 진입. 이게 없으면 같은 건물 안 두 장소를 0분 만에 이동하는 것으로 계산된다.
 *
 * <p><b>{@code KeywordType}(도메인 enum)을 재사용하지 않는 이유</b>는 셋이다.
 * <ol>
 *   <li><b>의존 방향</b> — {@code global/ai/route}(순수 알고리즘)가
 *       {@code domain/uploadcourse}를 참조하는 것은 역방향이다. 게다가 {@code KeywordType}은
 *       Jackson 어노테이션·{@code ObjectMapper}·{@code BusinessException}을 달고 있어, 이 단계의
 *       "외부 의존이 없는 순수 함수"라는 성질을 시그니처 한 줄로 깬다.</li>
 *   <li><b>불법 상태를 막지 못한다</b> — {@code KeywordType}은 22개 상수 중 2개만 이동수단이다.
 *       그대로 받으면 최적화기가 {@code HEALING}이 들어왔을 때를 처리해야 하는데, 그건 애초에
 *       존재하면 안 되는 입력이다.</li>
 *   <li><b>"미지정"을 null이 아니라 상수로 표현할 수 있다</b> — 기본 이동수단이 값 하나로
 *       드러나므로 호출부가 null 분기를 쓰지 않는다.</li>
 * </ol>
 *
 * <p>{@code KeywordType → TravelMode} 매핑은 <b>7단계 파이프라인의 몫이다.</b> 여기에
 * {@code fromKeywords()} 같은 팩터리를 두면 방금 끊어낸 의존이 그대로 돌아온다. 사용자가 뚜벅이와
 * 자차를 동시에 고르는 경우는 {@link #UNSPECIFIED}(중간값)로 흡수한다.
 */
@Getter
public enum TravelMode {

    /** 뚜벅이 — 도보와 대중교통을 섞어 다닌다. 오버헤드는 환승·배차 대기. */
    WALK(12.0, 10),

    /** 자차 — 도심 평균 주행. 오버헤드는 주차. */
    CAR(25.0, 5),

    /** 이동수단을 고르지 않았거나 여러 개를 골랐을 때의 중간값. */
    UNSPECIFIED(15.0, 8);

    /** 직선거리에 적용하는 유효속도(km/h). 실제 이동속도가 아니라 우회계수를 흡수한 값이다. */
    private final double effectiveSpeedKmh;

    /** 거리와 무관하게 매 이동에 붙는 시간(분). */
    private final int fixedOverheadMinutes;

    TravelMode(double effectiveSpeedKmh, int fixedOverheadMinutes) {
        this.effectiveSpeedKmh = effectiveSpeedKmh;
        this.fixedOverheadMinutes = fixedOverheadMinutes;
    }
}
