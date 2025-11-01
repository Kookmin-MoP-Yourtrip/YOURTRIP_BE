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
import org.hibernate.annotations.SQLRestriction;

@Entity
@SQLRestriction("deleted = false")
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
    private MyCourseType type=MyCourseType.DIRECT;

    private boolean deleted;

}
