package backend.yourtrip.global.ai.candidate;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import backend.yourtrip.global.ai.AiCourseMetrics;
import backend.yourtrip.global.ai.CourseDeadline;
import backend.yourtrip.global.ai.pipeline.PlannerDayPlan;
import backend.yourtrip.global.ai.pipeline.PlannerPlan;
import backend.yourtrip.global.ai.route.SlotType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 후보 풀을 만드는 단계 — Planner와 Curator 사이에 놓인다 (ROADMAP 5-8).
 *
 * <p><b>이 단계가 푸는 문제는 정밀도가 아니라 재현율이다.</b> 그라운딩은 "가짜 상호명"을 막지만
 * "이 지역에 좋은 곳이 있는데 후보에 아예 못 올랐다"는 아무도 안 푼다. 그래서 후보를 외부에서
 * 공급한다 — 네이버 시더가 인기 축을, TourAPI가 관광 슬롯의 커버리지·분류 축을 맡는다.
 * <b>카카오는 후보 소스가 아니다</b>(인기도 정렬이 없어 밀집 지역에서 임의 슬라이스가 된다).
 *
 * <h2>병렬 구조 — day별로 감싸지 않고 평평하게 편다</h2>
 * 지오코딩 한 라운드를 먼저 끝내고, 그 결과로 만든 <b>모든 리프 호출을 한 번에 fan-out</b>한다.
 * day 단위로 감싸면 day 태스크가 자식 태스크를 기다리며 같은 풀의 스레드를 붙잡는데
 * ({@code placeGroundingExecutor} 하나를 공유하므로) 그건 풀 고갈을 부르는 구조다. 평평하게 펴면
 * 대기하는 스레드가 요청 스레드 하나뿐이고, 설계의 지연 예산("지오코딩 ≤3 → 네이버 ∥ TourAPI /
 * 풀 12")과도 그대로 맞는다.
 *
 * <h2>fail-open</h2>
 * 개별 호출 실패·0건·데드라인 초과는 <b>그 조각만</b> 비운다. 전부 실패하면 빈 풀을 돌려주고
 * Curator가 파라메트릭만 내는 초안 구조로 degrade한다 — 여기서 예외를 올리면 네이버 장애 하나가
 * AI 코스 생성 전체를 죽인다.
 */
@Component
@Slf4j
public class CandidateRetrievalStage {

    private final AreaGeocoder areaGeocoder;
    private final NaverLocalSeedSource naverLocalSeedSource;
    private final TourApiSource tourApiSource;
    private final AiCourseMetrics metrics;
    private final Executor placeGroundingExecutor;

    public CandidateRetrievalStage(AreaGeocoder areaGeocoder,
        NaverLocalSeedSource naverLocalSeedSource,
        TourApiSource tourApiSource,
        AiCourseMetrics metrics,
        @Qualifier("placeGroundingExecutor") Executor placeGroundingExecutor) {
        this.areaGeocoder = areaGeocoder;
        this.naverLocalSeedSource = naverLocalSeedSource;
        this.tourApiSource = tourApiSource;
        this.metrics = metrics;
        this.placeGroundingExecutor = placeGroundingExecutor;
    }

    public CandidatePool retrieve(String location, PlannerPlan plan, List<KeywordType> keywords,
        CourseDeadline deadline) {
        List<PlannerDayPlan> days = plan == null ? List.of() : plan.days();
        if (days.isEmpty()) {
            return CandidatePool.empty();
        }
        if (deadline.expired()) {
            log.warn("후보 공급 진입 전에 예산이 소진됐다 — 빈 풀로 진행한다(초안 구조로 degrade)");
            return CandidatePool.empty();
        }

        List<StyleTag> modifiers = StyleModifierDictionary.modifiersFor(keywords);
        Set<StyleTag> preferredTags = Set.copyOf(StyleModifierDictionary.preferredTagsFor(keywords));

        Map<Integer, GeocodeResult> geocodes = geocodeDays(location, days, deadline);
        List<SeedOutcome> seeds = fetchSeeds(days, geocodes, modifiers, deadline);
        Map<Integer, List<TourOutcome>> tours = fetchTours(days, geocodes, deadline);

        return assemble(days, seeds, tours, preferredTags);
    }

    // ── ① 지오코딩 — day당 1회 ────────────────────────────────────────────────

    /**
     * day별 권역 중심 좌표. <b>실패해도 그 day의 TourAPI만 건너뛴다</b> — 시더는 텍스트 기반이라
     * 좌표가 없어도 그대로 돈다(설계의 부분 실패 전략).
     */
    private Map<Integer, GeocodeResult> geocodeDays(String location, List<PlannerDayPlan> days,
        CourseDeadline deadline) {
        List<CompletableFuture<Map.Entry<Integer, GeocodeResult>>> futures = days.stream()
            .map(day -> submit(() -> Map.entry(day.day(),
                areaGeocoder.geocode(location, day.area(), day.anchor()))))
            .toList();

        Map<Integer, GeocodeResult> geocodes = new LinkedHashMap<>();
        for (Map.Entry<Integer, GeocodeResult> entry : awaitCompleted(futures, deadline, "지오코딩")) {
            geocodes.put(entry.getKey(), entry.getValue());
            metrics.geocode(entry.getValue().outcome());
        }
        // 예산에 잘려 답을 못 받은 day 도 FAILED 로 센다 — "물어보지 못했다"가 이 값의 뜻이고,
        // 조용히 빠지면 fallback 비율이 실제보다 좋아 보인다.
        for (int lost = days.size() - geocodes.size(); lost > 0; lost--) {
            metrics.geocode(GeocodeOutcome.FAILED);
        }
        return geocodes;
    }

    // ── ② 네이버 시더 — (day × 슬롯타입) × (기본 1 + modifier 1~2) ──────────────

    private List<SeedOutcome> fetchSeeds(List<PlannerDayPlan> days,
        Map<Integer, GeocodeResult> geocodes, List<StyleTag> modifiers, CourseDeadline deadline) {
        List<SeedSpec> specs = new ArrayList<>();
        for (PlannerDayPlan day : days) {
            GeocodeResult geocode = geocodes.get(day.day());
            Double latitude = geocode != null && geocode.hasCoordinate() ? geocode.latitude() : null;
            Double longitude = geocode != null && geocode.hasCoordinate() ? geocode.longitude() : null;
            for (SlotType slotType : distinctSlots(day)) {
                // 기본 쿼리를 먼저 넣는다 — dedupe 에서 "먼저 만난 쪽이 이긴다"가 곧
                // "기본 쿼리의 seedRank 가 남는다"가 되게 하려면 이 순서가 계약이다.
                specs.add(new SeedSpec(day.day(), slotType, day.area(), null, latitude, longitude));
                for (StyleTag modifier : modifiers) {
                    specs.add(new SeedSpec(day.day(), slotType, day.area(), modifier,
                        latitude, longitude));
                }
            }
        }

        List<CompletableFuture<SeedOutcome>> futures = specs.stream()
            .map(spec -> submit(() -> new SeedOutcome(spec, naverLocalSeedSource.fetch(
                spec.area(), spec.slotType(), spec.modifier(),
                spec.anchorLatitude(), spec.anchorLongitude()))))
            .toList();
        List<SeedOutcome> outcomes = awaitCompleted(futures, deadline, "네이버 시더");
        record(AiCourseMetrics.SOURCE_NAVER_LOCAL,
            outcomes.stream().map(SeedOutcome::batch).toList(), specs.size());
        return outcomes;
    }

    // ── ③ TourAPI — day × contentTypeId (슬롯 단위가 아니다) ────────────────────

    private Map<Integer, List<TourOutcome>> fetchTours(List<PlannerDayPlan> days,
        Map<Integer, GeocodeResult> geocodes, CourseDeadline deadline) {
        List<TourSpec> specs = new ArrayList<>();
        for (PlannerDayPlan day : days) {
            GeocodeResult geocode = geocodes.get(day.day());
            Set<Integer> contentTypeIds = TourApiSource.contentTypeIdsFor(distinctSlots(day));
            if (geocode == null || !geocode.hasCoordinate()) {
                // 좌표가 없으면 부를 수 없다. "물어봤는데 없더라"(EMPTY)가 아니라 "물어보지
                // 못했다"(SKIPPED)이고, 둘을 뭉치면 "외부 데이터가 얇은 지역" 지표가 오염된다.
                for (int ignored : contentTypeIds) {
                    metrics.candidateRetrieval(AiCourseMetrics.SOURCE_TOUR_API,
                        CandidateOutcome.SKIPPED);
                }
                continue;
            }
            for (int contentTypeId : contentTypeIds) {
                specs.add(new TourSpec(day.day(), contentTypeId,
                    geocode.latitude(), geocode.longitude()));
            }
        }

        List<CompletableFuture<TourOutcome>> futures = specs.stream()
            .map(spec -> submit(() -> new TourOutcome(spec, tourApiSource.fetch(
                spec.latitude(), spec.longitude(), spec.contentTypeId()))))
            .toList();

        List<TourOutcome> outcomes = awaitCompleted(futures, deadline, "TourAPI");
        record(AiCourseMetrics.SOURCE_TOUR_API,
            outcomes.stream().map(TourOutcome::batch).toList(), specs.size());

        Map<Integer, List<TourOutcome>> byDay = new LinkedHashMap<>();
        for (TourOutcome outcome : outcomes) {
            byDay.computeIfAbsent(outcome.spec().day(), key -> new ArrayList<>()).add(outcome);
        }
        return byDay;
    }

    // ── ④ 조립 — 소스 안 dedupe → 소스 간 병합 → 사전식 정렬 → cap ─────────────

    private CandidatePool assemble(List<PlannerDayPlan> days, List<SeedOutcome> seeds,
        Map<Integer, List<TourOutcome>> tours, Set<StyleTag> preferredTags) {
        List<CandidateSlot> slots = new ArrayList<>();
        for (PlannerDayPlan day : days) {
            List<TourOutcome> dayTours = tours.getOrDefault(day.day(), List.of());
            for (SlotType slotType : distinctSlots(day)) {
                List<PlaceCandidate> seeded = CandidateMerger.dedupeWithinSource(
                    seedCandidates(seeds, day.day(), slotType));
                List<PlaceCandidate> listed = CandidateMerger.dedupeWithinSource(
                    tourCandidates(dayTours, slotType));

                List<PlaceCandidate> ordered = CandidateOrdering.order(
                    CandidateMerger.mergeAcrossSources(seeded, listed), preferredTags);
                slots.add(new CandidateSlot(day.day(), slotType, ordered));
            }
        }

        CandidatePool pool = new CandidatePool(slots);
        if (pool.isEmpty()) {
            log.warn("후보 공급이 전면 실패했다 — 빈 풀로 Curator 를 실행한다(초안 구조로 degrade)");
        } else {
            log.debug("후보 공급 완료: day별 후보 수={}", pool.candidateCountByDay());
        }
        return pool;
    }

    /** 그 (day, 슬롯)의 시드 후보들. <b>기본 쿼리 → modifier 순서가 유지된다</b>(specs 순서). */
    private static List<PlaceCandidate> seedCandidates(List<SeedOutcome> seeds, int day,
        SlotType slotType) {
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (SeedOutcome outcome : seeds) {
            if (outcome.spec().day() == day && outcome.spec().slotType() == slotType) {
                candidates.addAll(outcome.batch().candidates());
            }
        }
        return candidates;
    }

    /**
     * 그 day의 TourAPI 후보 중 이 슬롯이 쓸 분류만 골라 슬롯을 다시 붙인다.
     *
     * <p>한 번 받은 목록을 여러 관광 슬롯이 나눠 쓰는 지점이다 — 슬롯마다 다시 부르면 코스당
     * 호출이 설계 예산의 네 배가 된다.
     */
    private static List<PlaceCandidate> tourCandidates(List<TourOutcome> dayTours,
        SlotType slotType) {
        Set<Integer> wanted = TourApiSource.contentTypeIdsFor(slotType);
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<PlaceCandidate> candidates = new ArrayList<>();
        for (TourOutcome outcome : dayTours) {
            if (!wanted.contains(outcome.spec().contentTypeId())) {
                continue;
            }
            for (PlaceCandidate candidate : outcome.batch().candidates()) {
                candidates.add(candidate.withSlotType(slotType));
            }
        }
        return candidates;
    }

    /**
     * day의 슬롯 타입 목록(중복 제거). 후보는 <b>슬롯 자리가 아니라 타입 단위</b>로 모은다 —
     * 같은 타입 자리가 둘이라고 같은 쿼리를 두 번 던지면 쿼터만 두 배로 쓴다.
     */
    private static Set<SlotType> distinctSlots(PlannerDayPlan day) {
        return new LinkedHashSet<>(day.slots());
    }

    /**
     * 배치 결과를 메트릭으로. <b>예산에 잘려 돌아오지 못한 호출은 {@code FAILED}로 센다</b> —
     * 조용히 빠지면 성공률이 실제보다 좋아 보인다.
     */
    private void record(String source, List<CandidateBatch> batches, int attempted) {
        for (CandidateBatch batch : batches) {
            metrics.candidateRetrieval(source, batch.outcome());
        }
        for (int lost = attempted - batches.size(); lost > 0; lost--) {
            metrics.candidateRetrieval(source, CandidateOutcome.FAILED);
        }
    }

    // ── 병렬 실행 유틸 ────────────────────────────────────────────────────────

    private <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, placeGroundingExecutor);
    }

    /**
     * 데드라인까지 기다리고 <b>끝난 것만</b> 거둔다.
     *
     * <p>타임아웃이 예외가 아니라 부분 수확인 이유는 이 단계가 fail-open이기 때문이다 — 슬롯 하나가
     * 늦었다고 코스를 죽이는 것은 "degrade, don't fail"의 정반대다. 요청 전체를 실패시킬지는
     * 7단계 오케스트레이터가 판단한다.
     */
    private static <T> List<T> awaitCompleted(List<CompletableFuture<T>> futures,
        CourseDeadline deadline, String label) {
        if (futures.isEmpty()) {
            return List.of();
        }
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(deadline.remainingMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("{} 가 예산 안에 끝나지 않았다 — 끝난 것만 쓴다", label);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("{} 대기가 인터럽트됐다 — 끝난 것만 쓴다", label);
        } catch (ExecutionException e) {
            // 개별 태스크가 던진 예외. 소스는 실패를 값으로 돌려주므로 여기 오는 것은
            // 예상 밖 결함이고, 그래도 나머지 조각은 살린다.
            log.warn("{} 중 예상 밖 오류가 났다 — 끝난 것만 쓴다: {}", label, e.getCause().toString());
        }
        return collectDone(futures);
    }

    private static <T> List<T> collectDone(Collection<CompletableFuture<T>> futures) {
        List<T> completed = new ArrayList<>(futures.size());
        for (CompletableFuture<T> future : futures) {
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                completed.add(future.join());
            }
        }
        return completed;
    }

    // ── 내부 값 타입 ──────────────────────────────────────────────────────────

    private record SeedSpec(int day, SlotType slotType, String area, StyleTag modifier,
                            Double anchorLatitude, Double anchorLongitude) {
    }

    private record SeedOutcome(SeedSpec spec, CandidateBatch batch) {
    }

    private record TourSpec(int day, int contentTypeId, double latitude, double longitude) {
    }

    private record TourOutcome(TourSpec spec, CandidateBatch batch) {
    }
}
