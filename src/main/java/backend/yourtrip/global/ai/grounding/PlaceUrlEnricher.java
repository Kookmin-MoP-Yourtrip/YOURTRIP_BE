package backend.yourtrip.global.ai.grounding;

import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.candidate.CandidateMatcher;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.PlaceLookup;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 배치가 확정된 장소 중 <b>URL이 빈 것에만</b> 카카오 플레이스 URL을 붙인다 (ROADMAP 5-10).
 *
 * <p>FE가 {@code placeUrl}로 카카오 플레이스에 진입하므로 최종 장소에는 URL이 필요하다.
 * {@code SUGGESTED}는 그라운딩에서 이미 받았으니({@code place_url}을 함께 승계했다) 남는 것은
 * 카카오를 거치지 않은 {@code SEEDED}·{@code LISTED}뿐이다 — 후보 45개가 아니라 배치된
 * ~10~15개에만 호출한다. <b>이 단계를 배치 뒤에 두는 이유가 그 쿼터 차이다.</b>
 *
 * <h2>틀리느니 비운다</h2>
 * 수락 조건이 <b>둘</b>이고 하나라도 미달이면 {@code null}로 둔다.
 * <ol>
 *   <li>이름 일치 게이트 통과 (1-2가 점수 하한선을 대체한 그 게이트)</li>
 *   <li>카카오 좌표와 후보 좌표의 거리 ≤ {@value CandidateMatcher#PROXIMITY_THRESHOLD_KM}km</li>
 * </ol>
 * 둘째 조건은 <b>같은 상호명의 다른 지점</b>(전국구 프랜차이즈·동명 업소)에 URL을 붙이는 실수를
 * 막는다 — 좌표를 두 소스에서 갖게 된 덕에 공짜로 생긴 교차 검증이다.
 * <b>엉뚱한 장소의 URL은 URL 없음보다 나쁘다.</b> 배경이 "환각을 실존하는 엉뚱한 장소로
 * 세탁한다"고 비판한 그 실수를 URL 필드에서 반복하지 않는다.
 *
 * <h2>fail-open</h2>
 * URL은 코스 성립 조건이 아니라 "있으면 더 좋은" 부가 정보다. 그래서 전용 ErrorCode도 만들지
 * 않고, 카카오가 전면 장애여도 URL만 비고 코스는 성립한다. 보조 정보를 필수 의존성으로
 * 승격시키면 외부 장애 표면만 넓어진다.
 */
@Component
@Slf4j
public class PlaceUrlEnricher {

    private final KakaoLocalClient kakaoLocalClient;
    private final Executor placeGroundingExecutor;

    public PlaceUrlEnricher(KakaoLocalClient kakaoLocalClient,
        @Qualifier("placeGroundingExecutor") Executor placeGroundingExecutor) {
        this.kakaoLocalClient = kakaoLocalClient;
        this.placeGroundingExecutor = placeGroundingExecutor;
    }

    /**
     * @param placed 배치가 확정된 장소들. <b>반환 목록은 같은 크기·같은 순서</b>다 — 호출자가
     *               인덱스로 원래 자리에 되돌려 놓을 수 있어야 한다
     */
    public List<GroundedPlace> enrich(String location, List<GroundedPlace> placed,
        CourseDeadline deadline) {
        if (placed == null || placed.isEmpty()) {
            return List.of();
        }
        List<GroundedPlace> places = List.copyOf(placed);

        List<Integer> targets = new ArrayList<>();
        for (int i = 0; i < places.size(); i++) {
            if (places.get(i).needsPlaceUrl()) {
                targets.add(i);
            }
        }
        if (targets.isEmpty()) {
            return places;
        }
        if (deadline.expired()) {
            // 데드라인 임박 시 통째로 스킵한다 — URL 은 코스 성립 조건이 아니다.
            log.info("예산이 부족해 URL 보강을 건너뛴다: 대상 {}건", targets.size());
            return places;
        }

        List<CompletableFuture<PlaceLookup>> futures = targets.stream()
            .map(index -> CompletableFuture.supplyAsync(
                () -> kakaoLocalClient.lookupBestPlace(places.get(index).name(), location),
                placeGroundingExecutor))
            .toList();
        awaitAll(futures, deadline);

        List<GroundedPlace> enriched = new ArrayList<>(places);
        Map<PlaceUrlOutcome, Integer> tally = new EnumMap<>(PlaceUrlOutcome.class);
        for (int i = 0; i < targets.size(); i++) {
            int index = targets.get(i);
            GroundedPlace place = places.get(index);
            Attempt attempt = resolve(place, futures.get(i));
            tally.merge(attempt.outcome(), 1, Integer::sum);
            if (attempt.url() != null) {
                enriched.set(index, place.withPlaceUrl(attempt.url()));
            }
        }

        log.debug("URL 보강 결과: {}", tally);
        return List.copyOf(enriched);
    }

    private static Attempt resolve(GroundedPlace place, CompletableFuture<PlaceLookup> future) {
        if (!future.isDone() || future.isCompletedExceptionally() || future.isCancelled()) {
            return new Attempt(null, PlaceUrlOutcome.SKIPPED);
        }
        return switch (future.join()) {
            case PlaceLookup.Found found -> accept(place, found.document());
            case PlaceLookup.NameMismatch ignored ->
                new Attempt(null, PlaceUrlOutcome.NAME_MISMATCH);
            case PlaceLookup.NoResult ignored -> new Attempt(null, PlaceUrlOutcome.NO_RESULT);
            case PlaceLookup.Failed ignored -> new Attempt(null, PlaceUrlOutcome.FAILED);
        };
    }

    /** 이름 게이트는 {@code lookupBestPlace}가 이미 통과시킨 상태다. 여기서는 거리만 본다. */
    private static Attempt accept(GroundedPlace place, Document document) {
        Double longitude = parseCoordinate(document.x());
        Double latitude = parseCoordinate(document.y());
        if (latitude == null || longitude == null) {
            return new Attempt(null, PlaceUrlOutcome.NO_COORDINATE);
        }
        double distanceKm = CandidateMatcher.distanceKm(
            place.latitude(), place.longitude(), latitude, longitude);
        if (distanceKm > CandidateMatcher.PROXIMITY_THRESHOLD_KM) {
            log.debug("좌표가 멀어 URL 을 붙이지 않는다: place={}, 거리={}km",
                place.name(), String.format("%.2f", distanceKm));
            return new Attempt(null, PlaceUrlOutcome.TOO_FAR);
        }
        String url = document.place_url();
        return url == null || url.isBlank()
            ? new Attempt(null, PlaceUrlOutcome.NO_RESULT)
            : new Attempt(url, PlaceUrlOutcome.HIT);
    }

    private static Double parseCoordinate(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void awaitAll(List<CompletableFuture<PlaceLookup>> futures,
        CourseDeadline deadline) {
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("URL 보강이 예산 안에 끝나지 않았다 — 끝난 것만 붙인다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("URL 보강 대기가 인터럽트됐다 — 끝난 것만 붙인다");
        } catch (ExecutionException e) {
            log.warn("URL 보강 중 예상 밖 오류: {}", e.getCause().toString());
        }
    }

    private record Attempt(String url, PlaceUrlOutcome outcome) {
    }
}
