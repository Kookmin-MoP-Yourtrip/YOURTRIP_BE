package backend.yourtrip.domain.mypage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.List;

/**
 * 마이페이지 > 좋아요 누른 리스트 > 코스별
 * 리스트에서 한 코스를 표현하는 DTO
 */
@Schema(description = "마이페이지 - 좋아요한 코스 리스트 아이템 DTO")
@Builder
public record LikedCourseResponse(

    @Schema(example = "3", description = "좋아요한 업로드 코스 ID")
    Long uploadCourseId,

    @Schema(example = "봄 벚꽃 원데이 서울 투어", description = "코스 제목")
    String title,

    @Schema(
        example = "서울 주요 벚꽃 명소를 하루에 돌아보는 당일치기 코스입니다.",
        description = "코스 소개 문구"
    )
    String introduction,

    @Schema(
        example = "https://yourtrip.s3.ap-northeast-2.amazonaws.com/upload-course/thumb_123.png",
        description = "코스 대표 썸네일 이미지 URL"
    )
    String thumbnailImage,

    @Schema(
        example = "[\"벚꽃\", \"도시여행\", \"당일치기\"]",
        description = "코스에 연결된 키워드/해시태그 목록"
    )
    List<String> keywords
) {}