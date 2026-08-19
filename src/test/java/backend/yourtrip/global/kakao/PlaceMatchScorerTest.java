package backend.yourtrip.global.kakao;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link PlaceMatchScorer} 단위 테스트.
 *
 * <p><b>이 테스트가 지키는 것은 "점수가 옳다"가 아니라 "점수가 그때와 같다"이다.</b> 환각률
 * baseline이 이 계산으로 측정된 값이라, 여기가 바뀌면 before/after 비교가 무너진다. 그래서 단언이
 * 대체로 <b>구체적인 숫자</b>에 걸려 있다 — 계산을 "개선"하는 변경은 이 테스트를 깨야만 통과한다.
 *
 * <p>점수 계산이 {@code KakaoLocalClient}의 private 메서드였을 때는 이런 테스트를 쓸 수 없었다.
 * 리플렉션을 걷어낸 부수 효과다.
 */
@DisplayName("PlaceMatchScorer — 카카오 후보 점수 계산")
class PlaceMatchScorerTest {

    @Nested
    @DisplayName("가점 구성")
    class Components {

        @Test
        @DisplayName("이름·주소·카테고리가 모두 맞으면 10점이다")
        void awardsFullScore() {
            Document document = document("동궁과 월지", "경북 경주시 원화로 102", "AT4");

            assertThat(PlaceMatchScorer.score(document, "동궁과 월지", "경주")).isEqualTo(10);
        }

        @Test
        @DisplayName("이름만 맞으면 5점이다")
        void awardsNameOnly() {
            Document document = document("동궁과 월지", "서울 중구 세종대로 110", null);

            assertThat(PlaceMatchScorer.score(document, "동궁과 월지", "경주"))
                .isEqualTo(PlaceMatchScorer.NAME_MATCH_SCORE);
        }

        @Test
        @DisplayName("이름은 한쪽이 다른 쪽을 포함하기만 해도 맞는 것으로 본다")
        void treatsContainmentAsNameMatch() {
            assertThat(PlaceMatchScorer.score(
                document("스타벅스 경주황리단길점", "서울 중구 세종대로 110", null), "스타벅스", "경주"))
                .isEqualTo(PlaceMatchScorer.NAME_MATCH_SCORE);
        }

        @Test
        @DisplayName("도로명주소가 없으면 지번주소로 판정한다")
        void fallsBackToLotAddress() {
            Document document = new Document("1", "가게", "음식점", null, null, null,
                "경북 경주시 황남동 1", "", "129.2", "35.8", "http://x", null);

            assertThat(PlaceMatchScorer.bestAddressOf(document)).isEqualTo("경북 경주시 황남동 1");
            assertThat(PlaceMatchScorer.score(document, "없는이름", "경주"))
                .isEqualTo(PlaceMatchScorer.ADDRESS_MATCH_SCORE);
        }

        @Test
        @DisplayName("음식점·카페·관광명소 그룹만 카테고리 가점을 받는다")
        void awardsCategoryOnlyForPreferredGroups() {
            for (String group : new String[] {"FD6", "CE7", "AT4"}) {
                assertThat(PlaceMatchScorer.score(
                    document("가게", "서울 중구 세종대로 110", group), "없는이름", "경주"))
                    .as("%s 는 가점 대상이다", group)
                    .isEqualTo(PlaceMatchScorer.CATEGORY_MATCH_SCORE);
            }
            assertThat(PlaceMatchScorer.score(
                document("가게", "서울 중구 세종대로 110", "PM9"), "없는이름", "경주"))
                .as("약국(PM9)은 가점 대상이 아니다")
                .isZero();
        }
    }

    @Nested
    @DisplayName("점수를 하한선으로 쓸 수 없는 이유 — 이름 게이트의 근거")
    class WhyThresholdAloneIsNotEnough {

        @Test
        @DisplayName("이름이 하나도 안 맞아도 5점이 나온다 — 이 성질이 게이트를 필요하게 만들었다")
        void scoresFiveWithoutAnyNameMatch() {
            // 실측 오매칭 사례: 검색어가 "부산 해운대 시장"이라 주소가 자동으로 맞고,
            // 음식점이라 카테고리도 자동으로 맞는다. 이름은 전혀 다르다.
            Document document = document(
                "개미집 국제시장본점직영점", "부산 해운대구 구남로 34", "FD6");

            int score = PlaceMatchScorer.score(document, "해운대 시장", "부산");

            assertThat(score)
                .as("총점 하한선(예: 5점)으로 걸렀다면 이 오매칭이 그대로 통과한다")
                .isEqualTo(PlaceMatchScorer.ADDRESS_MATCH_SCORE
                    + PlaceMatchScorer.CATEGORY_MATCH_SCORE);
            assertThat(score).isGreaterThanOrEqualTo(PlaceMatchScorer.NAME_MATCH_SCORE);
        }

