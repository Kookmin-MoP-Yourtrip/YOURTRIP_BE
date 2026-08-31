package backend.yourtrip.global.tour;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TourGridKey} 단위 테스트 (ROADMAP 4-7 · 4-6).
 *
 * <p>이 함수의 존재 이유는 <b>캐시 적중</b>이라, 검증할 것도 "값이 맞는가"가 아니라
 * <b>"가까운 좌표가 같은 키로 뭉치는가"</b>다.
 */
@DisplayName("TourGridKey — 약 1km 격자 캐시 키 (ROADMAP 4-7)")
class TourGridKeyTest {

    @Test
    @DisplayName("같은 격자 안의 좌표는 같은 키가 된다 — 이것이 캐시 적중의 조건이다")
    void snapsNearbyCoordinatesToTheSameCell() {
        // 대릉원 주변에서 카카오가 1등으로 주는 장소가 조금씩 달라도 같은 칸에 들어가야 한다.
        String a = TourGridKey.of(35.8341, 129.2091, 12);
        String b = TourGridKey.of(35.8389, 129.2099, 12);

        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("격자가 다르면 키도 다르다 — 뭉치기만 하면 권역 분리가 캐시에서 무너진다")
    void separatesDistantCoordinates() {
        String gyeongju = TourGridKey.of(35.8347, 129.2094, 12);
        String bomun = TourGridKey.of(35.8480, 129.2810, 12);

        assertThat(gyeongju).isNotEqualTo(bomun);
    }

    @Test
    @DisplayName("contentTypeId가 다르면 키도 다르다 — 관광지 목록이 문화시설 자리에 오면 안 된다")
    void separatesContentTypes() {
        assertThat(TourGridKey.of(35.8347, 129.2094, 12))
            .isNotEqualTo(TourGridKey.of(35.8347, 129.2094, 14));
    }

    @Test
    @DisplayName("부동소수점 표기가 흔들려도 같은 키다 — 나눗셈 결과를 그대로 이어붙이면 깨진다")
    void isStableAcrossFloatingPointNoise() {
        assertThat(TourGridKey.of(35.834999999999994, 129.2094, 12))
            .isEqualTo(TourGridKey.of(35.835000000000001, 129.2094, 12));
    }

    @Test
    @DisplayName("경계에서 -0.00 같은 표기가 새지 않는다")
    void doesNotProduceNegativeZero() {
        assertThat(TourGridKey.of(0.0, 0.0, 12)).doesNotContain("-0.00");
    }

    @Test
    @DisplayName("같은 입력은 항상 같은 키다")
    void isDeterministic() {
        assertThat(TourGridKey.of(35.8347, 129.2094, 12))
            .isEqualTo(TourGridKey.of(35.8347, 129.2094, 12));
    }
}
