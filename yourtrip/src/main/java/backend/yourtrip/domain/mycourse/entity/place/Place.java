package backend.yourtrip.domain.mycourse.entity.place;

import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.time.LocalTime;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Place extends BaseEntity {

    @Id
    @Column(name = "place_id")
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "day_schedule_id")
    private DaySchedule daySchedule;

    private String name;

    private LocalTime startTime;

    @Column(columnDefinition = "TEXT")
    private String memo;

    private int budget;

    private double latitude; //위도

    private double longitude; //경도

    private String placeUrl;

    private String placeLocation;

    @Builder
    public Place(DaySchedule daySchedule, String name, LocalTime startTime, String memo, int budget,
        double latitude, double longitude, String placeUrl, String placeLocation) {
        this.daySchedule = daySchedule;
        this.name = name;
        this.startTime = startTime;
        this.memo = memo;
        this.budget = budget;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeUrl = placeUrl;
        this.placeLocation = placeLocation;
    }
}
