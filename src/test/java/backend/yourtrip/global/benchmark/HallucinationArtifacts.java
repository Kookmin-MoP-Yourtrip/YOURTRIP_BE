package backend.yourtrip.global.benchmark;

import static backend.yourtrip.global.benchmark.HallucinationScoring.ALL_BANDS;
import static backend.yourtrip.global.benchmark.HallucinationScoring.nullToEmpty;

import backend.yourtrip.global.benchmark.HallucinationScoring.PlaceRow;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 환각률 측정 산출물(CSV)의 읽기·쓰기. 채점기({@link HallucinationScoring})와 함께
 * 단일 호출 하네스와 파이프라인 하네스가 공유한다.
 *
 * <p><b>층화 추출 절차가 여기 있는 것이 요점이다.</b> 시드와 표본 수가 하네스마다 흩어지면
 * before/after의 표본이 서로 다른 방식으로 뽑히고, 그러면 두 지어냄률을 나란히 놓을 수 없다.
 * 지어냄률은 층별 비율을 전체 비중으로 가중해 얻는 값이라 <b>추출 절차 자체가 지표 정의의
 * 일부</b>다.
 */
public final class HallucinationArtifacts {

    /** 하네스의 작업 디렉터리. {@code .gitignore} 대상이라 승격 대상은 docs 쪽으로 복사한다. */
    public static final Path RESULTS_DIR = Path.of("results");

    /** 수동 검증 워크시트에서 점수 구간별로 뽑을 표본 수. */
    public static final int SAMPLES_PER_BAND = 10;

    /** 층화 추출을 재현 가능하게 만드는 고정 시드 — 재분석 시 같은 표본이 나와야 한다. */
    public static final long SAMPLING_SEED = 42L;

    /** CSV·콘솔에 남기는 예외 메시지의 최대 길이. */
    public static final int MAX_ERROR_LENGTH = 300;

    /**
     * 장소별 CSV의 열 이름. <b>열은 뒤에만 붙인다.</b> 읽는 쪽(재채점 모드)이 이름으로 찾으므로
     * 순수 추가는 구 스키마 CSV 와 호환되지만, 중간에 끼우면 사람이 옛 산출물과 눈으로 대조할 때
     * 열이 밀려 보인다.
     *
     * <p>파이프라인 하네스는 이 헤더 <b>뒤에</b> 자기 열(source·modifier·좌표)을 이어 붙인다.
     * 그래야 앞 17열이 구조적으로 동일해 같은 CSV 를 {@code BASELINE_RESCORE_FROM} 으로
     * 되먹일 수 있다.
     */
    public static final String PLACE_CSV_HEADER =
        "requestId,location,regionTier,keywordSet,day,placeIndex,aiPlaceName,"
            + "bestScore,scoreBand,matchedPlaceName,matchedCategory,"
            + "matchedAddress,matchedPlaceUrl,rejectedCandidateName,"
            + "matchedX,matchedY,matchedCategoryGroupCode";

    private HallucinationArtifacts() {
    }

    /** {@link #PLACE_CSV_HEADER}에 대응하는 한 행. 개행은 붙이지 않는다. */
    public static String placeCsvRow(PlaceRow r) {
        return new StringBuilder()
            .append(r.requestId()).append(',')
            .append(csv(r.location())).append(',')
            .append(r.tier()).append(',')
            .append(csv(r.keywordSetId())).append(',')
            .append(r.day()).append(',')
            .append(r.placeIndex()).append(',')
            .append(csv(r.aiPlaceName())).append(',')
            .append(r.bestScore()).append(',')
            .append(r.scoreBand()).append(',')
            .append(csv(r.matchedPlaceName())).append(',')
            .append(csv(r.matchedCategory())).append(',')
            .append(csv(r.matchedAddress())).append(',')
            .append(csv(r.matchedPlaceUrl())).append(',')
            .append(csv(r.rejectedCandidateName())).append(',')
            .append(csv(r.matchedX())).append(',')
            .append(csv(r.matchedY())).append(',')
            .append(csv(r.matchedCategoryGroupCode()))
            .toString();
    }

