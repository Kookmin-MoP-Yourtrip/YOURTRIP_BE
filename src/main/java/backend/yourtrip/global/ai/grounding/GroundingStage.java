package backend.yourtrip.global.ai.grounding;

import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.candidate.CandidateMatcher;
import backend.yourtrip.global.ai.candidate.CandidatePool;
import backend.yourtrip.global.ai.candidate.CandidateSourceType;
import backend.yourtrip.global.ai.candidate.PlaceCandidate;
import backend.yourtrip.global.ai.pipeline.CuratedDay;
import backend.yourtrip.global.ai.pipeline.CuratedPlace;
import backend.yourtrip.global.ai.pipeline.CuratedSlot;
import backend.yourtrip.global.ai.route.SlotType;
import backend.yourtrip.global.kakao.KakaoLocalClient;
import backend.yourtrip.global.kakao.PlaceLookup;
import backend.yourtrip.global.kakao.PlaceMatchScorer;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 실존 확인과 좌표 확보 (ROADMAP 5-2).
 *
 * <p><b>이 단계의 역할은 "카카오 검색"이 아니다.</b> 후보 공급 층이 생기면서 좌표를 얻을 곳이
 * 셋으로 늘었으므로, 역할을 <b>"실존 확인 + 좌표 확보"</b>로 다시 정의하고 그 역할을 출처별로
 * 다른 방법이 채운다.
 *
 * <table>
 *   <tr><th>출처</th><th>처리</th><th>카카오 호출</th></tr>
 *   <tr><td>{@code SEEDED}</td><td>목록에서 네이버 응답을 <b>코드가</b> 승계</td><td>0회</td></tr>
 *   <tr><td>{@code LISTED}</td><td>목록에서 TourAPI 응답을 <b>코드가</b> 승계</td><td>0회</td></tr>
 *   <tr><td>{@code SUGGESTED}</td><td>이름 게이트를 건 카카오 검증</td><td>1회</td></tr>
 * </table>
 *
 * <p>"재검증 생략"의 전제는 <b>코드가 목록에서 승계하는 것</b>이지 LLM이 좌표를 옮겨 적는 것이
 * 아니다 — 그래서 {@link CuratedPlace}에는 좌표·id 필드가 아예 없다.
 *
 * <h2>실패는 후보 하나만 죽인다</h2>
 * 호출 실패·무결과·이름 불일치를 <b>전부 그 후보만 탈락</b>시키고 사유별로 남긴다. 예외를 올리면
 * 15건 중 하나가 429일 때 코스 전체가 죽는다. 탈락하면 Curator의 차순위가 자연히 올라온다.
 */
@Component
@Slf4j
public class GroundingStage {

    private final KakaoLocalClient kakaoLocalClient;
    private final Executor placeGroundingExecutor;

    public GroundingStage(KakaoLocalClient kakaoLocalClient,
        @Qualifier("placeGroundingExecutor") Executor placeGroundingExecutor) {
        this.kakaoLocalClient = kakaoLocalClient;
        this.placeGroundingExecutor = placeGroundingExecutor;
    }

    public List<GroundedDay> ground(String location, List<CuratedDay> curatedDays,
        CandidatePool pool, CourseDeadline deadline) {
        if (curatedDays == null || curatedDays.isEmpty()) {
            return List.of();
        }
        CandidatePool candidatePool = pool == null ? CandidatePool.empty() : pool;

        Map<String, PlaceLookup> lookups =
            verifySuggested(location, curatedDays, candidatePool, deadline);
        return assemble(curatedDays, candidatePool, lookups);
    }

    // ── ① SUGGESTED 만 카카오로 검증한다 ──────────────────────────────────────

