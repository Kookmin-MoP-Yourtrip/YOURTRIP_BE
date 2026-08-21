package backend.yourtrip.global.ai.candidate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.naver.NaverLocalClient;
import backend.yourtrip.global.naver.NaverLocalResult;
import backend.yourtrip.global.naver.NaverPlace;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("NaverLocalSeedSource — 시더 응답을 후보로 (ROADMAP 5-8)")
class NaverLocalSeedSourceTest {

    private static final double ANCHOR_LAT = 35.8386877792;   // 천마총
    private static final double ANCHOR_LON = 129.2104983997;

    @Mock
    private NaverLocalClient naverLocalClient;

    @InjectMocks
    private NaverLocalSeedSource source;

    private static NaverPlace attraction(String name, Double latitude, Double longitude,
        int seedRank) {
        return new NaverPlace(name, "여행,명소>관광,명소", "전남 순천시 순천만길 513", "전남 순천시 대대동",
            "", latitude, longitude, seedRank);
    }

    private static NaverPlace place(String name, Double latitude, Double longitude, int seedRank) {
        return new NaverPlace(name, "음식점>카페,디저트", "경북 경주시 포석로 1080", "경북 경주시 황남동",
            "", latitude, longitude, seedRank);
    }

    @Nested
    @DisplayName("검색어 조립 — 4-3 실측 표기를 따른다")
    class QueryBuilding {

        @Test
        @DisplayName("기본 쿼리는 area 뒤에 슬롯 힌트를 붙인 것이다")
        void basicQuery() {
            assertThat(NaverLocalSeedSource.buildQuery("황리단길", SlotType.CAFE, null))
                .isEqualTo("황리단길 카페");
        }

        @Test
        @DisplayName("modifier 는 슬롯 힌트 앞에 온다 — 황리단길 루프탑 카페")
        void modifierPrecedesHint() {
            assertThat(NaverLocalSeedSource.buildQuery("황리단길", SlotType.CAFE, StyleTag.ROOFTOP))
                .isEqualTo("황리단길 루프탑 카페");
        }

        @Test
        @DisplayName("검색어가 죽은 태그는 수식어 없이 기본 쿼리가 된다 — 4-3에서 비운 표기들")
        void skipsTagWithoutSearchTerm() {
            // 통창은 3라운드 실측에서 결과가 무의미해 검색어를 비웠다. 그 태그가 정렬용으로
            // 흘러들어와도 쿼리를 망치지 않아야 한다.
            assertThat(NaverLocalSeedSource.buildQuery("황리단길", SlotType.CAFE,
                StyleTag.PANORAMIC_WINDOW)).isEqualTo("황리단길 카페");
        }

        @Test
        @DisplayName("area 가 비면 슬롯 힌트만 남는다 — Planner 폴백 경로")
        void blankArea() {
            assertThat(NaverLocalSeedSource.buildQuery("  ", SlotType.MEAL, null))
                .isEqualTo("맛집");
        }

        @Test
        @DisplayName("조립한 쿼리를 그대로 클라이언트에 넘긴다")
        void passesQueryToClient() {
            when(naverLocalClient.search(anyString(), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());

            source.fetch("황리단길", List.of(), SlotType.CAFE, StyleTag.ROOFTOP, null, null);

            verify(naverLocalClient).search(eq("황리단길 루프탑 카페"), eq(NaverLocalClient.MAX_DISPLAY));
        }
    }

    @Nested
    @DisplayName("응답 변환")
    class Mapping {

        @Test
        @DisplayName("시드 순위·출처·modifier 힌트를 실어 후보로 만든다")
        void mapsSeedFields() {
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(List.of(place("커피플레이스", 35.83, 129.21, 1))));

            CandidateBatch batch =
                source.fetch("황리단길", List.of(), SlotType.CAFE, StyleTag.ROOFTOP, null, null);

