package backend.yourtrip.global.ai.grounding;

import backend.yourtrip.global.ai.AiCourseMetrics;
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
 *
 * <h2>단 하나, 업종 불일치는 <b>보류</b>다 (이슈 #147)</h2>
 * 업종이 슬롯과 어긋난 후보는 그 자리에서 죽이지 않고 세워 뒀다가 <b>슬롯이 전멸했을 때만</b>
 * 꺼낸다. 이름 게이트를 통과한 이상 실존·좌표·URL이 모두 확보된 장소인데, 하드 드롭 탓에 제주
 * day3의 저녁이 통째로 비는 일이 실측됐다 — <b>차순위가 없을 때는 "차순위가 올라온다"가 성립하지
 * 않는다.</b> 무조건 완화가 아니라 최후 구제인 것이 요지이고, 몇 건이나 그렇게 살렸는지는
 * {@code ai.grounding.relaxed} 가 따로 센다.
 */
@Component
@Slf4j
public class GroundingStage {

    private final KakaoLocalClient kakaoLocalClient;
    private final AiCourseMetrics metrics;
    private final Executor placeGroundingExecutor;

    public GroundingStage(KakaoLocalClient kakaoLocalClient, AiCourseMetrics metrics,
        @Qualifier("placeGroundingExecutor") Executor placeGroundingExecutor) {
        this.kakaoLocalClient = kakaoLocalClient;
        this.metrics = metrics;
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
        Map<Tally, Integer> tally = new LinkedHashMap<>();
        Map<GroundingRelaxation, Integer> relaxations = new EnumMap<>(GroundingRelaxation.class);
        // 검증은 통과했는데 앞 day 가 이미 써서 버린 건수 (이슈 #149). tally 는 이것도 hit 으로
        // 세므로, 이 값을 빼야 그 출처가 실제로 코스에 실은 수가 나온다.
        Map<CandidateSourceType, Integer> duplicates = new EnumMap<>(CandidateSourceType.class);

        List<GroundedDay> days = new ArrayList<>(curatedDays.size());
        for (CuratedDay day : curatedDays) {
            List<GroundedSlot> slots = new ArrayList<>(day.slots().size());
            for (CuratedSlot slot : day.slots()) {
                List<GroundedPlace> survivors = new ArrayList<>(slot.choices().size());
                // 업종이 어긋난 후보를 버리지 않고 세워 둔다 — 슬롯이 전멸했을 때만 꺼낸다.
                List<GroundedPlace> rescuable = new ArrayList<>(slot.choices().size());
                for (CuratedPlace choice : slot.choices()) {
                    Resolution resolution = resolve(pool, lookups, day.day(), slot, choice);
                    tally.merge(new Tally(resolution.outcome(), resolution.source()), 1,
                        Integer::sum);
                    if (resolution.outcome() == GroundingOutcome.CATEGORY_MISMATCH) {
                        // 중복 제거를 여기서 걸면 안 된다 — 쓰지도 않을 후보가 placed 를 선점해
                        // 다른 day 의 같은 장소를 죽인다. 구제가 확정된 뒤에 건다.
                        resolution.place().ifPresent(rescuable::add);
                        continue;
                    }
                    // filter 체인을 편 것은 계측 때문이다 (이슈 #149) — 그 형태로는 "장소가 없어
                    // 못 실었다" 와 "중복이라 못 실었다" 가 같은 빈 Optional 로 뭉개져, 버린 쪽에
                    // 카운터를 붙일 자리가 없었다.
                    Optional<GroundedPlace> resolved = resolution.place();
                    if (resolved.isEmpty()) {
                        continue;
                    }
                    GroundedPlace place = resolved.get();
                    if (placed.add(CandidateMatcher.dedupeKey(place.name(), place.address()))) {
                        survivors.add(place);
                    } else {
                        duplicates.merge(resolution.source(), 1, Integer::sum);
                    }
                }
                if (survivors.isEmpty()) {
                    rescue(rescuable, placed).ifPresent(place -> {
                        survivors.add(place);
                        relaxations.merge(GroundingRelaxation.CATEGORY_LAST_RESORT, 1,
                            Integer::sum);
                    });
                }
                slots.add(new GroundedSlot(slot.slotType(), survivors));
            }
            days.add(new GroundedDay(day.day(), slots));
        }

        // source 태그로 나누면 "무인지 지역일수록 파라메트릭이 약하다"는 설계 원칙의 미실측
        // 가설을 운영 데이터로 검증할 수 있다(5-6).
        tally.forEach((key, count) -> metrics.groundingMatch(key.outcome(), key.source(), count));
        // 완화는 결말과 나란히 오른다 — 결말을 hit 으로 바꿔치기하면 5-3 의 업종 제약이 무엇을
        // 걸렀는지 못 재고, 완화가 환각을 몇 건 들였는지도 되짚을 수 없다(이슈 #147).
        relaxations.forEach(metrics::groundingRelaxed);
        // 결말과 나란히 오른다 — 이쪽도 hit 을 뒤집지 않는다(이슈 #149). 완화가 "검증이 탈락시킨
        // 것을 우리가 살렸다" 라면 이쪽은 "검증이 통과시킨 것을 우리가 버렸다" 이고, 둘이 대칭이다.
        duplicates.forEach(metrics::groundingDuplicate);
        log.debug("그라운딩 결과: {} (완화 {}, 중복 폐기 {})", tally, relaxations, duplicates);
        return days;
    }

    /**
     * 슬롯이 전멸했을 때만 부른다 — 업종이 어긋나 보류해 둔 후보 중 <b>선호 순서로 첫 하나</b>를
     * 살린다 (이슈 #147).
     *
     * <p><b>슬롯당 하나면 충분하다.</b> {@code AiCoursePipeline} 이 {@code GroundedSlot.preferred()}
     * 하나만 배치하므로 더 넣어도 쓰이지 않는다.
     *
     * <p>중복 제거는 <b>여기서</b> 건다. 이 자리에 오기 전까지 구제 후보는 {@code placed} 를 건드리지
     * 않으므로, 앞 day 가 이미 쓴 장소면 구제하지 않고 다음 후보로 넘어간다.
     */
    private static Optional<GroundedPlace> rescue(List<GroundedPlace> rescuable,
        Set<String> placed) {
        for (GroundedPlace place : rescuable) {
            if (placed.add(CandidateMatcher.dedupeKey(place.name(), place.address()))) {
                log.debug("슬롯이 전멸해 업종 불일치 후보를 구제한다: place={}, slot={}",
                    place.name(), place.slotType());
                return Optional.of(place);
            }
        }
        return Optional.empty();
    }

    private Resolution resolve(CandidatePool pool, Map<String, PlaceLookup> lookups, int day,
        CuratedSlot slot, CuratedPlace choice) {
        Optional<PlaceCandidate> inherited = inherit(pool, day, slot, choice);
        if (inherited.isPresent()) {
            // 호출 0회. 목록 항목의 좌표·주소를 코드가 그대로 옮긴다.
            PlaceCandidate candidate = inherited.get();
            return new Resolution(Optional.of(fromCandidate(candidate, slot)),
                GroundingOutcome.HIT, candidate.source());
        }
        if (choice.placeName() == null || choice.placeName().isBlank()) {
            return suggested(GroundingOutcome.NO_RESULT);
        }

        PlaceLookup lookup = lookups.get(choice.placeName());
        if (lookup == null) {
            // 데드라인에 잘렸거나 태스크가 죽었다 — 인프라 사건이지 환각이 아니다.
            return suggested(GroundingOutcome.FAILED);
        }
        return switch (lookup) {
            case PlaceLookup.Found found -> fromDocument(found.document(), slot);
            case PlaceLookup.NameMismatch mismatch -> {
                log.debug("이름 불일치로 탈락: 요청={}, 카카오={}",
                    choice.placeName(), mismatch.bestCandidateName());
                yield suggested(GroundingOutcome.NAME_MISMATCH);
            }
            case PlaceLookup.NoResult ignored -> suggested(GroundingOutcome.NO_RESULT);
            case PlaceLookup.Failed failed -> {
                log.debug("카카오 검증 실패로 탈락: 요청={}, cause={}",
                    choice.placeName(), failed.cause());
                yield suggested(GroundingOutcome.FAILED);
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
     *
     * <h2>업종 불일치는 <b>드롭이 아니라 보류</b>다 (이슈 #147)</h2>
     * 슬롯별 카테고리 제약(5-3)은 그대로 서 있지만, 걸린 후보를 그 자리에서 버리지 않고 장소까지
     * 만들어 돌려준다. <b>이름 게이트를 통과했으므로 실존·좌표·URL이 모두 확보된 장소</b>인데,
     * 하드 드롭 탓에 제주 day3의 저녁이 통째로 비는 일이 실측됐다. 5-3이 술집을 드롭이 아니라
     * 후순위로 민 것과 같은 판단이다 — <b>보조 신호를 필수 조건으로 승격시키지 않는다.</b>
     * 실제로 쓸지는 슬롯을 다 훑은 {@code assemble} 이 정한다.
     *
     * <p>{@code score()} 자체는 여전히 건드리지 않는다 — 하네스가 밴드 경계를 그 함수 기준으로
     * 고정해 뒀고 바꾸면 세 측정점의 비교 가능성이 깨진다.
     *
     * <h2>좌표를 업종보다 먼저 본다</h2>
     * 순서가 뒤집힌 것은 <b>구제하려면 좌표가 있어야 하기 때문</b>이다. 좌표가 없으면 애초에 구제
     * 대상이 아니므로, "업종도 어긋나고 좌표도 없는" 문서는 {@code CATEGORY_MISMATCH}가 아니라
     * {@code NO_COORDINATE}로 간다 — 살릴 길이 없는 사건을 살릴 수 있는 칸에 세지 않는다.
     */
    private static Resolution fromDocument(Document document, CuratedSlot slot) {
        Double longitude = parseCoordinate(document.x());
        Double latitude = parseCoordinate(document.y());
        if (latitude == null || longitude == null) {
            log.debug("카카오 응답에 좌표가 없어 탈락: place={}", document.place_name());
            return suggested(GroundingOutcome.NO_COORDINATE);
        }

        GroundedPlace place = new GroundedPlace(
            document.place_name(),
            slot.slotType(),
            latitude,
            longitude,
            PlaceMatchScorer.bestAddressOf(document),
            document.place_url(),
            CandidateSourceType.SUGGESTED,
            null);

        if (!isCategoryAllowed(document, slot.slotType())) {
            log.debug("업종이 슬롯과 어긋난다 — 슬롯이 전멸할 때만 쓴다: place={}, group={}, slot={}",
                document.place_name(), document.category_group_code(), slot.slotType());
            return new Resolution(Optional.of(place), GroundingOutcome.CATEGORY_MISMATCH,
                CandidateSourceType.SUGGESTED);
        }
        return new Resolution(Optional.of(place), GroundingOutcome.HIT,
            CandidateSourceType.SUGGESTED);
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

    /** 카카오 검증 경로의 결말 — 목록에서 승계하지 못한 후보는 전부 {@code SUGGESTED} 로 센다. */
    private static Resolution suggested(GroundingOutcome outcome) {
        return new Resolution(Optional.empty(), outcome, CandidateSourceType.SUGGESTED);
    }

    /**
     * 후보 하나의 검증 결말.
     *
     * <p>{@code place} 가 차 있는데 {@code outcome} 이 {@code HIT} 이 아닌 조합이 하나 있다 —
     * {@code CATEGORY_MISMATCH} 다. 그것이 곧 <b>"구제 후보"</b> 라는 뜻이라 별도 플래그를 두지 않는다.
     */
    private record Resolution(Optional<GroundedPlace> place, GroundingOutcome outcome,
                              CandidateSourceType source) {
    }

    /** 메트릭 집계 키. 같은 (결말, 출처)를 모아 한 번에 올린다. */
    private record Tally(GroundingOutcome outcome, CandidateSourceType source) {
    }
}
