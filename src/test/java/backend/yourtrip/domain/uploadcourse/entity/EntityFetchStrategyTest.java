package backend.yourtrip.domain.uploadcourse.entity;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * to-one 연관의 fetch 전략을 선언 수준에서 고정한다.
 * <p>
 * JPA는 {@code @ManyToOne}·{@code @OneToOne}의 기본값이 EAGER다. fetch를 적지 않으면 목록 쿼리에서
 * Hibernate가 엔티티마다 세컨더리 select를 내는데, <b>그 코드는 아무 데도 나타나지 않아</b> 코드
 * 리뷰로 잡기 어렵다. 실제로 이 저장소는 {@code UploadCourse}의 두 필드가 그 상태로 남아 있다가
 * 부하테스트 중에야 발견됐다(#85 — {@code GET /popular} 미스 1건이 2문장이 아니라 8~12문장).
 * <p>
 * 컨텍스트를 띄우지 않는 순수 리플렉션 테스트다. 트랜잭션 경계를 같은 방식으로 고정한
 * {@code UploadCoursePopularReaderTest}와 같은 취지다.
 * <p>
 * <b>이 테스트의 한계</b>: 선언만 검사한다. {@code mappedBy}가 붙은 역방향 {@code @OneToOne}은
 * LAZY로 선언해도 Hibernate가 무시하고 즉시 조회하는데(바이트코드 인핸스먼트가 있어야 동작한다)
 * 이 테스트는 통과한다. 실제 발행 문장 수는
 * {@code UploadCourseSqlStatementCountTest}가 맡는다.
 */
class EntityFetchStrategyTest {

    private static final String ENTITY_BASE_PACKAGE = "backend.yourtrip.domain";

    @Test
    @DisplayName("모든 엔티티의 @ManyToOne·@OneToOne은 fetch = LAZY를 명시한다")
    void allToOneAssociationsAreLazy() {
        // given
        List<Class<?>> entityClasses = scanEntityClasses();
        assertThat(entityClasses)
            .as("엔티티 스캔이 실패하면 이 테스트는 아무것도 검증하지 않은 채 통과한다")
            .isNotEmpty();

        // when
        List<String> eagerAssociations = new ArrayList<>();
        for (Class<?> entityClass : entityClasses) {
            for (Field field : entityClass.getDeclaredFields()) {
                ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                if (manyToOne != null && manyToOne.fetch() != FetchType.LAZY) {
                    eagerAssociations.add(describe(entityClass, field, "@ManyToOne"));
                }
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                if (oneToOne != null && oneToOne.fetch() != FetchType.LAZY) {
                    eagerAssociations.add(describe(entityClass, field, "@OneToOne"));
                }
            }
        }

        // then
        assertThat(eagerAssociations)
            .as("fetch를 생략하면 JPA 기본값 EAGER가 적용돼, 목록 쿼리에서 엔티티마다 "
                + "세컨더리 select가 나간다. 필요한 곳은 JPQL에서 JOIN FETCH로 명시한다(#85).")
            .isEmpty();
    }

    @Test
    @DisplayName("UploadCourse의 travelCourse·user는 LAZY다 (#85가 고친 지점)")
    void uploadCourseToOneAssociationsAreLazy() throws NoSuchFieldException {
        // 위 전역 규칙이 미래에 완화되더라도 이 두 필드만은 이름으로 남아 있게 한다.
        // 인기 코스 목록·검색 목록·내 코스 목록 매퍼는 이 둘을 전혀 읽지 않는다.
        assertThat(UploadCourse.class.getDeclaredField("travelCourse")
            .getAnnotation(OneToOne.class).fetch()).isEqualTo(FetchType.LAZY);
        assertThat(UploadCourse.class.getDeclaredField("user")
            .getAnnotation(ManyToOne.class).fetch()).isEqualTo(FetchType.LAZY);
    }

    private List<Class<?>> scanEntityClasses() {
        ClassPathScanningCandidateComponentProvider scanner =
            new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));

        List<Class<?>> entityClasses = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(ENTITY_BASE_PACKAGE)) {
            try {
                entityClasses.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(
                    "엔티티 클래스를 로드하지 못했다: " + definition.getBeanClassName(), e);
            }
        }
        return entityClasses;
    }

    private String describe(Class<?> entityClass, Field field, String annotationName) {
        return "%s.%s (%s) — fetch가 없거나 EAGER다"
            .formatted(entityClass.getSimpleName(), field.getName(), annotationName);
    }
}