    /**
     * 승계할 수 없는 후보의 상호명만 모아 병렬 조회한다.
     *
     * <p><b>이름 단위로 중복을 걷어낸다.</b> 같은 장소를 두 슬롯이 제안하면 카카오를 두 번 부를
     * 이유가 없다 — 쿼터가 지연보다 희소한 자원이라는 원칙이 여기에도 그대로 적용된다.
     */
    private Map<String, PlaceLookup> verifySuggested(String location, List<CuratedDay> curatedDays,
        CandidatePool pool, CourseDeadline deadline) {
        Set<String> names = new LinkedHashSet<>();
        for (CuratedDay day : curatedDays) {
            for (CuratedSlot slot : day.slots()) {
                for (CuratedPlace choice : slot.choices()) {
                    if (inherit(pool, day.day(), slot, choice).isEmpty()
                        && choice.placeName() != null && !choice.placeName().isBlank()) {
                        names.add(choice.placeName());
                    }
                }
            }
        }
        if (names.isEmpty()) {
            return Map.of();
        }
        if (deadline.expired()) {
            log.warn("그라운딩 진입 전에 예산이 소진됐다 — SUGGESTED {}건을 검증하지 못한다", names.size());
            return Map.of();
        }

        List<String> ordered = List.copyOf(names);
        List<CompletableFuture<PlaceLookup>> futures = ordered.stream()
            .map(name -> CompletableFuture.supplyAsync(
                () -> kakaoLocalClient.lookupBestPlace(name, location), placeGroundingExecutor))
            .toList();
        awaitAll(futures, deadline);

        Map<String, PlaceLookup> lookups = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            CompletableFuture<PlaceLookup> future = futures.get(i);
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                lookups.put(ordered.get(i), future.join());
            }
        }
        return lookups;
    }

    // ── ② 조립 — 승계 / 검증 결과 반영 + 전 day 중복 제거 ──────────────────────

    private List<GroundedDay> assemble(List<CuratedDay> curatedDays, CandidatePool pool,
        Map<String, PlaceLookup> lookups) {
        // 전 day 에 걸친 중복 제거. Curator 는 day 별로 병렬 실행돼 다른 day 를 모르므로,
        // "경주 3일 내내 같은 카페" 를 막는 것은 프롬프트가 아니라 여기의 책임이다.
        Set<String> placed = new LinkedHashSet<>();
        Map<GroundingOutcome, Integer> tally = new EnumMap<>(GroundingOutcome.class);

        List<GroundedDay> days = new ArrayList<>(curatedDays.size());
        for (CuratedDay day : curatedDays) {
            List<GroundedSlot> slots = new ArrayList<>(day.slots().size());
            for (CuratedSlot slot : day.slots()) {
                List<GroundedPlace> survivors = new ArrayList<>(slot.choices().size());
                for (CuratedPlace choice : slot.choices()) {
                    Resolution resolution = resolve(pool, lookups, day.day(), slot, choice);
                    tally.merge(resolution.outcome(), 1, Integer::sum);
                    resolution.place()
                        .filter(place -> placed.add(
                            CandidateMatcher.dedupeKey(place.name(), place.address())))
                        .ifPresent(survivors::add);
                }
                slots.add(new GroundedSlot(slot.slotType(), survivors));
            }
            days.add(new GroundedDay(day.day(), slots));
        }

        log.debug("그라운딩 결과: {}", tally);
        return days;
    }

    private Resolution resolve(CandidatePool pool, Map<String, PlaceLookup> lookups, int day,
        CuratedSlot slot, CuratedPlace choice) {
        Optional<PlaceCandidate> inherited = inherit(pool, day, slot, choice);
        if (inherited.isPresent()) {
            // 호출 0회. 목록 항목의 좌표·주소를 코드가 그대로 옮긴다.
            return new Resolution(Optional.of(fromCandidate(inherited.get(), slot)),
                GroundingOutcome.HIT);
        }
        if (choice.placeName() == null || choice.placeName().isBlank()) {
            return new Resolution(Optional.empty(), GroundingOutcome.NO_RESULT);
        }

        PlaceLookup lookup = lookups.get(choice.placeName());
        if (lookup == null) {
            // 데드라인에 잘렸거나 태스크가 죽었다 — 인프라 사건이지 환각이 아니다.
            return new Resolution(Optional.empty(), GroundingOutcome.FAILED);
        }
        return switch (lookup) {
            case PlaceLookup.Found found -> fromDocument(found.document(), slot);
            case PlaceLookup.NameMismatch mismatch -> {
                log.debug("이름 불일치로 탈락: 요청={}, 카카오={}",
                    choice.placeName(), mismatch.bestCandidateName());
                yield new Resolution(Optional.empty(), GroundingOutcome.NAME_MISMATCH);
            }
            case PlaceLookup.NoResult ignored ->
                new Resolution(Optional.empty(), GroundingOutcome.NO_RESULT);
            case PlaceLookup.Failed failed -> {
                log.debug("카카오 검증 실패로 탈락: 요청={}, cause={}",
                    choice.placeName(), failed.cause());
                yield new Resolution(Optional.empty(), GroundingOutcome.FAILED);
            }
        };
    }

    /**
     * 목록에서 승계할 수 있는 후보인가.
     *
     * <p>{@code SUGGESTED}이거나 {@code listIndex}가 목록 범위를 벗어나면 빈 값이고, 그러면 그
     * 후보는 <b>카카오 검증 경로로 간다</b> — 버리지 않는 이유는 이름이 실존할 수 있기 때문이다.
     * 이것이 6-7이 말하는 "강등"의 절반이고, 강등 판정과 {@code ai.candidate.demoted} 집계는
     * 6-7이 이 자리 위에 얹는다.
     */
    private static Optional<PlaceCandidate> inherit(CandidatePool pool, int day, CuratedSlot slot,
        CuratedPlace choice) {
        if (choice.source() == null || choice.source() == CandidateSourceType.SUGGESTED) {
            return Optional.empty();
        }
        return pool.findOrEmpty(day, slot.slotType()).at(choice.listIndex());
    }

    private static GroundedPlace fromCandidate(PlaceCandidate candidate, CuratedSlot slot) {
        return new GroundedPlace(
            candidate.name(),
            slot.slotType(),
            candidate.latitude(),
            candidate.longitude(),
            candidate.address(),
            // 네이버·TourAPI 응답에는 카카오 플레이스 URL 이 없다 — 5-10 이 채운다.
            null,
            candidate.source(),
            candidate.matchedModifier());
    }

    /**
     * 카카오 응답을 장소로. <b>검증에 성공한 순간 {@code place_url}도 함께 승계한다</b> — 5-10이
     * 같은 장소를 다시 부르지 않게 하기 위해서다.
     */
    private static Resolution fromDocument(Document document, CuratedSlot slot) {
        // 슬롯별 카테고리 하드 제약(5-3). 기존에는 category_group_code 가 점수 +2 로만 쓰여
        // "점심 슬롯에 술집" 같은 어긋남을 막지 못했다. 비용이 사실상 0인데 큰 오배정이 사라진다.
        // score() 자체는 건드리지 않는다 — 하네스가 밴드 경계를 그 함수 기준으로 고정해 뒀고
        // 바꾸면 세 측정점의 비교 가능성이 깨진다(1-2가 점수 하한선을 폐기할 때와 같은 판단).
        if (!isCategoryAllowed(document, slot.slotType())) {
            log.debug("업종 불일치로 탈락: place={}, group={}, slot={}",
                document.place_name(), document.category_group_code(), slot.slotType());
            return new Resolution(Optional.empty(), GroundingOutcome.CATEGORY_MISMATCH);
        }

        Double longitude = parseCoordinate(document.x());
        Double latitude = parseCoordinate(document.y());
        if (latitude == null || longitude == null) {
            log.debug("카카오 응답에 좌표가 없어 탈락: place={}", document.place_name());
            return new Resolution(Optional.empty(), GroundingOutcome.NO_COORDINATE);
        }
        return new Resolution(Optional.of(new GroundedPlace(
            document.place_name(),
            slot.slotType(),
            latitude,
            longitude,
            PlaceMatchScorer.bestAddressOf(document),
            document.place_url(),
            CandidateSourceType.SUGGESTED,
            null)), GroundingOutcome.HIT);
    }

    /**
     * 슬롯이 허용하는 업종인가.
     *
     * <p><b>코드가 비어 있으면 통과시킨다.</b> 카카오는 그룹 코드가 없는 POI 를 돌려주기도 하는데,
     * 모르는 것을 불일치로 취급하면 실존하는 장소가 이유 없이 탈락한다 — 4-4 가 "매핑에 없는 분류는
     * 통과시키되 표시한다"고 정한 것과 같은 태도다.
     */
    private static boolean isCategoryAllowed(Document document, SlotType slotType) {
        String groupCode = document.category_group_code();
        if (groupCode == null || groupCode.isBlank()) {
            return true;
        }
        return slotType.getAllowedCategoryCodes().contains(groupCode);
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
            log.warn("SUGGESTED 검증이 예산 안에 끝나지 않았다 — 끝난 것만 쓴다");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("SUGGESTED 검증 대기가 인터럽트됐다 — 끝난 것만 쓴다");
        } catch (ExecutionException e) {
            log.warn("SUGGESTED 검증 중 예상 밖 오류: {}", e.getCause().toString());
        }
    }

    private record Resolution(Optional<GroundedPlace> place, GroundingOutcome outcome) {
    }
}
