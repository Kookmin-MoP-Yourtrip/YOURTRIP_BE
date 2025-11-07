package backend.yourtrip.domain.mycourse.entity;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.domain.mycourse.entity.enums.MyCourseType;
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

    private int totalBudget;

    private int memberCount;
    
    private int nights;

    private int days;

    private LocalDate startDay;

    private LocalDate endDay;

    @Enumerated(EnumType.STRING)
    private MyCourseType type;

    private boolean deleted;

    @OneToMany(mappedBy = "course")
    @OrderBy("day ASC")
    private List<DaySchedule> daySchedules;

    @OneToMany(mappedBy = "course")
    private List<CourseParticipant> participants;

    @Builder
    public MyCourse(String title, String location, int nights, int days, LocalDate startDay,
        LocalDate endDay) {
        this.title = title;
        this.location = location;
        this.nights = nights;
        this.days = days;
        this.startDay = startDay;
        this.endDay = endDay;
        memberCount = 1;
        type = MyCourseType.DIRECT;
        daySchedules = new ArrayList<>();
        participants = new ArrayList<>();
    }

//    public void updateBudget(int budget) {
//        this.totalBudget += budget;
//    }
}
