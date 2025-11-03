package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.dto.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class MyCourseMapper {

    public static MyCourse toEntity(MyCourseCreateRequest request) {
        return MyCourse.builder()
            .title(request.title())
            .location(request.location())
            .nights(request.nights())
            .days(request.days())
            .startDay(request.startDay())
            .endDay(request.endDay())
            .build();
    }
}
