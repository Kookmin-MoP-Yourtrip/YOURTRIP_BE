package backend.yourtrip.domain.uploadcourse.entity.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonFormat.Shape;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@JsonFormat(shape = Shape.OBJECT) //enum을 객체로 직렬화
public enum KeywordType {

    // 이동수단
    WALK("travelMode", "뚜벅이"),
    CAR("travelMode", "자차"),

    // 동행유형
    SOLO("companionType", "혼자"),
    COUPLE("companionType", "연인"),
    FRIENDS("companionType", "친구"),
    FAMILY("companionType", "가족"),

    // 여행분위기
    HEALING("mood", "힐링"),
    ACTIVITY("mood", "액티비티"),
    FOOD("mood", "맛집탐방"),
    SENSIBILITY("mood", "감성"),
    CULTURE("mood", "문화/전시"),
    NATURE("mood", "자연"),
    SHOPPING("mood", "쇼핑"),

    // 여행기간
    ONE_DAY("duration", "하루"),
    TWO_DAYS("duration", "1박 2일"),
    WEEKEND("duration", "주말"),
    LONG("duration", "장기"),

    // 예산
    COST_EFFECTIVE("budget", "가성비"),
    NORMAL("budget", "평균예산"),
    PREMIUM("budget", "프리미엄");

    private final String category;
    @Getter
    private final String label;

    public static List<KeywordType> findByCategory(String category) {
        return Arrays.stream(values())
            .filter(keyword -> keyword.category.equals(category))
            .toList();
    }

    public String getCode() {
        return this.name();
    }

    private static final List<String> KEYWORD_CATEGORIES = List.of(
        "travelMode",
        "companionType",
        "mood",
        "duration",
        "budget"
    );

    public static String buildKeywordsJson(List<KeywordType> selectedKeywords) {
        ObjectMapper objectMapper = new ObjectMapper();

        // 선택된 키워드를 빠르게 조회하기 위한 Set 생성.
        // null 가드가 필요한 이유: AICourseCreateRequest에 @NotEmpty를 걸었지만, 그 검증을
        // 거치지 않는 호출부(벤치마크 하네스 등)가 있다. new HashSet<>(null)은 생성자 안에서
        // 즉시 NPE를 던지는데 이를 받는 핸들러가 없어 원시 500이 된다.
        Set<KeywordType> selectedSet = selectedKeywords == null
            ? Set.of()
            : new HashSet<>(selectedKeywords);

        Map<String, List<String>> result = new LinkedHashMap<>();

        for (String category : KEYWORD_CATEGORIES) {
            //해당 카테고리에 속한 모든 키워드 중에서 사용자가 고른 것만 필터링
            List<String> labelsForCategory = findByCategory(category).stream()
                .filter(selectedSet::contains)
                .map(KeywordType::getLabel)
                .toList();

            if (!labelsForCategory.isEmpty()) {
                result.put(category, labelsForCategory);
            }
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(result);
        } catch (JsonProcessingException e) {
            // 사용자 대면 BusinessException을 던지지 않는 이유 (ROADMAP 7-2): result는
            // Map<String, List<String>>라 직렬화가 실패할 현실적인 경로가 없다 — 도달 불가능한
            // 자리에 사용자 대면 ErrorCode를 두면, 만에 하나 터졌을 때 거짓 메시지를 낸다.
            // (과거의 JSON_TRANSFORMATION_FAILED(503)는 8-4에서 호출자 0이 되어 삭제됐다.)
            throw new IllegalStateException("keywords JSON 변환에 실패했다: " + result, e);
        }
    }

}
