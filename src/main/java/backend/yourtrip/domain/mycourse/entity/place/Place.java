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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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
@Table(indexes = @Index(name = "idx_place_day_schedule_id", columnList = "day_schedule_id"))
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

    private String placeName;

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

    // 좌표는 Double이다. 원시 double이면 빌더에서 좌표를 지정하지 않았을 때 기본값 0.0이
    // 오토박싱되어 "적도 앞바다"가 실제 좌표인 것처럼 저장된다. AI 코스 생성에서 카카오
    // 매칭에 실패한 장소가 정확히 이 경로를 탔다. null은 "아직 검증되지 않은 좌표"를 뜻한다.
    @Builder
    public Place(DaySchedule daySchedule, String placeName, LocalTime startTime, String memo,
        Double latitude, Double longitude, String placeUrl, String placeLocation) {
        this.daySchedule = daySchedule;
        this.placeName = placeName;
        this.startTime = startTime;
        this.memo = memo;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeUrl = placeUrl;
        this.placeLocation = placeLocation;
        placeImages = new ArrayList<>();
    }

    public void updatePlace(PlaceUpdateRequest request) {
        this.placeName = request.placeName();
        this.latitude = request.latitude();
        this.longitude = request.longitude();
        this.placeUrl = request.placeUrl();
        this.placeLocation = request.placeLocation();
    }

    public void updateKakaoPlace(String placeName, String placeLocation, String placeUrl,
        Double latitude,
        Double longitude) {
        this.placeName = placeName;
        this.placeLocation = placeLocation;
        this.placeUrl = placeUrl;
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public void updatePlaceInfo(String placeName, LocalTime startTime, String memo,
        Double latitude, Double longitude, String placeUrl, String placeLocation) {
        this.placeName = placeName;
        this.startTime = startTime;
        this.memo = memo;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeUrl = placeUrl;
        this.placeLocation = placeLocation;
    }
}
