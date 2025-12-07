package backend.yourtrip.domain.uploadcourse.dto.response;

import java.util.List;
import lombok.Builder;

@Builder
public record UploadCourseListItemResponse(
    Long uploadCourseId,
    String title,
    String location,
    String thumbnailImageUrl,
    int forkCount,
    List<String> keywords
) {

}
