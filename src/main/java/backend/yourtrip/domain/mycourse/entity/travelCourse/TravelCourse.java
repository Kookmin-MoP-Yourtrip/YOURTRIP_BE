package backend.yourtrip.domain.mycourse.entity.travelCourse;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.travelCourse.enums.TravelCourseType;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.SQLRestriction;

/**
 * 물리 테이블명은 리네임 이전과 동일하게 {@code my_course}로 고정한다. {@code @Table} 명시가 없으면
 * Hibernate가 클래스명(TravelCourse)을 스네이크케이스로 변환해 새 테이블명을 추론하려고 해서,
 * ddl-auto=update 환경에서 기존 데이터가 담긴 my_course 테이블이 버려지고 빈 travel_course
 * 테이블이 새로 생기는 위험이 있다 — 이 어노테이션으로 그 위험을 원천 차단한다.
 */
@Entity
@Table(name = "my_course")
@Getter
@SQLRestriction("deleted = false")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TravelCourse extends BaseEntity {

    @Id
    @Column(name = "course_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    private String title;

    private String location;

    private LocalDate startDate;

    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    private TravelCourseType type;

    private boolean deleted;

    /** 업로드 코스용 사본이 이미 만들어졌는지 여부(중복 업로드 방지) */
    private boolean uploaded;

    @OneToMany(mappedBy = "course")
    @OrderBy("day ASC")
    private List<DaySchedule> daySchedules;

    @Builder
    public TravelCourse(User user, String title, String location, LocalDate startDate,
        LocalDate endDate, TravelCourseType type) {
        this.user = user;
        this.title = title;
        this.location = location;
        this.startDate = startDate;
        this.endDate = endDate;
        this.type = type;
        daySchedules = new ArrayList<>();
    }

    public void markAsUploaded() {
        this.uploaded = true;
    }

    public void updateCourseInfo(String title, String location, LocalDate startDate, LocalDate endDate) {
        this.title = title;
        this.location = location;
        this.startDate = startDate;
        this.endDate = endDate;
    }
}
