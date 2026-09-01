package backend.yourtrip.global.ai.candidate;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 장소 이름·주소를 비교 가능한 형태로 다듬는다 (ROADMAP 4-5). <b>순수 함수만 둔다.</b>
 *
 * <h2>왜 공용으로 끌어올렸나</h2>
 * 이 규칙은 원래 {@code KakaoLocalClient}의 private 메서드였다. 1-2에서 이름 게이트를 만들며
 * <b>실측 거짓 음성의 원인</b>을 정리한 결과물이라("동궁과 월지" vs "동궁과월지", "허균·허난설헌
 * 기념공원" vs "허균허난설헌기념공원") 그냥 문자열 정리가 아니라 <b>측정으로 얻은 규칙</b>이다.
 *
 * <p>4-5의 후보 dedupe도 같은 판단을 해야 하는데, 두 벌로 두면 한쪽만 고쳐지는 날이 온다 —
 * 그러면 검증에서는 같은 장소로 보던 것이 dedupe에서는 다른 장소가 되어, <b>같은 곳이 코스에 두 번
 * 들어가는 증상</b>으로 나타난다. 원인을 찾기 매우 어려운 종류의 불일치라 규칙을 하나로 둔다.
 */
public final class PlaceNameNormalizer {

    /**
     * 비교에서 무시할 문자들. 공백·중점·문장부호가 실측 거짓 음성의 원인이었다.
     *
     * <p>근거: {@code docs/tasks/ai-course-create/BASELINE-ARTIFACT-ANALYSIS.md} 판정 1·2
     */
    private static final Pattern NAME_NOISE = Pattern.compile("[\\s·・.,\\-_()\\[\\]/&|]+");

    private PlaceNameNormalizer() {
    }

    /**
     * 이름을 정규화한다.
     *
     * <p>{@link Locale#ROOT}를 쓰는 것은 기본 로케일에 따라 결과가 달라지지 않게 하기 위해서다
     * (터키어의 I 처리 등). 서버 로케일이 바뀌었다고 dedupe 결과가 달라지면 안 된다.
     *
     * @return {@code null}이면 빈 문자열
     */
    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return NAME_NOISE.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * 두 이름이 같은 곳을 가리키는지 판정한다. <b>한쪽이 다른 쪽을 포함하면 같다고 본다.</b>
     *
     * <p>포함 관계로 보는 이유는 소스마다 <b>수식이 붙고 떨어지기</b> 때문이다 — TourAPI는 정식
     * 명칭("경주 동궁과 월지")을, 네이버는 상호명("동궁과월지")을 준다. 완전 일치를 요구하면 같은
     * 장소를 놓치고, 그러면 병합되지 않은 채 두 후보가 나란히 남는다.
     *
     * <p><b>느슨한 규칙이라 단독으로 쓰지 않는다.</b> "왕릉"과 "경주 내물왕릉"도 포함 관계라 이
     * 판정만으로 합치면 전국의 향교가 하나가 된다. {@link CandidateMatcher}가 거리 조건과 AND로
     * 묶는 것이 그 때문이다.
     *
     * @return 비교할 이름이 없으면 {@code false} — <b>모르는 것을 같다고 하지 않는다</b>
     */
    public static boolean similar(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    /**
     * 한쪽 이름이 다른 쪽을 <b>진부분으로</b> 포함하는지 판정한다 (이슈 #106).
     * {@link #similar}에서 <b>완전 일치를 뺀 것</b>이다.
     *
     * <p><b>완전 일치를 빼는 것이 이 함수의 존재 이유다.</b> 부속 POI는 본체 이름을 그대로
     * 품으면서 수식이 붙어 <b>더 길다</b>("영주댐전망대" ⊊ "영주댐전망대주차장1"). 반면 이름이
     * 정확히 같은 두 후보는 <b>같은 상호의 다른 지점</b>일 수 있고, 그건 합치면 안 된다 —
     * 300m 안에 같은 프랜차이즈 두 곳이 있는 일은 실제로 있다.
     *
     * <p>그 경우를 {@link #similar}는 구별하지 못한다({@code a.contains(b)}가 완전 일치에도
     * 참이다). 그래서 <b>같은 규칙을 고쳐 쓰지 않고 나란히 둔다</b> — 소스 간 병합은 정반대를
     * 요구하기 때문이다. TourAPI {@code 경주 동궁과 월지}와 네이버 {@code 동궁과월지}는 정규화하면
     * 완전 일치하고, 그건 반드시 합쳐야 한다.
     *
     * <p><b>느슨한 규칙이라 단독으로 쓰지 않는다.</b> "왕릉" ⊊ "경주 내물왕릉"도 진포함이므로,
     * {@link CandidateMatcher#isSubordinate}가 거리 조건과 AND로 묶는다.
     *
     * @return 두 이름이 정규화 후 같으면 {@code false}. 비교할 이름이 없어도 {@code false}
     */
    public static boolean properlyContains(String left, String right) {
        String a = normalize(left);
        String b = normalize(right);
        if (a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return false;
        }
        return a.contains(b) || b.contains(a);
    }

    /**
     * 후보 이름이 기준 이름을 품은 채 <b>뒤에 말이 더 붙었는지</b> 판정한다 (이슈 #164).
     * 부속 POI가 본체 대신 매칭되는 것을 막는 데 쓴다.
     *
     * <h3>왜 접미에만 걸리는가 — 실측이 갈랐다</h3>
     * 8-6 환각률 측정의 {@code WRONG_MATCH}는 형태가 하나였다. <b>본체 이름을 통째로 품고 뒤에
     * 시설명이 붙는다</b>({@code 해운대시장}+{@code 공영주차장}, {@code 환선굴}+{@code 휴게실가든},
     * {@code 계룡산동학사}+{@code 자동차야영장}).
     *
     * <p>반면 <b>접두만 다른 것은 같은 장소다</b> — {@code 공주 고마나루} · {@code 경주 동궁과월지}
     * 처럼 지역명이나 정식 명칭이 앞에 붙었을 뿐이다. 그래서 {@link #properlyContains}에 방향
     * 제한만 걸면 후보 389건 중 79건이 걸려 정상 매칭까지 흔들리는데, <b>접미 잔여를 요구하면
     * 그 셋이 자동으로 빠진다.</b> 지역명 사전 같은 것을 따로 둘 필요가 없다.
     *
     * <p>구현이 {@code endsWith} 한 줄인 것은 그 때문이다 — "품고 있는데 그것으로 끝나지 않는다"가
     * 곧 "뒤에 뭔가 붙었다"이다.
     *
     * <h3>어휘 사전을 쓰지 않는 이유 — 덤프에서 손실이 나왔다</h3>
     * {@code 주차장}·{@code 야영장} 같은 부속 시설 어휘 목록으로 좁히는 안을 후보 덤프 위에서 함께
     * 재어 봤는데, <b>사전이 못 잡는 유형을 상대적으로 우대해</b> 더 나쁜 후보가 올라왔다
     * ({@code 이호테우해변입구교차로} → {@code CU 이호테우해변점}, {@code 서래마을카페거리} →
     * {@code 육갑식당 서래마을 직영점}). 접미 잔여가 있으면 전부 같게 다루면 그 비대칭이 없다.
     *
     * <h3>{@link CandidateMatcher#isSubordinate}와 다르다</h3>
     * 그쪽은 이 판정에 <b>거리 300m를 AND로</b> 묶는다. 여기서는 그럴 수 없다 —
     * <b>좌표를 얻으려고 검색하는 중이라 비교할 좌표가 아직 없다.</b> 그래서 거리 대신 접미 잔여
     * 조건으로 범위를 좁힌다. 두 함수가 같은 이름을 쓰지 않는 것은 그 차이를 감추지 않기 위해서다.
     *
     * <p><b>이 판정은 후보를 지우는 데 쓰지 않는다.</b> {@code KakaoLocalClient}는 이것을 순위
     * 강등에만 쓴다 — 부속밖에 없는 검색어가 실제로 있어서(공주 {@code 계룡산 동학사}는 게이트를
     * 통과한 셋이 전부 부속이었다) 탈락시키면 장소를 통째로 잃는다.
     *
     * @param candidateName 카카오가 준 상호명
     * @param baseName      AI가 준 장소명
     * @return 정규화 후 완전 일치거나, 포함 관계가 아니거나, 비교할 이름이 없으면 {@code false}
     */
    public static boolean isSubordinateName(String candidateName, String baseName) {
        String candidate = normalize(candidateName);
        String base = normalize(baseName);
        if (candidate.isEmpty() || base.isEmpty() || candidate.equals(base)) {
            return false;
        }
        if (!candidate.contains(base)) {
            return false;
        }
        return !candidate.endsWith(base);
    }
}