        @Test
        @DisplayName("정답인데 3점에 머무는 경우가 있다 — 점수와 정확도가 단조 관계가 아니다")
        void correctMatchCanScoreLowerThanWrongOne() {
            // 이름 표기가 달라 이름 가점을 못 받는 정답. 1-2 실측에서 3점 구간은 표본 전부가
            // 정답이었다 — 5~7점 구간(31% 오매칭)보다 오히려 정확했다.
            Document correct = document("동궁과월지", "경북 경주시 원화로 102", null);

            assertThat(PlaceMatchScorer.score(correct, "동궁과 월지", "경주"))
                .as("띄어쓰기 하나 때문에 이름 가점이 빠진다")
                .isEqualTo(PlaceMatchScorer.ADDRESS_MATCH_SCORE);
        }
    }

    @Nested
    @DisplayName("이름 비교 규칙이 게이트와 다르다 — 의도된 차이다")
    class DiffersFromGate {

        @Test
        @DisplayName("점수는 공백을 걷어내지 않는다 — 게이트는 걷어낸다")
        void doesNotNormalizePunctuation() {
            Document document = document("동궁과월지", "서울 중구 세종대로 110", null);

            assertThat(PlaceMatchScorer.score(document, "동궁과 월지", "경주"))
                .as("이 값을 바꾸는 것은 환각률 baseline 을 다시 재야 한다는 뜻이다")
                .isZero();
        }
    }

    @Nested
    @DisplayName("경계")
    class EdgeCases {

        @Test
        @DisplayName("장소명이 비면 이름 가점이 붙지 않는다")
        void skipsNameScoreForBlankInput() {
            Document document = document("가게", "서울 중구 세종대로 110", null);

            assertThat(PlaceMatchScorer.score(document, "  ", "경주")).isZero();
            assertThat(PlaceMatchScorer.score(document, null, "경주")).isZero();
        }

        @Test
        @DisplayName("지역명이 비면 주소 가점이 붙지 않는다")
        void skipsAddressScoreForBlankLocation() {
            Document document = document("가게", "경북 경주시 원화로 102", null);

            assertThat(PlaceMatchScorer.score(document, "없는이름", null)).isZero();
            assertThat(PlaceMatchScorer.score(document, "없는이름", "")).isZero();
        }

        @Test
        @DisplayName("문서가 null이면 0점이다 — 측정 하네스가 예외로 죽으면 안 된다")
        void handlesNullDocument() {
            assertThat(PlaceMatchScorer.score(null, "가게", "경주")).isZero();
            assertThat(PlaceMatchScorer.bestAddressOf(null)).isEmpty();
        }

        @Test
        @DisplayName("상호명이 없는 후보가 이름 가점을 다 받는다 — 원본의 성질이라 그대로 뒀다")
        void awardsNameScoreToNamelessDocument() {
            // 빈 문자열은 모든 문자열에 포함되므로 "가게".contains("") 가 true 다.
            // 고치고 싶어지지만 **환각률 baseline 이 이 계산으로 측정된 값**이라 그대로 둔다.
            //
            // 프로덕션에서는 도달하지 않는다 — findBestPlace 가 이름 게이트를 먼저 거치고
            // PlaceNameNormalizer.similar 는 한쪽이 비면 false 를 준다. 게이트 없이 전 후보를
            // 채점하는 측정 하네스에서만 나타난다.
            Document document = new Document("1", null, null, null, null, null,
                null, null, null, null, null, null);

            assertThat(PlaceMatchScorer.score(document, "가게", "경주"))
                .isEqualTo(PlaceMatchScorer.NAME_MATCH_SCORE);
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private static Document document(String placeName, String roadAddress, String categoryGroup) {
        return new Document("1", placeName, "테스트 > 카테고리", categoryGroup, null, null,
            roadAddress, roadAddress, "129.2", "35.8", "http://place.map.kakao.com/1", null);
    }
}
