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
 * <h2>병렬 구조 — 두 라운드뿐이다</h2>
 * 지오코딩 한 라운드를 먼저 끝내고(TourAPI가 그 좌표를 쓰므로 필연이다), 그 결과로 만든
 * <b>시더와 TourAPI 호출을 전부 함께 던져 한 번만 기다린다</b> — 소스별로 나눠 기다리면
 * 라운드가 셋으로 갈려 설계 지연 예산의 "네이버 ∥ TourAPI"가 깨진다.
 *
 * <p>day 단위로 감싸지 않는 이유는 따로 있다.
 * day 태스크가 자식 태스크를 기다리며 같은 풀의 스레드를 붙잡는데
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

        // 시더와 TourAPI 는 서로를 기다릴 이유가 없다 — 지오코딩 결과만 있으면 둘 다 바로
        // 던질 수 있다. 그래서 두 소스의 호출을 먼저 전부 띄우고 **한 번만** 기다린다.
        // 소스별로 나눠 기다리면 라운드가 둘로 갈려 설계 지연 예산의 "네이버 ∥ TourAPI"가
        // 깨진다(TourAPI 라운드만큼 통째로 늘어난다).
        List<SeedSpec> seedSpecs = seedSpecs(location, days, geocodes, modifiers);
        Map<TourCall, List<Integer>> tourCalls = tourCalls(days, geocodes);
        List<CompletableFuture<SeedOutcome>> seedFutures = submitSeeds(seedSpecs);
        List<CompletableFuture<TourOutcome>> tourFutures = submitTours(tourCalls.keySet());

        List<CompletableFuture<?>> pending =
            new ArrayList<>(seedFutures.size() + tourFutures.size());
        pending.addAll(seedFutures);
        pending.addAll(tourFutures);
        awaitAll(pending, deadline, "후보 공급");

        List<SeedOutcome> seeds = collectSeeds(seedFutures, seedSpecs.size());
        Map<Integer, List<TourOutcome>> tours = collectTours(tourCalls, tourFutures);

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

    private static List<SeedSpec> seedSpecs(String location, List<PlannerDayPlan> days,
        Map<Integer, GeocodeResult> geocodes, List<StyleTag> modifiers) {
        List<SeedSpec> specs = new ArrayList<>();
        for (PlannerDayPlan day : days) {
            GeocodeResult geocode = geocodes.get(day.day());
            Double latitude = geocode != null && geocode.hasCoordinate() ? geocode.latitude() : null;
            Double longitude = geocode != null && geocode.hasCoordinate() ? geocode.longitude() : null;
            List<NaverLocalSeedSource.Fallback> fallbacks = fallbackAreas(day, location);
            for (SlotType slotType : distinctSlots(day)) {
                // 기본 쿼리를 먼저 넣는다 — dedupe 에서 "먼저 만난 쪽이 이긴다"가 곧
                // "기본 쿼리의 seedRank 가 남는다"가 되게 하려면 이 순서가 계약이다.
                specs.add(new SeedSpec(day.day(), slotType, day.area(), fallbacks, null,
                    latitude, longitude));
                for (StyleTag modifier : modifiers) {
                    specs.add(new SeedSpec(day.day(), slotType, day.area(), fallbacks, modifier,
                        latitude, longitude));
                }
            }
        }

        return specs;
    }

    /**
     * Curator가 슬롯당 골라야 하는 후보 수. <b>{@code anchor} 단계의 하한선</b>이다 — 후보가 이보다
     * 적으면 3순위가 실존 후보가 아니게 되고, 1순위가 탈락했을 때의 예비도 없다.
     *
     * <p>실측에서 <b>1단계만으로는 24%의 칸이 3건에 못 미쳤다</b>(96칸 기준).
     */
    private static final int ANCHOR_FILL_THRESHOLD = 3;

    /**
     * {@code location} 단계의 하한선 — <b>0건일 때만 탄다.</b>
     *
     * <p>여기까지 넓히면 후보가 채워지긴 하지만 거리 중앙값이 <b>6.34km</b>로 벌어진다(anchor 추가분은
     * 1.07km). day를 권역으로 나눈 의미가 사라지므로, "없는 것보다 낫다"가 성립하는 0건에만 쓴다.
     */
    private static final int LOCATION_FILL_THRESHOLD = 1;

    /**
     * 후보가 모자랄 때 넓혀 가며 물어볼 단계 — <b>{@code anchor} → {@code location} 순서</b>.
     *
     * <p>{@code anchor}를 먼저 두는 근거는 실측이다. area 질의가 0건이던 4칸 중 <b>3칸이
     * {@code anchor}에서 살아났고</b>, 그 후보들은 권역 안에 머문다. 나머지 1칸처럼 {@code anchor}로도
     * 안 되는 경우가 있어 {@code location}을 뒤에 남긴다.
     */
    private static List<NaverLocalSeedSource.Fallback> fallbackAreas(PlannerDayPlan day,
        String location) {
        List<NaverLocalSeedSource.Fallback> fallbacks = new ArrayList<>(2);
        if (day.anchor() != null && !day.anchor().isBlank()) {
            fallbacks.add(new NaverLocalSeedSource.Fallback(day.anchor(), ANCHOR_FILL_THRESHOLD,
                SeedScope.ANCHOR));
        }
        if (location != null && !location.isBlank()) {
            fallbacks.add(new NaverLocalSeedSource.Fallback(location, LOCATION_FILL_THRESHOLD,
                SeedScope.LOCATION));
        }
        return List.copyOf(fallbacks);
    }

    private List<CompletableFuture<SeedOutcome>> submitSeeds(List<SeedSpec> specs) {
        return specs.stream()
            .map(spec -> submit(() -> new SeedOutcome(spec, naverLocalSeedSource.fetch(
                spec.area(), spec.fallbackAreas(), spec.slotType(), spec.modifier(),
                spec.anchorLatitude(), spec.anchorLongitude()))))
            .toList();
    }

    private List<SeedOutcome> collectSeeds(List<CompletableFuture<SeedOutcome>> futures,
        int attempted) {
        List<SeedOutcome> outcomes = collectDone(futures);
        record(AiCourseMetrics.SOURCE_NAVER_LOCAL,
            outcomes.stream().map(SeedOutcome::batch).toList(), attempted);
        return outcomes;
    }

    // ── ③ TourAPI — (좌표 × contentTypeId) 단위. 슬롯도 day도 아니다 ────────────

    /**
     * 실제로 던질 TourAPI 호출과 <b>그 호출이 대신해 줄 day 목록</b>.
     *
     * <p><b>좌표가 같은 day 들은 한 번만 부른다.</b> 3일 내내 같은 권역인 코스에서 Planner가 같은
     * {@code anchor}를 주거나 지오코딩 캐스케이드가 두 day 모두 {@code location}으로 떨어지면,
     * 세 day가 <b>완전히 같은 좌표</b>로 같은 분류를 물어보게 된다 — 그대로 두면 개발계정
     * 일 1,000건을 필요량의 세 배로 쓴다.
     *
     * <p><b>격자가 아니라 좌표 완전 일치로 묶는 이유.</b> TourAPI의 {@code dist}는 <b>우리가 보낸
     * 좌표 기준</b>으로 API가 계산해 준 값이라(4-7), 근처지만 다른 좌표의 결과를 재사용하면 그
     * 거리가 그대로 {@code distanceKm}에 실려 목록 정렬이 어긋난다. {@code TourGridKey}(0.01°)로
     * 묶으면 같은 셀 안에서도 최대 ~1.4km가 벌어진다. 격자 단위 재사용은 <b>거리를 직접 다시
     * 계산하는 것과 한 세트</b>이므로 10단계 캐시와 함께 다룬다 — 실제 중복은 대부분 "완전히 같은
     * 좌표"라 이것만으로도 대부분 사라진다.
     */
    private Map<TourCall, List<Integer>> tourCalls(List<PlannerDayPlan> days,
        Map<Integer, GeocodeResult> geocodes) {
        Map<TourCall, List<Integer>> daysByCall = new LinkedHashMap<>();
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
                daysByCall.computeIfAbsent(
                        new TourCall(contentTypeId, geocode.latitude(), geocode.longitude()),
                        key -> new ArrayList<>())
                    .add(day.day());
            }
        }
        return daysByCall;
    }

    private List<CompletableFuture<TourOutcome>> submitTours(Collection<TourCall> calls) {
        return calls.stream()
            .map(call -> submit(() -> new TourOutcome(call, tourApiSource.fetch(
                call.latitude(), call.longitude(), call.contentTypeId()))))
            .toList();
    }

    /**
     * 호출 결과를 그 호출이 대신한 day 들에 나눠 싣는다.
     *
     * <p><b>메트릭 단위는 {@code (day, 분류)}로 유지한다.</b> 중복 제거는 호출 수를 줄이는
     * 최적화일 뿐이고, 이 지표가 답해야 하는 질문("이 day의 이 분류에 후보가 모였는가")은 바뀌지
     * 않는다. 호출을 세는 쪽으로 바꾸면 5-9의 집계와 단위가 어긋나 전후 비교가 깨진다.
     */
    private Map<Integer, List<TourOutcome>> collectTours(Map<TourCall, List<Integer>> daysByCall,
        List<CompletableFuture<TourOutcome>> futures) {
        List<TourCall> calls = List.copyOf(daysByCall.keySet());
        Map<Integer, List<TourOutcome>> byDay = new LinkedHashMap<>();

        for (int i = 0; i < calls.size(); i++) {
            CompletableFuture<TourOutcome> future = futures.get(i);
            boolean done = future.isDone() && !future.isCompletedExceptionally()
                && !future.isCancelled();
            TourOutcome outcome = done ? future.join() : null;

            for (int day : daysByCall.get(calls.get(i))) {
                metrics.candidateRetrieval(AiCourseMetrics.SOURCE_TOUR_API,
                    outcome == null ? CandidateOutcome.FAILED : outcome.batch().outcome());
                if (outcome != null) {
                    byDay.computeIfAbsent(day, key -> new ArrayList<>()).add(outcome);
                }
            }
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
            if (!wanted.contains(outcome.call().contentTypeId())) {
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
        awaitAll(futures, deadline, label);
        return collectDone(futures);
    }

    /**
     * 여러 소스의 호출을 <b>한 번에</b> 기다린다. 소스별로 나눠 기다리면 라운드가 갈려
     * 설계 지연 예산의 "네이버 ∥ TourAPI"가 깨진다.
     */
    private static void awaitAll(Collection<? extends CompletableFuture<?>> futures,
        CourseDeadline deadline, String label) {
        if (futures.isEmpty()) {
            return;
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

    /**
     * 시더 호출 하나의 명세.
     *
     * <p>{@code fallbackAreas}는 <b>후보가 모자랄 때 차례로 넓혀 묻는 단계</b>다(이슈 #110).
     * 상권이 없는 유적 지명이 권역명이 되면 정규화만으로는 살아나지 않아, {@code anchor} →
     * {@code location} 순으로 물어본다. <b>단계마다 하한선이 다르다</b> — anchor 는 3건, location 은
     * 0건일 때만. 재질의를 스테이지가 아니라 소스 안에서 하는 이유는
     * <b>라운드를 가르지 않기 위해서</b>다 — 여기서 다시 던지면 라운드가 하나 늘어 지연 예산의
     * "네이버 ∥ TourAPI"가 깨진다.
     */
    private record SeedSpec(int day, SlotType slotType, String area,
                            List<NaverLocalSeedSource.Fallback> fallbackAreas,
                            StyleTag modifier, Double anchorLatitude, Double anchorLongitude) {

        private SeedSpec {
            fallbackAreas = fallbackAreas == null ? List.of() : List.copyOf(fallbackAreas);
        }
    }

    private record SeedOutcome(SeedSpec spec, CandidateBatch batch) {
    }

    /**
     * TourAPI 호출 하나의 신원. <b>{@code day} 가 없는 것이 핵심이다</b> — 좌표와 분류가 같으면
     * 어느 day 가 요구했든 같은 호출이므로, day 를 키에 넣으면 중복 제거가 성립하지 않는다.
     */
    private record TourCall(int contentTypeId, double latitude, double longitude) {
    }

    private record TourOutcome(TourCall call, CandidateBatch batch) {
    }
}
