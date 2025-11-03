package backend.yourtrip.domain.mycourse.entity;

import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import java.time.LocalDate;
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

    private String thumbnailImageUrl;

    private int nights;

    private int days;

    private LocalDate startDay;

    private LocalDate endDay;

    @Enumerated(EnumType.STRING)
    private MyCourseType type;

    private boolean deleted;

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
    }

}
