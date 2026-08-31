package backend.yourtrip.global.benchmark;

import static backend.yourtrip.global.benchmark.HallucinationScoring.ALL_BANDS;
import static backend.yourtrip.global.benchmark.HallucinationScoring.BAND_NAME_MISMATCH;
import static backend.yourtrip.global.benchmark.HallucinationScoring.SUSPECT_BANDS;

import backend.yourtrip.global.benchmark.BaselineInputSet.RegionTier;
import backend.yourtrip.global.benchmark.HallucinationScoring.PlaceRow;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 장소 표본에서 <b>판정자 없이 코드가 계산할 수 있는 지표</b>를 콘솔에 찍는다.
 *
 * <p>여기 있는 것과 없는 것의 경계가 중요하다. 자동 프록시·이름 불일치율·장소 미확보율은
 * 결과 구간만으로 나오므로 실행 즉시 얻어지지만, <b>1차 지표인 지어냄률과 세탁 통과율은
 * 수동 검증 워크시트를 채운 뒤에야 산출된다</b> — 그 둘은 "이 이름이 실존하는가"라는 질문의
 * 답을 필요로 하고, 그건 카카오 검색 결과가 아니라 사람(또는 근거를 남긴 AI 세션)이 답한다.
 *
 * <p>요청 결말·JSON 실패율·절단 상세는 <b>단일 호출 하네스 고유</b>라 여기 없다. 파이프라인은
 * LLM 을 요청당 네 번 부르고 개별 실패를 degrade 로 흡수하므로 "요청 하나 = 응답 하나"라는
 * 전제가 성립하지 않는다.
 */
public final class HallucinationReport {

    private HallucinationReport() {
    }

    /** 두 하네스가 공유하는 장소 지표. 출력 문구가 같아야 산출물을 나란히 읽을 수 있다. */
    public static void printPlaceMetrics(List<PlaceRow> rows) {
        System.out.printf("%n=== 점수 구간 분포 (장소 표본 %,d개) ===%n", rows.size());

        Map<String, Integer> bandCounts = new LinkedHashMap<>();
        for (String band : ALL_BANDS) {
            bandCounts.put(band, 0);
        }
        for (PlaceRow r : rows) {
            bandCounts.merge(r.scoreBand(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> e : bandCounts.entrySet()) {
            System.out.printf("  %-12s %4d건 (%5.1f%%)%n",
                e.getKey(), e.getValue(), pct(e.getValue(), rows.size()));
        }

        long suspect = rows.stream().filter(r -> SUSPECT_BANDS.contains(r.scoreBand())).count();
        long nameMismatch = rows.stream()
            .filter(r -> BAND_NAME_MISMATCH.equals(r.scoreBand())).count();
        System.out.printf("%n=== 자동 지표 (판정자 없이 코드가 계산한다) ===%n");
        System.out.printf("  자동 프록시    NO_RESULT / 전체     = %d / %d = %.1f%%%n",
            suspect, rows.size(), pct(suspect, rows.size()));
        System.out.printf("  이름 불일치율  NAME_MISMATCH / 전체 = %d / %d = %.1f%%%n",
            nameMismatch, rows.size(), pct(nameMismatch, rows.size()));
        System.out.printf("  장소 미확보율  둘의 합              = %d / %d = %.1f%%"
                + "   ← 운영 ai.grounding.match 와 같은 축%n",
            suspect + nameMismatch, rows.size(), pct(suspect + nameMismatch, rows.size()));
        System.out.printf("  ※ KAKAO_ERROR 는 분자에서 빠지고 분모엔 남는다 — 장애 시 값이 희석된다.%n");
        System.out.printf("     지어낸 이름이 게이트를 통과해 실린 '세탁'은 수동 검증으로만 잡는다.%n");

        System.out.printf("%n=== 지역 tier별 자동 프록시 (NO_RESULT 기준) ===%n");
        for (RegionTier tier : RegionTier.values()) {
            List<PlaceRow> tierRows = rows.stream().filter(r -> r.tier() == tier).toList();
            long tierSuspect = tierRows.stream()
                .filter(r -> SUSPECT_BANDS.contains(r.scoreBand())).count();
            System.out.printf("  %-7s %4d / %4d = %.1f%%%n",
                tier, tierSuspect, tierRows.size(), pct(tierSuspect, tierRows.size()));
        }
    }

    /** 수동 검증 후에 사람이 손으로 계산하는 지표의 정의를 콘솔에 남긴다. */
    public static void printManualMetricFormulas() {
        System.out.printf("%n  수동 검증 후 지표 산출:%n");
        System.out.printf("    지어냄률    = Σ_구간 (구간별 FABRICATED 비율 × 구간별 전체 비중)"
            + "   ← 1차 지표%n");
        System.out.printf("    세탁 통과율 = 위 식을 Found 구간(S0~S8_10)에만 적용"
            + "                     ← 지어냈는데 통과한 것%n");
    }

    public static double pct(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : numerator * 100.0 / denominator;
    }
}
