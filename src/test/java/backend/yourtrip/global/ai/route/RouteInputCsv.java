package backend.yourtrip.global.ai.route;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code AiCourseRouteInputProbeTest}가 남긴 <b>최적화 직전 입력 CSV</b>를 읽는다.
 *
 * <p>파이프라인 30요청을 실제로 태워 캡처한 산출물이라, 좌표·슬롯 종류·시작 시각·이동수단이
 * 모두 실려 있다. 이 클래스를 읽는 하네스는 네트워크를 쓰지 않고 <b>같은 CSV 면 언제나 같은
 * 값</b>을 얻는다.
 *
 * <p><b>왜 하네스에서 뗐는가.</b> 원래 {@code RouteOptimizationEffectTest} 안에 private 으로
 * 있었는데, 3-8 계수 스윕이 <b>같은 CSV 를 같은 방식으로 읽어야</b> 해서 공유가 필요해졌다.
 * 두 하네스가 같은 패키지라 추출 비용이 거의 없다 — 3-7 하네스가 CSV 파서 복제를 정당화하며
 * 들었던 이유("private 이고 <b>다른 패키지</b>다")가 여기서는 성립하지 않는다.
 *
 * <p>반대로 두 하네스가 각자 파서를 들면, 같은 입력을 읽는 두 측정이 조용히 다른 것을 읽게
 * 되고 그 사실이 결과 어디에도 드러나지 않는다.
 */
final class RouteInputCsv {

    private RouteInputCsv() {
    }

    /**
     * 캡처된 {@code RouteRequest} 한 행. 좌표가 {@code Double}이 아니라 primitive 인 것은
     * {@link RoutePlace}와 같은 이유다 — 좌표 없는 장소는 최적화기에 들어갈 수 없으므로
     * 이 산출물에 실릴 수도 없다.
     */
    record PlaceRow(
        int requestId, String location, String regionTier, String keywordSet,
        int day, int placeIndex, String placeName,
        double latitude, double longitude,
        SlotType slotType, String source, LocalTime dayStartTime, TravelMode travelMode
    ) {}

    /**
     * 필요한 열이 전부 있는지 먼저 확인한다.
     *
     * <p><b>{@code slotType}·{@code dayStartTime}·{@code travelMode}가 없으면 읽지 않는다.</b>
     * 셋 다 최적화 입력이라 없는 것을 기본값으로 채우면 운영에서 나온 순서를 재현하지 못하고,
     * 그 사실이 결과에 드러나지도 않는다. 열이 모자란 산출물은 조용히 절반만 재는 것보다
     * 읽기를 거부하는 편이 낫다.
     */
    static List<PlaceRow> readPlaces(Path source) throws IOException {
        List<List<String>> csv = readCsv(source);
        Map<String, Integer> col = new HashMap<>();
        List<String> header = csv.get(0);
        for (int i = 0; i < header.size(); i++) {
            col.put(header.get(i), i);
        }
        for (String column : List.of("requestId", "location", "regionTier", "keywordSet",
            "day", "placeIndex", "placeName", "slotType", "latitude", "longitude", "source",
            "dayStartTime", "travelMode")) {
            assertThat(col)
                .as("입력 CSV에 %s 열이 있어야 한다 — AiCourseRouteInputProbeTest 의 산출물인가?",
                    column)
                .containsKey(column);
        }

        List<PlaceRow> rows = new ArrayList<>(csv.size() - 1);
        for (int i = 1; i < csv.size(); i++) {
            List<String> row = csv.get(i);
            rows.add(new PlaceRow(
                Integer.parseInt(row.get(col.get("requestId"))),
                row.get(col.get("location")),
                row.get(col.get("regionTier")),
                row.get(col.get("keywordSet")),
                Integer.parseInt(row.get(col.get("day"))),
                Integer.parseInt(row.get(col.get("placeIndex"))),
                row.get(col.get("placeName")),
                Double.parseDouble(row.get(col.get("latitude"))),
                Double.parseDouble(row.get(col.get("longitude"))),
                SlotType.valueOf(row.get(col.get("slotType"))),
                row.get(col.get("source")),
                LocalTime.parse(row.get(col.get("dayStartTime"))),
                TravelMode.valueOf(row.get(col.get("travelMode")))));
        }
        return rows;
    }

    /**
     * 큰따옴표를 처리하는 최소 CSV 파서. 장소명에 쉼표가 들어갈 수 있어 단순
     * {@code split(",")}으로는 열이 밀린다. BOM 은 걷어낸다.
     *
     * <p><b>{@code AiHallucinationBaselineTest}에도 같은 것이 있는데 합치지 않은 이유.</b>
     * 그쪽은 private 이고 다른 패키지다. 공유하려면 public 으로 올리거나 테스트 공용 모듈을 새로
     * 만들어야 하는데, 스무 줄짜리 파서를 위해 하네스의 표면을 넓히는 것보다 각자 갖는 편이
     * 낫다고 봤다 — 두 파서가 어긋나더라도 서로의 결과를 오염시키지 않는다.
     */
    static List<List<String>> readCsv(Path path) throws IOException {
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
                continue;
            }
            switch (c) {
                case '"' -> quoted = true;
                case ',' -> {
                    current.add(field.toString());
                    field.setLength(0);
                }
                case '\r' -> { }
                case '\n' -> {
                    current.add(field.toString());
                    field.setLength(0);
                    rows.add(current);
                    current = new ArrayList<>();
                }
                default -> field.append(c);
            }
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            rows.add(current);
        }
        return rows;
    }
}
