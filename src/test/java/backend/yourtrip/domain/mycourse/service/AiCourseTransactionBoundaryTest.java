package backend.yourtrip.domain.mycourse.service;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.mycourse.dto.request.AICourseCreateRequest;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 코스 생성의 트랜잭션 경계가 유지되는지 지키는 회귀 테스트.
 *
 * <p><b>이 테스트가 확인하는 것과 못 하는 것.</b> 실제 프록시가 걸려 트랜잭션이 시작되는지는
 * Spring 컨텍스트와 DB가 있어야 검증할 수 있고, 그건 이 단계의 범위가 아니다(커넥션 점유
 * 시간 실측은 로드맵 8단계 E2E로 미뤘다). 대신 <b>경계가 무너지는 방식의 회귀</b>는 구조로
 * 막을 수 있다 — 이 단계에서 고친 결함이 정확히 그 구조였기 때문이다.
 *
 * <p>막으려는 회귀 두 가지:
 * <ul>
 *   <li>{@code createAICourse}에 {@code @Transactional}이 다시 붙는 것 — LLM·카카오 호출이
 *       트랜잭션 안으로 돌아가 커넥션이 수 분간 묶인다</li>
 *   <li>{@link AiCoursePersister}의 저장 로직이 {@code MyCourseServiceImpl} 안으로 합쳐지는 것
 *       — self-invocation이라 프록시를 우회해 트랜잭션이 <b>아예 걸리지 않는다</b>.
 *       조용히 깨지므로 테스트로 못 잡으면 발견이 늦다</li>
 * </ul>
 */
class AiCourseTransactionBoundaryTest {

    @Test
    @DisplayName("createAICourse에는 @Transactional이 없다 — 외부 I/O가 트랜잭션 밖에 있어야 한다")
    void createAiCourseIsNotTransactional() throws Exception {
        Method createAICourse = MyCourseServiceImpl.class
            .getMethod("createAICourse", AICourseCreateRequest.class);

        assertThat(createAICourse.isAnnotationPresent(Transactional.class))
            .as("createAICourse에 @Transactional이 붙으면 LLM 호출과 카카오 호출 N회가 "
                + "트랜잭션 안으로 들어가 HikariCP 커넥션을 그 시간 내내 점유한다")
            .isFalse();
    }

    @Test
    @DisplayName("AiCoursePersister는 별도 빈이고 save에 @Transactional이 걸려 있다")
    void persisterIsSeparateTransactionalBean() {
        assertThat(AiCoursePersister.class.isAnnotationPresent(Service.class))
            .as("별도 빈이어야 프록시가 걸린다 — 같은 클래스 안의 메서드로 옮기면 "
                + "self-invocation이라 트랜잭션이 아예 적용되지 않는다")
            .isTrue();

        Method save = findSaveMethod();
        assertThat(save.isAnnotationPresent(Transactional.class))
            .as("저장 구간은 짧은 트랜잭션으로 묶여야 한다")
            .isTrue();
    }

    @Test
    @DisplayName("저장 메서드는 userId를 파라미터로 받는다 — SecurityContext를 다시 읽지 않는다")
    void persisterReceivesUserIdAsParameter() {
        // SecurityContextHolder는 요청 스레드의 ThreadLocal이라, 협력 빈이나 다른 스레드에서
        // 다시 읽으면 인증 정보가 없다. 호출자가 요청 스레드에서 확보해 넘겨야 한다.
        assertThat(findSaveMethod().getParameterTypes())
            .as("save 시그니처에 userId(Long)가 있어야 한다")
            .contains(Long.class);
    }

    private Method findSaveMethod() {
        for (Method method : AiCoursePersister.class.getDeclaredMethods()) {
            if (method.getName().equals("save")) {
                return method;
            }
        }
        throw new AssertionError("AiCoursePersister.save 를 찾지 못했다");
    }
}
