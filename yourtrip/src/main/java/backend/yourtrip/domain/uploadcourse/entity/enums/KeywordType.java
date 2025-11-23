package backend.yourtrip.domain.uploadcourse.entity.enums;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
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
    CULTURE("mood", "문화·전시"),
    NATURE("mood", "자연"),
    SHOPPING("mood", "쇼핑"),

    // 여행기간
    ONE_DAY("duration", "하루"),
    TWO_DAYS("duration", "1박2일"),
    WEEKEND("duration", "주말"),
    LONG("duration", "장기"),

    // 예산
    COST_EFFECTIVE("budget", "가성비"),
    NORMAL("budget", "보통"),
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

    private final ObjectMapper objectMapper = new ObjectMapper();

    public String buildKeywordsJson(List<KeywordType> selectedKeywords) {
        // 선택된 키워드를 빠르게 조회하기 위한 Set 생성
        Set<KeywordType> selectedSet = new HashSet<>(selectedKeywords);

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
            log.warn("keywords JSON 변환 실패: {}, 변환 map: {}", e.getMessage(), result);
            throw new BusinessException(MyCourseErrorCode.JSON_TRANSFORMATION_FAILED);
        }
    }

}
