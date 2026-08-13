package backend.yourtrip.domain.mycourse.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import backend.yourtrip.domain.mycourse.dto.ai.ResolvedPlace;
import backend.yourtrip.domain.mycourse.entity.place.Place;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 좌표가 nullable이 된 뒤의 {@link PlaceMapper} 회귀 테스트.
 *
 * <p>좌표를 Double로 승격하기 전에는 미검증 장소가 0.0/0.0으로 저장돼 null이 흘러다닐 일이
 * 없었다. 이제는 null이 정상 값이므로, 좌표를 읽는 경로가 언박싱으로 터지지 않아야 한다.
 */
class PlaceMapperTest {

    @Test
    @DisplayName("좌표가 null인 장소를 복사해도 언박싱 NPE가 나지 않는다")
    void copiesPlaceWithNullCoordinates() {
        // 카카오 매칭에 실패해 좌표가 비어 있는 장소 (fork·업로드 시 이 경로를 탄다)
        Place original = Place.builder()
            .placeName("검증되지 않은 장소")
            .startTime(LocalTime.of(10, 0))
            .latitude(null)
            .longitude(null)
            .build();

        assertThatCode(() -> PlaceMapper.toCopyEntity(original, null))
            .doesNotThrowAnyException();

        Place copied = PlaceMapper.toCopyEntity(original, null);
        assertThat(copied.getLatitude()).isNull();
        assertThat(copied.getLongitude()).isNull();
        assertThat(copied.getPlaceName()).isEqualTo("검증되지 않은 장소");
    }

    @Test
    @DisplayName("좌표를 지정하지 않으면 0.0이 아니라 null로 남는다")
    void leavesCoordinatesNullWhenNotSet() {
        // 원시 double 파라미터일 때는 기본값 0.0이 오토박싱되어 적도 앞바다가 저장됐다.
        Place place = Place.builder().placeName("좌표 미지정").build();

        assertThat(place.getLatitude()).isNull();
        assertThat(place.getLongitude()).isNull();
    }

    @Test
    @DisplayName("검증이 끝난 중간 표현은 좌표까지 그대로 엔티티에 옮긴다")
    void mapsResolvedPlaceIncludingCoordinates() {
        ResolvedPlace resolved = new ResolvedPlace("불국사", LocalTime.of(9, 30),
            35.7900, 129.3320, "http://place.map.kakao.com/12760573", "경북 경주시 불국로 385");

        Place place = PlaceMapper.toEntityFromResolved(resolved, null);

        assertThat(place.getPlaceName()).isEqualTo("불국사");
        assertThat(place.getStartTime()).isEqualTo(LocalTime.of(9, 30));
        assertThat(place.getLatitude()).isEqualTo(35.7900);
        assertThat(place.getLongitude()).isEqualTo(129.3320);
        assertThat(place.getPlaceLocation()).isEqualTo("경북 경주시 불국로 385");
    }

    @Test
    @DisplayName("미검증 팩토리는 이름과 시간만 남기고 좌표를 비운다")
    void unverifiedKeepsOnlyNameAndTime() {
        ResolvedPlace unverified = ResolvedPlace.unverified("존재하지 않는 카페", LocalTime.of(14, 0));

        Place place = PlaceMapper.toEntityFromResolved(unverified, null);

        assertThat(place.getPlaceName()).isEqualTo("존재하지 않는 카페");
        assertThat(place.getStartTime()).isEqualTo(LocalTime.of(14, 0));
        assertThat(place.getLatitude()).isNull();
        assertThat(place.getLongitude()).isNull();
    }
}
