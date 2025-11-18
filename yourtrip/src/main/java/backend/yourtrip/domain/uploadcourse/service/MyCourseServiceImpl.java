package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MyCourseServiceImpl implements MyCourseService {

    @Override
    public void forkCourse(Long userId, UploadCourse uploadCourse) {
        // TODO: 추후 MyCourse 기능 구현 예정
        // 지금은 오류 방지를 위해 빈 메서드로 유지
    }
}