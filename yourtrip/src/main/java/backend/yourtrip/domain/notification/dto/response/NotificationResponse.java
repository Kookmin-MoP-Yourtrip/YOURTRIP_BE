package backend.yourtrip.domain.notification.dto.response;

import backend.yourtrip.domain.notification.entity.enums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알림 리스트 DTO")
public record NotificationResponse(

    @Schema(
        description = "알림 ID (고유 식별자)",
        example = "10"
    )
    Long notificationId,

    @Schema(
        description = """
            알림 타입
            - COMMENT : 내 피드에 댓글이 달린 경우
            - COURSE_REMIND : 코스 일정 리마인드 알림
            - COURSE_INVITE : 코스 협업 초대 알림
            """,
        example = "COMMENT"
    )
    NotificationType type,

    @Schema(
        description = "사용자에게 보여줄 알림 메시지 내용",
        example = "다른 사용자가 내 피드에 댓글을 남겼습니다."
    )
    String message,

    @Schema(
        description = "읽음 여부 (true면 이미 읽은 알림)",
        example = "false"
    )
    boolean readFlag,

    @Schema(
        description = "알림 생성 시각 (yyyy-MM-dd HH:mm 형식)",
        example = "2025-11-22 17:10"
    )
    String createdAt
) {}