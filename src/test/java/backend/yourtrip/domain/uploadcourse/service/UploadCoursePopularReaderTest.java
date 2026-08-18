package backend.yourtrip.domain.uploadcourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import backend.yourtrip.domain.uploadcourse.dto.cache.CourseListItemCacheItem;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;

@ExtendWith(MockitoExtension.class)
class UploadCoursePopularReaderTest {

    @Mock
    private UploadCourseRepository uploadCourseRepository;

    @InjectMocks
    private UploadCoursePopularReader uploadCoursePopularReader;

    @Test
    @DisplayName("테마를 지정하면 테마 전용 쿼리로 상위 5건만 요청한다")
    void readPopularCourseIds_ThemeGiven_UsesThemeQuery() {
        // given
        given(uploadCourseRepository.findPopularCourseIdsByTheme(eq(KeywordType.FOOD),
            any(Pageable.class))).willReturn(List.of(1L, 2L, 3L));

        // when
        List<Long> ids = uploadCoursePopularReader.readPopularCourseIds(KeywordType.FOOD);

        // then
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(uploadCourseRepository)
            .findPopularCourseIdsByTheme(eq(KeywordType.FOOD), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        assertThat(ids).containsExactly(1L, 2L, 3L);

        // 두 쿼리가 섞이면 안 된다 — 랭킹 쿼리를 (:theme IS NULL OR EXISTS ...) 하나로 다시
        // 합치면 PostgreSQL이 EXISTS를 세미조인으로 못 바꿔 규모에 선형으로 눕는다.
        // 이 분기가 그 분리를 지키는 유일한 런타임 계약이다.
        verify(uploadCourseRepository, never()).findPopularCourseIds(any(Pageable.class));
    }

    @Test
    @DisplayName("테마가 없으면 필터 없는 쿼리로 상위 5건만 요청한다")
    void readPopularCourseIds_ThemeNull_UsesNoFilterQuery() {
        // given
        given(uploadCourseRepository.findPopularCourseIds(any(Pageable.class)))
            .willReturn(List.of(1L, 2L, 3L));

        // when
        List<Long> ids = uploadCoursePopularReader.readPopularCourseIds(null);

        // then
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(uploadCourseRepository).findPopularCourseIds(pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageNumber()).isZero();
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(5);
        assertThat(ids).containsExactly(1L, 2L, 3L);

        verify(uploadCourseRepository, never())
            .findPopularCourseIdsByTheme(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("readCourseListItems는 빈 ID 목록이면 DB를 조회하지 않는다")
    void readCourseListItems_EmptyIds_SkipsQuery() {
        // when
        List<CourseListItemCacheItem> items = uploadCoursePopularReader.readCourseListItems(
            List.of());

        // then
        assertThat(items).isEmpty();
        verifyNoInteractions(uploadCourseRepository);
    }

    @Test
    @DisplayName("readCourseListItems는 엔티티가 아니라 캐시 DTO로 변환해 반환한다")
    void readCourseListItems_ReturnsCacheItems() {
        // given — 엔티티를 트랜잭션 밖으로 내보내지 않는 것이 이 메서드의 설계 의도이므로
        // 반환 타입이 캐시 DTO인지를 고정한다
        UploadCourse uploadCourse = UploadCourse.builder()
            .title("경주 1박 2일")
            .location("경주")
            .thumbnailImageS3Key("thumb.png")
            .build();
        setEntityId(uploadCourse, 7L);
        given(uploadCourseRepository.findAllByIdInWithKeywords(List.of(7L)))
            .willReturn(List.of(uploadCourse));

        // when
        List<CourseListItemCacheItem> items = uploadCoursePopularReader.readCourseListItems(
            List.of(7L));

        // then
        assertThat(items).hasSize(1);
        assertThat(items.get(0).uploadCourseId()).isEqualTo(7L);
        assertThat(items.get(0).title()).isEqualTo("경주 1박 2일");
        assertThat(items.get(0).thumbnailImageS3Key()).isEqualTo("thumb.png");
    }

    @Test
    @DisplayName("DB 조회 메서드에만 readOnly 트랜잭션이 걸려 있고 getPopularCourses에는 걸려 있지 않다")
    void transactionBoundaryStaysOnReaderOnly() throws NoSuchMethodException {
        // 캐시 조회·분산 락·CloudFront URL 조립을 트랜잭션 밖에 두는 것이 이 구조의 핵심이라,
        // 리팩터링이 조용히 되돌아가는 것을 어노테이션 수준에서 막는다.
        assertThat(UploadCourseServiceImpl.class
            .getMethod("getPopularCourses", KeywordType.class)
            .isAnnotationPresent(Transactional.class)).isFalse();

        assertThat(UploadCoursePopularReader.class
            .getMethod("readPopularCourseIds", KeywordType.class)
            .getAnnotation(Transactional.class).readOnly()).isTrue();
        assertThat(UploadCoursePopularReader.class
            .getMethod("readCourseListItems", List.class)
            .getAnnotation(Transactional.class).readOnly()).isTrue();
    }

    private void setEntityId(Object entity, Long id) {
        try {
            java.lang.reflect.Field field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
