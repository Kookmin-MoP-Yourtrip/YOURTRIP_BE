package backend.yourtrip.domain.uploadcourse.entity;

import backend.yourtrip.domain.mycourse.entity.travelCourse.TravelCourse;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@SQLRestriction("deleted = false")
@Table(indexes = @Index(name = "idx_upload_course_view_count", columnList = "view_count"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UploadCourse extends BaseEntity {

    @Id
    @Column(name = "upload_course_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // fetch를 명시하지 않으면 JPA 기본값이 EAGER라, keywords만 fetch join하는 목록 쿼리
    // (findAllByIdInWithKeywords / findAllByKeywords... / findAllByUserId...)에서 Hibernate가
    // 엔티티마다 my_course·users를 세컨더리 select로 채운다. 정작 목록 매퍼
    // (toCourseListItemCacheItem, toListItemResponse)는 둘 다 쓰지 않는다 — GET /popular 캐시
    // 미스 1건이 2문장이 아니라 12문장이었던 원인이다(#85,
    // docs/tasks/cache-effect-measurement/phase0-local-gate.md).
    //
    // 이 두 필드를 실제로 쓰는 경로는 전부 JPQL에서 명시적으로 JOIN FETCH하거나
    // (getDetail의 findWithTravelCourseAndKeywords, forkCourse의 findWithTravelCourseById)
    // 트랜잭션 안에 있다. 소유권 체크는 getUser().getId()뿐이라 프록시를 초기화조차 하지 않는다
    // — Hibernate의 identifier getter 최적화인데, @Id가 필드에 있고 getId()가 그 필드와 대응해야
    // 성립한다. Lombok @Getter를 떼거나 @Id를 getter로 옮기면 소리 없이 깨진다
    // (UploadCourseLazyProxyContractTest가 이 계약을 지킨다).
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id")
    private TravelCourse travelCourse;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String title;

    @Column(columnDefinition = "TEXT")
    private String introduction;

    private String thumbnailImageS3Key;

    private int heartCount;

    private int viewCount;

    private boolean deleted;

    @OneToMany(mappedBy = "uploadCourse", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CourseKeyword> keywords;

    private String location;

    private int forkCount;

    @Builder
    public UploadCourse(String title, String introduction, String thumbnailImageS3Key,
        TravelCourse travelCourse, User user, String location) {
        this.title = title;
        this.introduction = introduction;
        this.thumbnailImageS3Key = thumbnailImageS3Key;
        this.travelCourse = travelCourse;
        this.user = user;
        this.location = location;
        keywords = new ArrayList<>();
    }

    public void increaseViewCount() {
        this.viewCount += 1;
    }

    public void increaseForkCount() {
        this.forkCount += 1;
    }

    public void updateUploadCourseInfo(String title, String introduction, String location,
        String thumbnailImageS3Key) {
        this.title = title;
        this.introduction = introduction;
        this.location = location;
        if (thumbnailImageS3Key != null) {
            this.thumbnailImageS3Key = thumbnailImageS3Key;
        }
    }
}
