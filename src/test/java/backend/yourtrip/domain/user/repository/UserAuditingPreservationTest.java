package backend.yourtrip.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.domain.user.entity.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDateTime;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 사용자 상태를 바꿔도 {@code created_at}이 보존된다는 계약을 고정한다.
 * <p>
 * 과거 {@code User}는 {@code toBuilder()}로 새 인스턴스를 만들어 돌려주는 {@code with*}
 * 메서드로 상태를 바꿨고, 호출부가 {@code save()}로 merge했다. Lombok {@code @Builder}는
 * {@code @SuperBuilder}와 달리 상위 클래스({@code BaseEntity})의 필드를 복사하지 않으므로
 * 그 복사본은 {@code createdAt}이 null이었고, merge가 그 null을 DB까지 내려보냈다(#136).
 * {@code updatedAt}은 {@code @LastModifiedDate}가 다시 채우지만 {@code createdAt}은
 * {@code @CreatedDate}라 최초 저장에만 동작해 복구되지 않는다.
 * <p>
 * 의도한 변경은 성공하고 파괴만 조용히 일어나 예외도 로그도 남지 않는 종류의 버그라,
 * 이 테스트가 유일한 감시 장치다. {@code UserSoftDeleteRestrictionTest}와 같은 이유로
 * {@code @DataJpaTest} 슬라이스를 쓴다 - 여기서 재려는 것이 JPA가 만들어내는 SQL뿐이고,
 * {@code replace = NONE}이어야 application-test.yml의 H2 설정이 무시되지 않는다.
 */
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = Replace.NONE)
class UserAuditingPreservationTest {

    @PersistenceContext
    private EntityManager em;

    @Autowired
    private UserRepository userRepository;

    private Long userId;
    private LocalDateTime seededCreatedAt;

    @BeforeEach
    void seed() {
        User user = User.builder()
            .email("audit@yourtrip.test")
            .password("encoded-password")
            .nickname("감사대상")
            .build();
        em.persist(user);
        em.flush();

        userId = user.getId();
        em.clear();

        // 기준선은 인메모리 값이 아니라 DB에 실제로 저장된 값으로 잡는다. LocalDateTime은
        // 나노초까지 갖지만 timestamp 컬럼은 마이크로초까지만 저장해서, 인메모리 값과 직접
        // 비교하면 보존이 잘 되고 있어도 정밀도 차이로 실패한다. 여기서 재려는 것은
        // "저장된 값이 그대로 남는가"이므로 저장된 값이 기준선이어야 맞다.
        //
        // 이 값이 null이 아니라는 것이 auditing 자가 검증도 겸한다. @EnableJpaAuditing은
        // YourtripApplication(= @SpringBootConfiguration)에 붙어 있어 슬라이스에서도 살아
        // 있어야 하는데, 만약 죽으면 createdAt이 처음부터 null이라 "보존된다"는 명제 자체가
        // 무의미해진다. 그걸 조용히 통과시켜서는 안 된다.
        seededCreatedAt = rawCreatedAt(userId);
        assertThat(seededCreatedAt)
            .as("JPA auditing이 @DataJpaTest 슬라이스에서 활성화되어 created_at이 저장되어야 한다")
            .isNotNull();
    }

    @Test
    @DisplayName("refreshToken 갱신 후에도 created_at이 DB에 보존된다")
    void updateRefreshToken_preservesCreatedAt() {
        assertMutationPreservesCreatedAt(user -> user.updateRefreshToken("new-refresh-token"));
    }

    @Test
    @DisplayName("refreshToken 무효화 후에도 created_at이 DB에 보존된다")
    void clearRefreshToken_preservesCreatedAt() {
        assertMutationPreservesCreatedAt(User::clearRefreshToken);
    }

    @Test
    @DisplayName("닉네임 변경 후에도 created_at이 DB에 보존된다")
    void updateNickname_preservesCreatedAt() {
        assertMutationPreservesCreatedAt(user -> user.updateNickname("바뀐닉네임"));
    }

    @Test
    @DisplayName("비밀번호 변경 후에도 created_at이 DB에 보존된다")
    void changePassword_preservesCreatedAt() {
        assertMutationPreservesCreatedAt(user -> user.changePassword("encoded-new-password"));
    }

    @Test
    @DisplayName("프로필 이미지 변경 후에도 created_at이 DB에 보존된다")
    void updateProfileImage_preservesCreatedAt() {
        assertMutationPreservesCreatedAt(user -> user.updateProfileImage("new-profile.png"));
    }

    @Test
    @DisplayName("소프트 삭제 후에도 created_at이 DB에 보존된다")
    void softDelete_preservesCreatedAt() {
        // 이 케이스만 findById로 읽을 수 없다 - clear() 이후에는 @SQLRestriction("deleted = false")가
        // 걸려 조회 자체가 비어버린다. 그래서 raw 컬럼만 확인한다.
        User user = userRepository.findById(userId).orElseThrow();
        user.softDelete();

        em.flush();
        em.clear();

        assertThat(rawCreatedAt(userId))
            .as("탈퇴 처리는 created_at을 건드리면 안 된다")
            .isEqualTo(seededCreatedAt);
    }

    @Test
    @DisplayName("상태를 바꾸면 updated_at은 갱신된다 - created_at 보존이 'UPDATE가 아예 안 나감'의 부작용이 아니다")
    void mutation_stillBumpsUpdatedAt() {
        User user = userRepository.findById(userId).orElseThrow();
        LocalDateTime before = user.getUpdatedAt();
        user.updateNickname("갱신확인용");

        em.flush();
        em.clear();

        User reloaded = userRepository.findById(userId).orElseThrow();
        assertThat(reloaded.getNickname()).isEqualTo("갱신확인용");
        assertThat(reloaded.getUpdatedAt()).isAfterOrEqualTo(before);
    }

    private void assertMutationPreservesCreatedAt(Consumer<User> mutation) {
        User user = userRepository.findById(userId).orElseThrow();
        mutation.accept(user);

        // flush(): UPDATE를 DB로 실제로 밀어낸다.
        // clear(): 1차 캐시를 비운다. 이게 없으면 아래 findById가 SELECT를 발행하지 않고 방금 그
        //   자바 인스턴스를 그대로 돌려주는데, 그 인스턴스의 createdAt은 뮤테이터가 건드리지
        //   않았으니 UPDATE가 DB에 null을 썼더라도 단언이 통과해버린다.
        em.flush();
        em.clear();

        assertThat(userRepository.findById(userId).orElseThrow().getCreatedAt())
            .isEqualTo(seededCreatedAt);
        assertThat(rawCreatedAt(userId)).isEqualTo(seededCreatedAt);
    }

    /**
     * 엔티티 로드 경로(1차 캐시, @SQLRestriction)를 전부 우회해 DB 컬럼 값을 그대로 읽는다.
     * Hibernate는 네이티브 쿼리 앞에서 auto-flush를 보장하지 않으므로 호출부가 먼저 flush한다.
     */
    private LocalDateTime rawCreatedAt(Long id) {
        Object value = em.createNativeQuery("select created_at from users where user_id = :id")
            .setParameter("id", id)
            .getSingleResult();

        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        return ((java.sql.Timestamp) value).toLocalDateTime();
    }
}
