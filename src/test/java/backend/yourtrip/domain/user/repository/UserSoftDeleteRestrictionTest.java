package backend.yourtrip.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 소프트 삭제된 사용자가 조회에서 제외된다는 계약을 고정한다.
 * <p>
 * {@code User}에 붙은 엔티티 레벨 필터가 이 동작의 유일한 근거인데, 지금까지 이를 직접
 * 검증하는 테스트가 없었다. 필터 어노테이션을 지원 종료된 {@code @Where}에서
 * {@code @SQLRestriction}으로 교체하기(#127) <b>전에</b> 이 테스트를 먼저 넣는 이유가
 * 그것이다 — 교체 전에 통과하는 것을 확인해두어야, 교체 후에도 통과한다는 사실이
 * "두 어노테이션의 동작이 같다"는 근거가 된다.
 * <p>
 * {@code @SpringBootTest}가 아니라 {@code @DataJpaTest}를 쓰는 것은
 * {@code UploadCourseJpaSliceSupport}와 같은 판단이다. 여기서 재려는 것이 JPA 매핑이
 * 만들어내는 SQL뿐이라 Redis·S3·CloudFront·Gemini 빈을 띄울 이유가 없고,
 * {@code replace = NONE}이어야 application-test.yml의 H2 설정(MODE=PostgreSQL 등)이
 * 무시되지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserSoftDeleteRestrictionTest {

    private static final String ACTIVE_EMAIL = "active@yourtrip.test";
    private static final String TO_DELETE_EMAIL = "to-delete@yourtrip.test";

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    private Long activeUserId;
    private Long toDeleteUserId;

    @BeforeEach
    void seed() {
        activeUserId = persistUser(ACTIVE_EMAIL, "활성사용자");
        toDeleteUserId = persistUser(TO_DELETE_EMAIL, "탈퇴예정사용자");

        flushAndClear();
    }

    @Test
    @DisplayName("삭제되지 않은 사용자는 findById와 findByEmail로 모두 조회된다")
    void activeUser_isVisible() {
        assertThat(userRepository.findById(activeUserId)).isPresent();
        assertThat(userRepository.findByEmail(ACTIVE_EMAIL)).isPresent();
    }

    @Test
    @DisplayName("소프트 삭제된 사용자는 findById로 조회되지 않는다")
    void softDeletedUser_isNotFoundById() {
        softDelete(toDeleteUserId);

        assertThat(userRepository.findById(toDeleteUserId)).isEmpty();
    }

    @Test
    @DisplayName("소프트 삭제된 사용자는 findByEmail로 조회되지 않는다")
    void softDeletedUser_isNotFoundByEmail() {
        softDelete(toDeleteUserId);

        assertThat(userRepository.findByEmail(TO_DELETE_EMAIL)).isEmpty();
    }

    @Test
    @DisplayName("한 사용자를 소프트 삭제해도 다른 사용자는 그대로 조회된다")
    void softDelete_doesNotAffectOtherUsers() {
        softDelete(toDeleteUserId);

        assertThat(userRepository.findById(activeUserId)).isPresent();
    }

    private Long persistUser(String email, String nickname) {
        User user = User.builder()
            .email(email)
            .password("encoded-password")
            .nickname(nickname)
            .build();
        em.persist(user);
        return user.getId();
    }

    /** 운영 코드의 탈퇴 경로({@code ProfileServiceImpl.deleteUser})와 같은 방식으로 삭제한다. */
    private void softDelete(Long userId) {
        User user = userRepository.findById(userId).orElseThrow();
        userRepository.save(user.withDeleted());

        flushAndClear();
    }

    /**
     * clear()가 이 테스트의 핵심이다. 1차 캐시에 엔티티가 남아 있으면 findById가 SELECT를
     * 아예 발행하지 않아 필터가 검증되지 않은 채 통과해버린다.
     */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