            assertThat(batch.outcome()).isEqualTo(CandidateOutcome.HIT);
            PlaceCandidate candidate = batch.candidates().get(0);
            assertThat(candidate.source()).isEqualTo(CandidateSourceType.SEEDED);
            assertThat(candidate.seedRank()).isEqualTo(1);
            assertThat(candidate.matchedModifier()).isEqualTo(StyleTag.ROOFTOP);
            assertThat(candidate.styleTags()).containsExactly(StyleTag.ROOFTOP);
            assertThat(candidate.rawCategory()).isEqualTo("음식점>카페,디저트");
        }

        @Test
        @DisplayName("기본 쿼리 후보에는 스타일 표식이 붙지 않는다")
        void basicQueryHasNoStyleMark() {
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(List.of(place("커피플레이스", 35.83, 129.21, 1))));

            PlaceCandidate candidate =
                source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null).candidates().get(0);

            assertThat(candidate.matchedModifier()).isNull();
            assertThat(candidate.styleTags()).isEmpty();
        }

        @Test
        @DisplayName("좌표 없는 항목은 후보가 되지 못한다 — 거르는 책임이 소스에 있다")
        void dropsPlacesWithoutCoordinates() {
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(List.of(
                    place("좌표없음", null, null, 1),
                    place("커피플레이스", 35.83, 129.21, 2))));

            CandidateBatch batch = source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null);

            assertThat(batch.candidates()).extracting(PlaceCandidate::name)
                .containsExactly("커피플레이스");
        }

        @Test
        @DisplayName("좌표 있는 항목이 하나도 없으면 EMPTY 다 — 실패가 아니다")
        void allDroppedBecomesEmpty() {
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(List.of(place("좌표없음", null, null, 1))));

            assertThat(source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null).outcome())
                .isEqualTo(CandidateOutcome.EMPTY);
        }

        @Test
        @DisplayName("anchor 좌표가 있으면 거리를 채운다")
        void fillsDistanceWhenAnchorKnown() {
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(
                    List.of(place("커피플레이스", ANCHOR_LAT, ANCHOR_LON, 1))));

            PlaceCandidate candidate = source
                .fetch("황리단길", List.of(), SlotType.CAFE, null, ANCHOR_LAT, ANCHOR_LON)
                .candidates().get(0);

            assertThat(candidate.distanceKm()).isNotNull().isCloseTo(0.0, within(0.001));
        }

        @Test
        @DisplayName("매핑이 아는 분류가 슬롯과 어긋나면 후보가 되지 못한다 (ROADMAP 5-3)")
        void dropsCategoryMismatch() {
            // 카페를 물었는데 국밥집이 왔다. 풀에 넣으면 Curator 입력 토큰만 먹고,
            // 골라지면 "카페 자리에 국밥집"이 된다.
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(List.of(
                    new NaverPlace("황남국밥", "음식점>한식>국밥", "경북 경주시 포석로 1", "", "",
                        35.83, 129.21, 1),
                    place("커피플레이스", 35.83, 129.21, 2))));

            assertThat(source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null).candidates())
                .extracting(PlaceCandidate::name).containsExactly("커피플레이스");
        }

        @Test
        @DisplayName("매핑에 없는 분류는 통과시킨다 — 하드 드롭은 매핑이 아는 것에만 건다")
        void passesUnmappedCategory() {
            // 주유소는 사전 어느 규칙에도 걸리지 않는다. 모르는 것을 불일치로 취급하면
            // 실존하는 장소가 이유 없이 탈락한다(4-4의 "통과시키되 표시한다").
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(List.of(
                    new NaverPlace("경주주유소", "자동차>주유소", "경북 경주시 포석로 1", "", "",
                        35.83, 129.21, 1))));

            assertThat(source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null).candidates())
                .hasSize(1);
        }

        @Test
        @DisplayName("anchor 좌표가 없으면 거리를 비운다 — 모르는 것을 0으로 적지 않는다")
        void leavesDistanceNullWithoutAnchor() {
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Found(List.of(place("커피플레이스", 35.83, 129.21, 1))));

            assertThat(source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null)
                .candidates().get(0).distanceKm()).isNull();
        }
    }

    @Nested
    @DisplayName("실패는 값으로 돌려준다")
    class Failures {

        @Test
        @DisplayName("0건은 EMPTY — 그 지역·슬롯의 데이터가 얇다는 신호다")
        void emptyResult() {
            when(naverLocalClient.search(anyString(), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());

            assertThat(source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null).outcome())
                .isEqualTo(CandidateOutcome.EMPTY);
        }

        @Test
        @DisplayName("쿼터 초과는 FAILED 로 사유까지 남긴다 — EMPTY 와 뭉치면 지표가 오염된다")
        void quotaExceeded() {
            when(naverLocalClient.search(anyString(), anyInt())).thenReturn(
                new NaverLocalResult.Failed(ApiFailureCause.QUOTA_EXCEEDED, "429"));

            CandidateBatch batch = source.fetch("황리단길", List.of(), SlotType.CAFE, null, null, null);

            assertThat(batch.outcome()).isEqualTo(CandidateOutcome.FAILED);
            assertThat(batch.cause()).isEqualTo(ApiFailureCause.QUOTA_EXCEEDED);
            assertThat(batch.candidates()).isEmpty();
        }
    }
    @Nested
    @DisplayName("0건일 때 넓혀 다시 묻는다 (이슈 #110)")
    class Fallback {

        /**
         * 실측에서 {@code 송산리고분군} 권역의 MEAL·CAFE 가 그랬다 — 유적·고분군처럼 상권이 없는
         * 지명이 권역명이 되면 표기 정규화만으로는 살아나지 않는다.
         */
        @Test
        @DisplayName("권역명이 0건이면 넓은 지명으로 한 번 더 묻는다")
        void retriesWithFallbackArea() {
            when(naverLocalClient.search(eq("송산리고분군 카페"), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());
            when(naverLocalClient.search(eq("공주 카페"), anyInt()))
                .thenReturn(new NaverLocalResult.Found(List.of(
                    place("루치아의 뜰", 36.45, 127.12, 1))));

            CandidateBatch batch = source.fetch("송산리고분군·국립공주박물관 일대", List.of("공주"),
                SlotType.CAFE, null, null, null);

            assertThat(batch.candidates()).extracting(PlaceCandidate::name)
                .containsExactly("루치아의 뜰");
        }

        @Test
        @DisplayName("권역명이 결과를 주면 다시 묻지 않는다")
        void doesNotRetryWhenFound() {
            when(naverLocalClient.search(eq("황리단길 카페"), anyInt()))
                .thenReturn(new NaverLocalResult.Found(List.of(
                    place("카페 A", 35.83, 129.21, 1))));

            source.fetch("황리단길·대릉원 일대", List.of("경주"), SlotType.CAFE, null, null, null);

            verify(naverLocalClient, never()).search(eq("경주 카페"), anyInt());
        }

        @Test
        @DisplayName("modifier 쿼리는 다시 묻지 않는다 — 기본이 0건이면 그쪽도 0건일 가능성이 높다")
        void doesNotRetryModifierQuery() {
            when(naverLocalClient.search(anyString(), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());

            source.fetch("송산리고분군 일대", List.of("공주"), SlotType.CAFE, StyleTag.ROOFTOP, null, null);

            verify(naverLocalClient).search(eq("송산리고분군 루프탑 카페"), anyInt());
            verify(naverLocalClient, never()).search(eq("공주 루프탑 카페"), anyInt());
        }

        @Test
        @DisplayName("호출 실패에는 다시 묻지 않는다 — '없더라'와 '못 물었다'는 다른 사건이다")
        void doesNotRetryOnFailure() {
            when(naverLocalClient.search(anyString(), anyInt()))
                .thenReturn(new NaverLocalResult.Failed(ApiFailureCause.QUOTA_EXCEEDED, "429"));

            CandidateBatch batch =
                source.fetch("송산리고분군 일대", List.of("공주"), SlotType.CAFE, null, null, null);

            assertThat(batch.outcome()).isEqualTo(CandidateOutcome.FAILED);
            verify(naverLocalClient).search(eq("송산리고분군 카페"), anyInt());
            verify(naverLocalClient, never()).search(eq("공주 카페"), anyInt());
        }

        @Test
        @DisplayName("업종 불일치로 전멸한 경우도 다시 묻는다 — 풀 입장에서는 0건인 것이 같다")
        void retriesWhenEveryPlaceIsFilteredOut() {
            when(naverLocalClient.search(eq("송산리고분군 맛집"), anyInt()))
                // 5건이 왔지만 분류가 카페라 MEAL 슬롯에서 전부 탈락한다.
                .thenReturn(new NaverLocalResult.Found(List.of(
                    place("카페 A", 36.45, 127.12, 1))));
            when(naverLocalClient.search(eq("공주 맛집"), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());

            source.fetch("송산리고분군 일대", List.of("공주"), SlotType.MEAL, null, null, null);

            verify(naverLocalClient).search(eq("공주 맛집"), anyInt());
        }

        /**
         * 실측 근거: area 질의가 0건이던 4칸 중 <b>3칸이 anchor 에서 살아났다</b>. 그렇게 살아난
         * 후보는 권역 안에 머물러(거리 중앙값 0.61km) day 를 나눈 의미가 유지된다 — 곧장
         * location 으로 넓히면 6.2km 로 벌어진다.
         */
        @Test
        @DisplayName("area 가 0건이면 location 보다 anchor 를 먼저 물어본다")
        void triesAnchorBeforeLocation() {
            when(naverLocalClient.search(eq("송산리고분군 카페"), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());
            when(naverLocalClient.search(eq("무령왕릉 카페"), anyInt()))
                .thenReturn(new NaverLocalResult.Found(List.of(
                    place("루치아의 뜰", 36.45, 127.12, 1))));

            CandidateBatch batch = source.fetch("송산리고분군·박물관 일대",
                List.of("무령왕릉", "공주"), SlotType.CAFE, null, null, null);

            assertThat(batch.candidates()).extracting(PlaceCandidate::name)
                .containsExactly("루치아의 뜰");
            verify(naverLocalClient, never()).search(eq("공주 카페"), anyInt());
        }

        @Test
        @DisplayName("anchor 로도 0건이면 그때 location 까지 내려간다")
        void fallsThroughToLocation() {
            when(naverLocalClient.search(eq("원도심 관광명소"), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());
            when(naverLocalClient.search(eq("순천부읍성 관광명소"), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());
            when(naverLocalClient.search(eq("순천 관광명소"), anyInt()))
                .thenReturn(new NaverLocalResult.Found(List.of(
                    attraction("순천만습지", 34.88, 127.50, 1))));

            CandidateBatch batch = source.fetch("원도심·문화의거리 일대",
                List.of("순천부읍성", "순천"), SlotType.ATTRACTION, null, null, null);

            assertThat(batch.candidates()).extracting(PlaceCandidate::name)
                .containsExactly("순천만습지");
        }

        @Test
        @DisplayName("anchor 가 정규화 결과와 같으면 건너뛴다 — 관측상 60%가 이 경우다")
        void skipsAnchorWhenItRepeatsTheAreaQuery() {
            when(naverLocalClient.search(anyString(), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());

            source.fetch("안목해변·송정동 일대", List.of("안목해변", "강릉"),
                SlotType.CAFE, null, null, null);

            verify(naverLocalClient).search(eq("안목해변 카페"), anyInt());
            verify(naverLocalClient).search(eq("강릉 카페"), anyInt());
            verify(naverLocalClient, times(2)).search(anyString(), anyInt());
        }

        @Test
        @DisplayName("정규화 결과가 이미 넓은 지명과 같으면 다시 묻지 않는다 — 같은 쿼리를 두 번 던지는 셈이다")
        void doesNotRetryWhenQueryWouldRepeat() {
            when(naverLocalClient.search(anyString(), anyInt()))
                .thenReturn(new NaverLocalResult.Empty());

            source.fetch("공주 일대", List.of("공주"), SlotType.CAFE, null, null, null);

            verify(naverLocalClient).search(eq("공주 카페"), anyInt());
        }
    }
}
