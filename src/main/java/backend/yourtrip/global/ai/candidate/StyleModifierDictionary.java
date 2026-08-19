package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사용자 키워드 → 스타일 modifier 사전 (ROADMAP 4-3). <b>순수 함수만 둔다.</b>
 *
 * <h2>왜 필요한가</h2>
 * 후보 공급 쿼리가 {@code "{area} {searchHint}"} 뿐이면 <b>풀 자체가 스타일을 모른다.</b> 황리단길
 * 카페 300개 중 스타일 무관 쿼리로 5개가 들어왔는데 그 안에 루프탑·야경 카페가 하나도 없으면,
 * {@code 연인·감성} 요청에서 Curator는 평범한 5개 중 "그나마 나은 3개"를 고르는 수밖에 없다.
 * <b>풀이 천장이고 선별은 천장 아래에서만 움직인다</b> — 그래서 스타일을 선별이 아니라 <i>검색</i>
 * 단계에 넣는다.
 *
 * <h2>왜 LLM을 쓰지 않는가</h2>
 * 결정론으로 되는 일에 LLM을 먼저 쓰지 않는다(설계 원칙 1). 사전은 순수 함수라 완전히 테스트
 * 가능하고, LLM 호출이 한 번도 늘지 않는다. 9단계 4층이 V1에서 빠져도 이 사전은 살아 있다.
 *
 * <h2>이 사전이 만들지 <b>않는</b> 것</h2>
 * 최종 쿼리 문자열은 여기서 만들지 않는다. 이 클래스는 태그만 고르고, {@code "{area} {searchTerm}
 * {searchHint}"} 조립은 5-8이 한다 — {@code area}와 {@code SlotType}을 아는 것이 그쪽이기 때문이다.
 */
public final class StyleModifierDictionary {

    /**
     * 쿼리 변주 예산. 슬롯당 기본 1회 + 스타일 1~2회로 코스당 네이버 호출이 18~30회가 된다
     * (일 25,000건 한도에서 하루 830~1,400코스). <b>이 상수가 곧 쿼터 예산이다.</b>
     */
    public static final int MAX_MODIFIERS = 2;

    /** 키워드 하나가 끌어오는 태그. 순서가 곧 그 키워드 안에서의 우선순위다. */
    private record Preference(List<StyleTag> preferred, Set<StyleTag> avoided) {

        static Preference of(List<StyleTag> preferred, StyleTag... avoided) {
            return new Preference(preferred,
                avoided.length == 0 ? EnumSet.noneOf(StyleTag.class) : EnumSet.of(avoided[0], avoided));
        }
    }

    /**
     * 설계 문서의 "키워드 → traits" 표를 그대로 옮긴 것 + mood 3개 확장.
     *
     * <p><b>매핑을 비워 둔 키워드가 7개 있고, 비운 것도 결정이다.</b>
     * <ul>
     *   <li>{@code duration} 4종 — 스타일 축이 아니다. 처리 방침은 6-5가 따로 정한다</li>
     *   <li>{@code NORMAL}(평균예산) — {@code "경주 보통 카페"} 는 검색어로 무의미하다</li>
     *   <li>{@code FOOD}(맛집탐방)·{@code SHOPPING}(쇼핑) — 이미 <b>슬롯 구성</b>으로 표현된다.
     *       Planner가 MEAL·SHOPPING 슬롯을 늘리는 축이지 수식어가 아니며, {@code "맛집 카페"} 같은
     *       쿼리는 쿼터만 쓰고 결과를 흐린다</li>
     * </ul>
     */
    private static final Map<KeywordType, Preference> PREFERENCES = buildPreferences();

    private StyleModifierDictionary() {
    }

    /**
     * 사용자 키워드에서 modifier로 쓸 태그를 최대 {@link #MAX_MODIFIERS}개 고른다.
     *
     * <p><b>선정 규칙 — 설계는 "가점 태그 상위 1~2개"라고만 적어 두었다.</b> 사용자가
     * {@code COUPLE + HEALING}을 함께 고르면 후보가 8개가 되므로 "상위"의 정의가 필요하다.
     *
     * <ol>
     *   <li><b>사용자가 피하고 싶다고 표시한 태그를 먼저 걷어낸다.</b> 어떤 키워드의 감점 태그가
     *       다른 키워드의 가점 태그일 수 있는데({@code FRIENDS}는 {@code 시끌벅적}을 원하고
     *       {@code COUPLE}은 피한다), 이때는 <b>피하는 쪽을 존중한다</b> — 원하지 않는 곳으로
     *       데려가는 실수가 그저 그런 곳으로 데려가는 실수보다 나쁘다</li>
     *   <li><b>여러 키워드에 중복 등장하는 태그를 우선한다.</b> 중복은 취향이 겹치는 지점이라
     *       신호가 강하다({@code COUPLE}과 {@code HEALING} 둘 다 {@code 뷰맛집}을 가리킨다)</li>
     *   <li>동점이면 <b>설계 표 안에서의 순위</b>(그 태그가 등장한 가장 앞 위치)로 깬다.
     *       <b>enum 선언 순서를 쓰면 안 된다</b> — {@link StyleTag}는 범주별(뷰·접근성·가격…)로
     *       묶여 있어 어느 키워드의 선호 순서와도 일치하지 않는다. 실제로 {@code COUPLE}은 설계
     *       표에서 야경 → 루프탑인데 선언 순서로는 야경 → 뷰맛집이 되어 표를 배신한다</li>
     *   <li>그래도 같으면 enum 선언 순서로 최종 확정한다 — 같은 입력에 같은 쿼리가 나가야 한다</li>
     * </ol>
     *
     * @return 검색 가능한 태그 0~2개. 빈 목록이면 기본 쿼리만 쓴다(fail-open)
     */
    public static List<StyleTag> modifiersFor(List<KeywordType> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return List.of();
        }

        Set<StyleTag> avoided = EnumSet.noneOf(StyleTag.class);
        Map<StyleTag, Integer> hits = new EnumMap<>(StyleTag.class);
        Map<StyleTag, Integer> bestRank = new EnumMap<>(StyleTag.class);
        for (KeywordType keyword : keywords) {
            Preference preference = PREFERENCES.get(keyword);
            if (preference == null) {
                continue;
            }
            avoided.addAll(preference.avoided());
            List<StyleTag> preferred = preference.preferred();
            for (int rank = 0; rank < preferred.size(); rank++) {
                StyleTag tag = preferred.get(rank);
                hits.merge(tag, 1, Integer::sum);
                bestRank.merge(tag, rank, Math::min);
            }
        }

        List<StyleTag> selected = new ArrayList<>(hits.keySet());
        selected.removeAll(avoided);
        selected.removeIf(tag -> !tag.isSearchable());
        selected.sort(Comparator
            .comparingInt((StyleTag tag) -> hits.get(tag)).reversed()
            .thenComparingInt(bestRank::get)
            .thenComparing(Comparator.naturalOrder()));

        return List.copyOf(selected.subList(0, Math.min(selected.size(), MAX_MODIFIERS)));
    }

    private static Map<KeywordType, Preference> buildPreferences() {
        Map<KeywordType, Preference> map = new EnumMap<>(KeywordType.class);

        // 동행유형
        map.put(KeywordType.COUPLE, Preference.of(
            List.of(StyleTag.NIGHT_VIEW, StyleTag.ROOFTOP, StyleTag.PANORAMIC_WINDOW,
                StyleTag.QUIET, StyleTag.GREAT_VIEW),
            StyleTag.LIVELY));
        map.put(KeywordType.FAMILY, Preference.of(
            List.of(StyleTag.PARKING_AVAILABLE, StyleTag.KID_FRIENDLY, StyleTag.SPACIOUS,
                StyleTag.GROUP_FRIENDLY),
            StyleTag.LONG_WAIT, StyleTag.PARKING_DIFFICULT));
        map.put(KeywordType.FRIENDS, Preference.of(
            List.of(StyleTag.LIVELY, StyleTag.GROUP_FRIENDLY, StyleTag.LATE_NIGHT)));
        // 소품샵은 설계 표에 없던 확장이다 — 아늑함(COZY)의 검색어가 전부 죽어 그 자리를 메운다.
        // 조용함 뒤에 두어 원래 표의 1순위(아늑함 → 검색 불가)가 빠진 자리를 이어받게 했다.
        map.put(KeywordType.SOLO, Preference.of(
            List.of(StyleTag.COZY, StyleTag.QUIET, StyleTag.LIFESTYLE_SHOP, StyleTag.UNCROWDED),
            StyleTag.GROUP_FRIENDLY));

        // 이동수단
        map.put(KeywordType.WALK, Preference.of(
            List.of(StyleTag.NEAR_STATION, StyleTag.WALKABLE)));
        map.put(KeywordType.CAR, Preference.of(
            List.of(StyleTag.PARKING_AVAILABLE),
            StyleTag.PARKING_DIFFICULT));

        // 여행분위기
        map.put(KeywordType.HEALING, Preference.of(
            List.of(StyleTag.QUIET, StyleTag.UNCROWDED, StyleTag.GREAT_VIEW),
            StyleTag.LONG_WAIT, StyleTag.LIVELY));
        map.put(KeywordType.SENSIBILITY, Preference.of(
            List.of(StyleTag.PANORAMIC_WINDOW, StyleTag.HANOK, StyleTag.RETRO, StyleTag.ROOFTOP)));
        // 아래 셋은 설계 표에 없던 확장이다 — 4-9의 `cat3` 어휘를 그대로 끌어와 채웠다.
        map.put(KeywordType.NATURE, Preference.of(
            List.of(StyleTag.NATURE, StyleTag.UNCROWDED)));
        map.put(KeywordType.CULTURE, Preference.of(
            List.of(StyleTag.CULTURE, StyleTag.INDOOR)));
        map.put(KeywordType.ACTIVITY, Preference.of(
            List.of(StyleTag.ACTIVITY)));

        // 예산
        map.put(KeywordType.COST_EFFECTIVE, Preference.of(
            List.of(StyleTag.CHEAP),
            StyleTag.EXPENSIVE));
        map.put(KeywordType.PREMIUM, Preference.of(
            List.of(StyleTag.EXPENSIVE, StyleTag.GREAT_VIEW),
            StyleTag.CHEAP));

        return map;
    }
}
