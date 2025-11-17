package backend.yourtrip.domain.mypage.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

/**
 * 마이페이지 > 좋아요 누른 리스트 > 피드별
 * 리스트에서 한 피드(row)를 표현하는 DTO
 */
@Schema(description = "마이페이지 - 좋아요한 피드 리스트 아이템 DTO")
@Builder
public record LikedFeedResponse(

    @Schema(example = "10", description = "좋아요한 피드 ID")
    Long feedId,

    @Schema(example = "제주 바다 노을 맛집 공유", description = "피드 제목")
    String title,

    @Schema(example = "제주 제주시 애월읍", description = "피드와 연관된 위치/지역명")
    String location,

    @Schema(
        example = "https://yourtrip.s3.ap-northeast-2.amazonaws.com/feed/img_abc123.jpg",
        description = "피드 대표 콘텐츠(이미지/영상) URL"
    )
    String contentUrl,

    @Schema(example = "27", description = "이 피드의 총 좋아요 수")
    int heartCount,

    @Schema(example = "5", description = "이 피드의 총 댓글 수")
    int commentCount
) {}