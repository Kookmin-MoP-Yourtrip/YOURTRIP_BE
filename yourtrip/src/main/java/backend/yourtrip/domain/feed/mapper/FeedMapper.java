package backend.yourtrip.domain.feed.mapper;

import backend.yourtrip.domain.feed.dto.request.FeedCreateRequest;
import backend.yourtrip.domain.feed.entity.Feed;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FeedMapper {

    public static Feed toEntity(FeedCreateRequest request) {
        return Feed.builder()
                .title(request.title())
                .location(request.location())
                .contentUrl(request.contendUrl())
                //업로드 코스 내용 필요
                .build();
    }
}
