package backend.yourtrip.domain.mycourse.entity;

import backend.yourtrip.domain.mycourse.entity.enums.CourseRole;
import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CourseParticipant extends BaseEntity {

    @Id
    @Column(name = "course_participant_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne
    @JoinColumn(name = "course_id")
    private MyCourse course;

    @Enumerated(EnumType.STRING)
    private CourseRole role;

    @Builder
    public CourseParticipant(User user, MyCourse course, CourseRole role) {
        this.user = user;
        this.course = course;
        this.role = role;
    }
}
