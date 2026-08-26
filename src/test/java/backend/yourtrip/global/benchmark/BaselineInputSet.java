package backend.yourtrip.global.benchmark;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.ArrayList;
import java.util.List;

/**
 * AI 코스 생성 측정이 공유하는 <b>입력 세트</b> — 지역 10곳 × 키워드 조합 3개 = 30요청.
 *
 * <p><b>이 값들이 왜 한 곳에 모여 있어야 하는가.</b> 측정점끼리 비교가 성립하려면 같은 입력을
 * 넣어야 한다({@code AI-HALLUCINATION-GEMINI.md}의 "재측정 재현 조건"). 하네스마다 자기 사본을
 * 두면 언제든 한쪽만 바뀔 수 있고, 그렇게 어긋난 뒤에는 <b>과거 산출물과 비교가 깨졌다는 사실
 * 자체가 드러나지 않는다</b> — 두 CSV 모두 30행이라 겉으로는 멀쩡해 보이기 때문이다.
 *
 * <p>원래 {@code AiHallucinationBaselineTest} 안에 {@code private}으로 있었다. ROADMAP 3-7이
 * 같은 세트로 파이프라인을 돌려야 해서 여기로 뗐고, {@code STEP-8}이 8-6 계획에 적어 둔
 * "공유 자산은 헬퍼로 추출해 양쪽이 쓴다"의 첫 조각이다. <b>옮기면서 값을 한 글자도 바꾸지
 * 않았다</b> — {@code LegacyGeminiPrompt}를 이관할 때와 같은 원칙이다.
 *
 * <p><b>{@code requestId}가 매겨지는 순서도 계약이다.</b> 지역이 바깥 루프라 1~3 = 경주 A/B/C,
 * 4~6 = 부산 … 28~30 = 삼척이 되고, 이 번호가 기존 산출물 CSV 의 행과 대조하는 열쇠다.
 * 루프를 뒤집으면 옛 CSV 와 조인이 조용히 어긋난다.
 */
public final class BaselineInputSet {

    private BaselineInputSet() {
    }

    /** 여행 일수. 일수까지 변수로 두면 표본이 흩어져 지역·키워드 효과를 못 본다. */
    public static final int TRIP_DAYS = 3;

    /**
     * 지역 인지도. <b>이 축을 통제하지 않으면</b> 측정값이 표본에 어떤 지역이 많이 들었는지에
     * 따라 요행으로 오르내린다 — 그래서 유명 5 : 무인지 5로 고정한다.
     */
    public enum RegionTier {FAMOUS, MINOR}

    public record RegionSpec(String name, RegionTier tier) {}

    public static final List<RegionSpec> REGIONS = List.of(
        new RegionSpec("경주", RegionTier.FAMOUS),
        new RegionSpec("부산", RegionTier.FAMOUS),
        new RegionSpec("제주", RegionTier.FAMOUS),
        new RegionSpec("서울", RegionTier.FAMOUS),
        new RegionSpec("강릉", RegionTier.FAMOUS),
        new RegionSpec("순천", RegionTier.MINOR),
        new RegionSpec("영주", RegionTier.MINOR),
        new RegionSpec("공주", RegionTier.MINOR),
        new RegionSpec("통영", RegionTier.MINOR),
        new RegionSpec("삼척", RegionTier.MINOR)
    );

    public record KeywordSetSpec(String id, List<KeywordType> keywords) {}

    /**
     * duration 카테고리(ONE_DAY 등)는 넣지 않는다 — 실제 일수(3일)와 모순되는데,
     * 그건 이미 알려진 별개 결함이라 환각 측정에 노이즈만 더한다.
     */
    public static final List<KeywordSetSpec> KEYWORD_SETS = List.of(
        new KeywordSetSpec("A", List.of(KeywordType.WALK, KeywordType.COUPLE,
            KeywordType.HEALING, KeywordType.SENSIBILITY, KeywordType.COST_EFFECTIVE)),
        new KeywordSetSpec("B", List.of(KeywordType.CAR, KeywordType.FAMILY,
            KeywordType.NATURE, KeywordType.NORMAL)),
        new KeywordSetSpec("C", List.of(KeywordType.WALK, KeywordType.FRIENDS,
            KeywordType.FOOD, KeywordType.ACTIVITY, KeywordType.PREMIUM))
    );

    public record RequestSpec(int requestId, RegionSpec region, KeywordSetSpec keywordSet) {}

    /** 30요청을 고정된 순서로 만든다. {@code requestId}는 1부터 시작한다. */
    public static List<RequestSpec> buildInputSet() {
        List<RequestSpec> specs = new ArrayList<>();
        int id = 1;
        for (RegionSpec region : REGIONS) {
            for (KeywordSetSpec keywordSet : KEYWORD_SETS) {
                specs.add(new RequestSpec(id++, region, keywordSet));
            }
        }
        return specs;
    }
}
