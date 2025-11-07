package backend.yourtrip.domain.uploadcourse.entity.enums;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonFormat.Shape;
import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
@JsonFormat(shape = Shape.OBJECT) //enum을 객체로 직렬화
public enum KeywordType {

    // 이동수단
    WALK("이동수단", "뚜벅이"),
    CAR("이동수단", "자차"),

    // 동행유형
    SOLO("동행유형", "혼자"),
    COUPLE("동행유형", "연인"),
    FRIENDS("동행유형", "친구"),
    FAMILY("동행유형", "가족"),

    // 여행분위기
    HEALING("여행분위기", "힐링"),
    ACTIVITY("여행분위기", "액티비티"),
    FOOD("여행분위기", "맛집탐방"),
    SENSIBILITY("여행분위기", "감성"),
    CULTURE("여행분위기", "문화·전시"),
    NATURE("여행분위기", "자연"),
    SHOPPING("여행분위기", "쇼핑"),

    // 여행기간
    ONE_DAY("여행기간", "하루"),
    TWO_DAYS("여행기간", "1박2일"),
    WEEKEND("여행기간", "주말"),
    LONG("여행기간", "장기"),

    // 예산
    COST_EFFECTIVE("예산", "가성비"),
    NORMAL("예산", "보통"),
    PREMIUM("예산", "프리미엄");

    private final String category;
    private final String label;

    public static List<KeywordType> findByCategory(String category) {
        return Arrays.stream(values())
            .filter(tag -> tag.category.equals(category))
            .toList();
    }

}
