package backend.yourtrip.domain.uploadcourse.entity;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
// (upload_course_id, keyword_type) 복합 인덱스. 인기 코스의 테마별 랭킹 쿼리
// (UploadCourseRepository.findPopularCourseIdsByTheme)가 세미조인으로 승격된 뒤,
// 바깥 view_count 인덱스를 훑으며 행마다 여기를 probe하는 Nested Loop Semi Join을
// 성립시키는 인덱스다. 이 인덱스가 없으면 플래너가 probe 비용을 감당 못 해
// 해시 세미조인으로 되돌아가고, course_keyword 전체 스캔이 규모에 비례해 커진다.
//
// 컬럼 순서: probe 조건은 (upload_course_id, keyword_type) 둘 다 등치라 순서가 무관하지만,
// upload_course_id를 선두에 두면 이 컬럼 단독으로 걸리는 다른 경로들까지 함께 커버한다 —
// findAllByIdInWithKeywords의 LEFT JOIN FETCH, findAllByKeywords*의 상관 COUNT 서브쿼리,
// 코스 삭제 시 FK 검사. 원래 누락돼 있던 FK 인덱스이기도 하다.
//
// 주의: ddl-auto=validate인 배포 환경에는 이 애노테이션만으로 인덱스가 생성되지 않는다
// (validate는 인덱스를 검증조차 하지 않는다). 운영 반영에는 별도 수동 DDL이 필요하다.
@Table(indexes = @Index(name = "idx_course_keyword_course_type",
                        columnList = "upload_course_id, keyword_type"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "course_keyword_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "upload_course_id")
    private UploadCourse uploadCourse;

    @Enumerated(EnumType.STRING)
    private KeywordType keywordType;

    public CourseKeyword(UploadCourse uploadCourse, KeywordType keywordType) {
        this.uploadCourse = uploadCourse;
        this.keywordType = keywordType;

    }

}
