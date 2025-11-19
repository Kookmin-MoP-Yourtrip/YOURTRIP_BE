package backend.yourtrip.domain.mycourse.entity.myCourse;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.myCourse.enums.MyCourseType;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Getter
@SQLRestriction("deleted = false")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MyCourse extends BaseEntity {

    @Id
    @Column(name = "course_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String title;

    private String location;

    private int memberCount;

    private LocalDate startDate;

    private LocalDate endDate;

    @Enumerated(EnumType.STRING)
    @Setter
    private MyCourseType type;

    private boolean deleted;

    @OneToMany(mappedBy = "course")
    @OrderBy("day ASC")
    private List<DaySchedule> daySchedules;

    @OneToMany(mappedBy = "course")
    private List<CourseParticipant> participants;

    @Builder
    public MyCourse(String title, String location, LocalDate startDate,
        LocalDate endDate) {
        this.title = title;
        this.location = location;
        this.startDate = startDate;
        this.endDate = endDate;
        memberCount = 1;
        type = MyCourseType.DIRECT;
        daySchedules = new ArrayList<>();
        participants = new ArrayList<>();
    }
}
