package backend.yourtrip.domain.mycourse.entity.dayschedule;

import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalTime;

@Entity
public class Place extends BaseEntity {

    @Id
    @Column(name = "place_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "day_schedule_id")
    private DaySchedule daySchedule;

    private String name;

    private LocalTime startTime;

    @Column(columnDefinition = "TEXT")
    private String memo;

    private int budget;

    private double latitude; //위도

    private double longitude; //경도

    private String mapUrl;
}
