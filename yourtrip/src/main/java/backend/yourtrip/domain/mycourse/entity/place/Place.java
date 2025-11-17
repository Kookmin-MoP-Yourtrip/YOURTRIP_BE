package backend.yourtrip.domain.mycourse.entity.place;

import backend.yourtrip.domain.mycourse.dto.request.PlaceUpdateRequest;
import backend.yourtrip.domain.mycourse.entity.dayschedule.DaySchedule;
import backend.yourtrip.global.common.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

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

    @Setter
    private LocalTime startTime;

    @Setter
    @Column(columnDefinition = "TEXT")
    private String memo;

    private Double latitude; //위도

    private Double longitude; //경도

    private String placeUrl;

    private String placeLocation;

    @OneToMany(mappedBy = "place", cascade = CascadeType.ALL, orphanRemoval = true)
    @Fetch(FetchMode.SUBSELECT) // place 조회 시 장소 사진들도 함께 조회
    private List<PlaceImage> placeImages;

    @Builder
    public Place(DaySchedule daySchedule, String name, LocalTime startTime, String memo,
        double latitude, double longitude, String placeUrl, String placeLocation) {
        this.daySchedule = daySchedule;
        this.name = name;
        this.startTime = startTime;
        this.memo = memo;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeUrl = placeUrl;
        this.placeLocation = placeLocation;
        placeImages = new ArrayList<>();
    }

    public void updatePlace(PlaceUpdateRequest request) {
        this.name = request.placeName();
        this.latitude = request.latitude();
        this.longitude = request.longitude();
        this.placeUrl = request.placeUrl();
        this.placeLocation = request.placeLocation();
    }
}
