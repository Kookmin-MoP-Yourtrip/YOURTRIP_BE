package backend.yourtrip.domain.uploadcourse.service;

import backend.yourtrip.domain.uploadcourse.dto.cache.CourseListItemCacheItem;
import backend.yourtrip.domain.uploadcourse.entity.UploadCourse;
import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.domain.uploadcourse.mapper.UploadCourseMapper;
import backend.yourtrip.domain.uploadcourse.repository.UploadCourseRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// UploadCourseServiceImpl.getPopularCourses의 DB 조회만 짧은 트랜잭션으로 묶기 위해 분리한 협력 빈.
// UploadCourseDetailReader(getDetail용)와 같은 이유 — Spring @Transactional은 프록시 기반이라
// 같은 클래스 안에서의 this.메서드() 호출에는 적용되지 않아, DB 접근 부분만 별도 빈으로 뺐다.
//
// 이 빈 밖(=트랜잭션 밖)에 남아야 하는 것들:
//   - 랭킹/아이템 캐시 read/write (Redis)
//   - 랭킹 분산 락 획득·해제와 락 대기 재시도(최대 1초 Thread.sleep)
//   - CloudFront 공개 URL 조립
// 특히 캐시가 모두 히트하면 이 빈을 아예 호출하지 않으므로 DB 커넥션을 전혀 쓰지 않는다.
// 커넥션을 쥔 채로 sleep하던 것이 분리 전의 가장 큰 문제였다.
@Service
@RequiredArgsConstructor
public class UploadCoursePopularReader {

    private static final int POPULAR_COURSE_LIMIT = 5;

    private final UploadCourseRepository uploadCourseRepository;

    /**
     * 조회수 기준 상위 코스ID를 읽는다. 랭킹 캐시 미스일 때만 호출된다.
     * <p>
     * 분산 락 획득/해제와 락 대기 재시도는 호출부(UploadCourseServiceImpl)에 남겨둔다. 이 메서드까지
     * 락 구간을 끌어오면 Redis 왕복과 Thread.sleep이 트랜잭션 안으로 들어와 분리한 의미가 없어진다.
     */
    @Transactional(readOnly = true)
    public List<Long> readPopularCourseIds(KeywordType theme) {
        return uploadCourseRepository.findPopularCourseIds(theme,
            PageRequest.of(0, POPULAR_COURSE_LIMIT));
    }

    /**
     * 아이템 캐시에서 미스난 코스들만 DB에서 읽어 캐시 DTO로 변환한다.
     * <p>
     * 엔티티가 아니라 CourseListItemCacheItem을 반환하는 이유: UploadCourse.keywords는 LAZY라
     * findAllByIdInWithKeywords의 LEFT JOIN FETCH가 유일한 초기화 보장인데, 그 보장이 다른 파일의
     * JPQL 문자열 안에만 존재한다. 엔티티를 트랜잭션 밖으로 넘기면 호출부가 그 원격 의존성에 조용히
     * 기대게 되고, open-in-view가 꺼져 있어 fetch join이 바뀌는 순간 LazyInitializationException으로
     * 터진다(PR #70에서 Place.placeImages가 정확히 이 방식으로 터졌다). 변환까지 여기서 끝내면
     * 엔티티가 트랜잭션 경계를 넘지 않아 그 사고가 구조적으로 불가능해진다.
     * <p>
     * 매핑을 트랜잭션 안에서 하는 부담은 최대 {@value #POPULAR_COURSE_LIMIT}건에 대한 순수 static
     * 변환이라 무시할 수준이다. 목록 전체 조회처럼 결과가 커지는 경로에 이 메서드를 재사용하게 되면
     * 그 판단을 다시 해야 한다.
     */
    @Transactional(readOnly = true)
    public List<CourseListItemCacheItem> readCourseListItems(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }

        List<UploadCourse> uploadCourses = uploadCourseRepository.findAllByIdInWithKeywords(ids);
        return uploadCourses.stream()
            .map(UploadCourseMapper::toCourseListItemCacheItem)
            .toList();
    }
}
