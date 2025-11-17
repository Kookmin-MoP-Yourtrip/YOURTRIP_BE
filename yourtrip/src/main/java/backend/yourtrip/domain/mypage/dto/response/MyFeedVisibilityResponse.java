package backend.yourtrip.domain.mypage.dto.response;

public record MyFeedVisibilityResponse(
    Long feedId,
    boolean isPublic,
    String message
) {}