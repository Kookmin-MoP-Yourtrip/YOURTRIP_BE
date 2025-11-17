package backend.yourtrip.domain.notification.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "알림 설정 조회 DTO")
public record NotificationSettingResponse(

    @Schema(
        description = "내 피드에 댓글이 달렸을 때 알림 수신 여부",
        example = "true"
    )
    boolean commentNoti,

    @Schema(
        description = "코스 일정(전날) 리마인드 알림 수신 여부",
        example = "true"
    )
    boolean courseRemindNoti,

    @Schema(
        description = "코스 협업 초대 알림 수신 여부",
        example = "false"
    )
    boolean courseInviteNoti
) {}