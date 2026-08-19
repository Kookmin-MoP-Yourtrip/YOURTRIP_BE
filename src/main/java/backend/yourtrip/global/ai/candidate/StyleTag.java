package backend.yourtrip.global.ai.candidate;

import java.util.Optional;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 스타일 속성의 <b>닫힌 태그 집합</b>. 후보 공급과 지식 신호 층이 공유하는 유일한 어휘다.
 *
 * <p>자유 텍스트로 두지 않는 이유는 설계가 명시한 그대로다 — 열어두면 "요약 단계 자체가 새로운
 * 환각 지점이 되고 테스트도 불가능해진다".
 *
 * <p><b>어휘를 합집합으로 둔 이유.</b> 설계는 4-3(키워드→traits 사전)과 4-9(`cat3`→스타일 태그)가
 * <i>같은 traits 어휘</i>를 쓴다고 못박았는데, 두 표를 실제로 대조해 보니 어긋나 있었다 — 4층 traits
 * 26개에 4-9가 쓰는 {@code 자연}·{@code 역사}·{@code 문화}·{@code 액티비티}·{@code 실내}가 없다.
 * 어휘를 둘로 나누면 9단계 4층을 켤 때 매핑 테이블을 두 벌 유지해야 하므로, <b>합집합 31개</b>를
 * 한 enum으로 두어 설계의 그 문장이 실제로 성립하게 만든다.
 *
 * <h2>{@code searchTerm} — 태그 이름과 검색어를 나누는 이유</h2>
 * 태그 이름은 속성을 가리키는 라벨이고, 검색어는 네이버 지역검색에 실제로 실리는 문자열이다. 둘은
 * 자주 다르다 — {@code QUIET}의 라벨은 "조용함"이지만 쿼리에는 {@code "조용한"}으로 들어가야
 * 자연스럽고, {@code PARKING_AVAILABLE}은 "주차가능"이 아니라 {@code "주차"}가 낫다(4-2 실측에서
 * {@code "공주 주차 맛집"}이 신규 후보 4/4를 줬다).
 *
 * <p><b>{@code searchTerm}이 없는 태그가 있다.</b> 감점 전용 태그({@code 주차난}·{@code 웨이팅})와
 * 검색어로 무의미한 태그({@code 보통}·{@code 브레이크타임})는 modifier 쿼리에 실릴 일이 없다.
 * 비워 두면 {@link StyleModifierDictionary}가 구조적으로 그것을 고르지 못한다.
 *
 * <p><b>검색어 표기 대부분은 아직 실측되지 않았다.</b> 4-2가 확인한 것은
 * {@code 루프탑}·{@code 조용한}·{@code 야경}·{@code 주차} 넷뿐이다. 나머지가 실제로 유의미한 결과를
 * 주는지는 5-9 후보 공급 실측에서 확인하고, 그때 표기를 조정한다.
 */
@Getter
@RequiredArgsConstructor
public enum StyleTag {

    // ── 뷰·분위기 ────────────────────────────────────────────────────────────
    NIGHT_VIEW("야경", "야경"),
    GREAT_VIEW("뷰맛집", "뷰맛집"),
    ROOFTOP("루프탑", "루프탑"),
    PANORAMIC_WINDOW("통창", "통창"),
    HANOK("한옥", "한옥"),
    RETRO("레트로", "레트로"),
    SPACIOUS("넓음", "넓은"),
    COZY("아늑함", "아늑한"),
    QUIET("조용함", "조용한"),
    LIVELY("시끌벅적", "시끌벅적한"),

    // ── 접근성 ───────────────────────────────────────────────────────────────
    NEAR_STATION("역세권", "역세권"),
    WALKABLE("도보접근", "도보"),
    /** 4-2 실측으로 검색어 표기가 검증된 태그. */
    PARKING_AVAILABLE("주차가능", "주차"),
    /** 감점 전용. 사용자가 피하고 싶은 속성이라 쿼리로 부르지 않는다. */
    PARKING_DIFFICULT("주차난", null),

    // ── 혼잡 ─────────────────────────────────────────────────────────────────
    /** 감점 전용. */
    LONG_WAIT("웨이팅", null),
    /** 감점 전용. */
    RESERVATION_REQUIRED("예약필수", null),
    UNCROWDED("한적함", "한적한"),

    // ── 동반 ─────────────────────────────────────────────────────────────────
    PET_FRIENDLY("반려동물동반", "애견동반"),
    KID_FRIENDLY("아이동반", "아이동반"),
    GROUP_FRIENDLY("단체가능", "단체"),

    // ── 가격 ─────────────────────────────────────────────────────────────────
    CHEAP("저렴", "가성비"),
    /** 검색어로 무의미하다 — "보통 카페"를 찾는 사람은 없다. */
    MODERATE("보통", null),
    EXPENSIVE("고가", "고급"),

    // ── 시간 ─────────────────────────────────────────────────────────────────
    MORNING_OPEN("아침영업", "아침"),
    LATE_NIGHT("야간영업", "야간"),
    /** 감점 전용. */
    BREAK_TIME("브레이크타임", null),

    // ── 4-9(`cat3` 매핑)가 요구하는 확장 어휘 ────────────────────────────────
    NATURE("자연", "자연"),
    HISTORY("역사", "역사"),
    CULTURE("문화", "문화"),
    ACTIVITY("액티비티", "체험"),
    INDOOR("실내", "실내");

    /** 사람이 읽는 태그 이름. 설계 문서의 traits 표기와 1:1로 맞춘다. */
    private final String label;

    /** 지역검색 쿼리에 실리는 문자열. 없으면 modifier로 쓰이지 않는다. */
    private final String searchTerm;

    /** modifier 쿼리에 쓸 수 있는 태그인가. */
    public boolean isSearchable() {
        return searchTerm != null;
    }

    public Optional<String> searchTerm() {
        return Optional.ofNullable(searchTerm);
    }
}
