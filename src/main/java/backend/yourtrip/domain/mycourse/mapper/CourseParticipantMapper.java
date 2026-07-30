package backend.yourtrip.domain.mycourse.mapper;

import backend.yourtrip.domain.mycourse.entity.myCourse.CourseParticipant;
import backend.yourtrip.domain.mycourse.entity.myCourse.MyCourse;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.CourseRole;
import backend.yourtrip.domain.user.entity.User;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class CourseParticipantMapper {

    public static CourseParticipant toEntityWithOwner(User user, MyCourse course) {
        return CourseParticipant.builder()
            .user(user)
            .course(course)
            .role(CourseRole.OWNER)
            .build();
    }

}
