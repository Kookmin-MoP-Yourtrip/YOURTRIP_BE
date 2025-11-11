package backend.yourtrip.domain.uploadcourse.entity.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonFormat.Shape;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

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

}
