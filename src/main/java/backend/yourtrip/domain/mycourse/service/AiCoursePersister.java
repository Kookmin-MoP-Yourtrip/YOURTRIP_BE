package backend.yourtrip.domain.mycourse.service;

import backend.yourtrip.domain.mycourse.dto.ai.ResolvedDay;
import backend.yourtrip.domain.mycourse.dto.ai.ResolvedPlace;
import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.mycourse.mapper.PlaceMapper;
import backend.yourtrip.domain.mycourse.mapper.TravelCourseMapper;
import backend.yourtrip.domain.mycourse.repository.DayScheduleRepository;
import backend.yourtrip.domain.mycourse.repository.PlaceRepository;
import backend.yourtrip.domain.mycourse.repository.TravelCourseRepository;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.service.UserService;
import backend.yourtrip.global.gemini.dto.GeminiCourseDto;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 코스 생성 결과를 DB에 저장하는 것만 담당하는 협력 빈.
 *
 * <p><b>왜 별도 빈인가.</b> Spring의 {@code @Transactional}은 프록시 기반이라 같은 클래스
 * 안에서의 {@code this.메서드()} 호출(self-invocation)에는 적용되지 않는다. 즉
 * {@code MyCourseServiceImpl} 안에 private 메서드로 두고 어노테이션만 붙이면 트랜잭션이
 * 아예 걸리지 않는다. 이 저장소에는 같은 이유로 분리한 {@link MyCourseDetailReader} 선례가
 * 있다(TASK-PRESIGN-BOTTLENECK-FIX.md 0단계).
 *
 * <p><b>왜 분리해야 했나.</b> 기존 {@code createAICourse}는 메서드 전체가
 * {@code @Transactional}이라 LLM 호출 1회와 카카오 블로킹 호출 N회가 전부 트랜잭션 안에
 * 있었다. {@code open-in-view: false}라 그 시간 내내 HikariCP 커넥션이 묶였고, 최악의 경우
 * 커넥션 하나를 수 분간 점유했다. 이건 품질이 아니라 가용성 문제다.
 *
 * <p><b>userId를 파라미터로 받는 이유.</b> {@code SecurityContextHolder}는 요청 스레드의
 * ThreadLocal이므로, 인증 정보는 호출자가 요청 스레드에서 미리 확보해 넘긴다.
 */
@Service
@RequiredArgsConstructor
public class AiCoursePersister {

    private final UserService userService;
    private final TravelCourseRepository travelCourseRepository;
    private final DayScheduleRepository dayScheduleRepository;
    private final PlaceRepository placeRepository;

    /**
     * 외부 I/O가 모두 끝난 상태에서 호출된다 — 이 트랜잭션 안에는 DB 작업만 있다.
     *
     * @return 생성된 코스 ID
     */
    @Transactional
    public Long save(AICourseCreateRequest request, GeminiCourseDto courseDto,
        List<ResolvedDay> resolvedDays, Long userId) {

        User user = userService.getUser(userId);
        TravelCourse travelCourse = travelCourseRepository.save(
            TravelCourseMapper.toAICourseEntity(request, courseDto, user));

        for (ResolvedDay resolvedDay : resolvedDays) {
            DaySchedule daySchedule = dayScheduleRepository.save(
                new DaySchedule(travelCourse, resolvedDay.day()));
            travelCourse.getDaySchedules().add(daySchedule);

            // 저장 순서가 곧 표시 순서다 — DaySchedule.places에 @OrderBy("id ASC")가 걸려 있고
            // 별도의 sequence 컬럼이 없어서, 리스트 순서 그대로 save 해야 순서가 재현된다.
            for (ResolvedPlace resolvedPlace : resolvedDay.places()) {
                Place place = placeRepository.save(
                    PlaceMapper.toEntityFromResolved(resolvedPlace, daySchedule));
                daySchedule.getPlaces().add(place);
            }
        }

        return travelCourse.getId();
    }
}