    public static void writePlaceCsv(Path outFile, List<PlaceRow> rows) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(PLACE_CSV_HEADER).append('\n');
        for (PlaceRow r : rows) {
            sb.append(placeCsvRow(r)).append('\n');
        }
        writeUtf8Bom(outFile, sb.toString());
    }

    /**
     * 점수 구간별 층화 추출 워크시트. 사람이 matchedPlaceUrl 을 열어 verdict 를 채운다.
     *
     * <p>층화(무작위가 아니라 구간별 균등)로 뽑는 이유: "몇 점부터 실제로 신뢰할 수 있는가"를
     * 데이터로 확인해야 임계값이 적절한지까지 판정할 수 있다. 대신 전체 환각률을 추정할 때는
     * 구간별 비율을 전체 비중으로 가중해야 한다.
     *
     * <p><b>{@link Random} 인스턴스 하나를 층 루프 전체에서 공유한다</b> — 층 순회 순서를 바꾸면
     * 같은 시드라도 표본이 달라진다. 순서는 {@link HallucinationScoring#ALL_BANDS}가 정한다.
     */
    public static void writeManualVerificationCsv(Path outFile, List<PlaceRow> rows)
        throws IOException {

        Map<String, List<PlaceRow>> byBand = new LinkedHashMap<>();
        for (String band : ALL_BANDS) {
            byBand.put(band, new ArrayList<>());
        }
        for (PlaceRow r : rows) {
            byBand.get(r.scoreBand()).add(r);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# verdict 를 채워주세요: CORRECT | FABRICATED | WRONG_MATCH | UNVERIFIABLE\n")
            .append("#   CORRECT       AI 원안이 실존하고 카카오 매칭도 그 장소가 맞음\n")
            .append("#   FABRICATED    AI 원안이 그 지역에 실존하지 않음 — 매칭 성공 여부는 보지 않는다\n")
            .append("#                 (매칭까지 통과한 '세탁'인지는 scoreBand 로 사후 분해된다)\n")
            .append("#   WRONG_MATCH   AI 원안은 실존하는데 카카오가 엉뚱한 것을 매칭\n")
            .append("#   UNVERIFIABLE  판단 불가\n")
            .append("scoreBand,bestScore,requestId,day,location,aiPlaceName,matchedPlaceName,")
            .append("matchedAddress,matchedPlaceUrl,verdict,note\n");

        Random random = new Random(SAMPLING_SEED);
        for (Map.Entry<String, List<PlaceRow>> entry : byBand.entrySet()) {
            List<PlaceRow> pool = new ArrayList<>(entry.getValue());
            Collections.shuffle(pool, random);
            for (PlaceRow r : pool.subList(0, Math.min(SAMPLES_PER_BAND, pool.size()))) {
                sb.append(r.scoreBand()).append(',')
                    .append(r.bestScore()).append(',')
                    .append(r.requestId()).append(',')   // 같은 장소명이 여러 번 나올 때 출처 구분용
                    .append(r.day()).append(',')
                    .append(csv(r.location())).append(',')
                    .append(csv(r.aiPlaceName())).append(',')
                    .append(csv(r.matchedPlaceName())).append(',')
                    .append(csv(r.matchedAddress())).append(',')
                    .append(csv(r.matchedPlaceUrl())).append(',')
                    .append(',')   // verdict — 사람이 채운다
                    .append('\n'); // note
            }
        }
        writeUtf8Bom(outFile, sb.toString());
    }

    /**
     * 큰따옴표 이스케이프를 처리하는 최소 CSV 파서. {@code matchedCategory}에 쉼표가 들어
     * 있어("여행 > 관광,명소") 단순 {@code split(",")}으로는 열이 밀린다. BOM은 걷어낸다.
     */
    public static List<List<String>> readCsv(Path path) throws IOException {
        String content = Files.readString(path, StandardCharsets.UTF_8);
        if (content.startsWith("﻿")) {
            content = content.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char c = content.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                current.add(field.toString());
                field.setLength(0);
                if (current.size() > 1 || !current.get(0).isBlank()) {
                    rows.add(current);
                }
                current = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }

    public static void writeUtf8Bom(Path path, String content) throws IOException {
        // 출력 디렉터리를 먼저 만든다. results/ 는 .gitignore 대상이라 clone 직후나 정리 후에는
        // 존재하지 않는데, 없으면 여기서 NoSuchFileException 이 나고 그때는 이미 LLM 호출을
        // 다 끝낸 뒤라 측정 비용만 날린다. raw-* 쪽은 이미 createDirectories 를 부르지만
        // CSV 세 종은 이 메서드가 유일한 통로라 여기 한 곳이면 전부 덮인다.
        Files.createDirectories(path.getParent());

        // Excel(Windows)이 UTF-8 CSV의 한글을 깨뜨리지 않도록 BOM을 붙인다 — 수동 검증 워크시트를
        // 사람이 스프레드시트로 열기 때문이다.
        Files.writeString(path, "﻿" + content, StandardCharsets.UTF_8);
    }

    public static String csv(String value) {
        String s = nullToEmpty(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    /** CSV·콘솔에 넣기 전에 개행을 없애고 길이를 제한한다 — 예외 메시지가 여러 줄이라 행이 깨진다. */
    public static String oneLine(String s) {
        String flat = nullToEmpty(s).replaceAll("\\s*[\\r\\n]+\\s*", " ").trim();
        return flat.length() <= MAX_ERROR_LENGTH ? flat : flat.substring(0, MAX_ERROR_LENGTH) + "…";
    }
}
