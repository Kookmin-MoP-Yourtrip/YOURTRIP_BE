package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.MyCourseCreateRequest;
import backend.yourtrip.domain.mycourse.dto.MyCourseCreateResponse;
import backend.yourtrip.domain.mycourse.entity.MyCourse;
import backend.yourtrip.domain.mycourse.mapper.CourseParticipantMapper;
import backend.yourtrip.domain.mycourse.mapper.MyCourseMapper;
import backend.yourtrip.domain.mycourse.repository.CourseParticipantRepository;
import backend.yourtrip.domain.mycourse.repository.MyCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class MyCourseService {

    private final MyCourseRepository myCourseRepository;
    private final CourseParticipantRepository courseParticipantRepository;
    private final UserService userService;

    @Transactional
    public MyCourseCreateResponse save(MyCourseCreateRequest request, Long userId) {
        User user = userService.getUser(userId);
        MyCourse myCourse = MyCourseMapper.toEntity(request);
        courseParticipantRepository.save(
            CourseParticipantMapper.toEntityWithOwner(user, myCourse)
        );
        MyCourse savedCourse = myCourseRepository.save(myCourse);

        return new MyCourseCreateResponse(savedCourse.getId(), "코스 등록 완료");
    }

}
