package backend.yourtrip.global.ai.route;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 하루치 장소의 <b>방문 순서와 시각을 계산</b>한다. 외부 의존이 없는 결정론적 계산이다.
 *
 * <p>지금까지 이 일은 LLM 프롬프트가 맡고 있었다 — "시간이 겹치지 않게", "역주행하지 않게" 같은
 * 문장으로 부탁하는 방식이다. LLM은 좌표를 모른 채 텍스트로만 배치하므로 지그재그 동선이 나오고,
 * 부탁한 규칙이 지켜지는지 확인할 방법도 없다. 좌표가 확보된 뒤 여기서 계산하면
 * <b>"시간 겹침 없음"이 규칙이 아니라 알고리즘의 성질</b>이 된다(설계 원칙 1).
 *
 * <p>시간 모델은 다음 한 줄이다.
 * <pre>
 * t[0] = dayStartTime
 * t[i] = t[i-1] + 체류시간(i-1) + 이동시간(i-1 → i)
 * </pre>
 * 앞 장소에서 나온 뒤에야 다음 장소에 도착하므로 <b>겹침이 구조적으로 불가능하다.</b>
 *
 * <p><b>내부 시각을 {@code LocalTime}이 아니라 {@code int}(자정 기준 분)로 다룬다.</b>
 * {@code LocalTime.plusMinutes()}는 자정을 넘으면 조용히 랩어라운드해서, 23:50 + 30분이
 * 00:20이 된다. 그 순간 "하루를 넘겼는지" 판정이 <b>정반대로 뒤집힌다</b> — 축소·드롭이
 * 발동해야 할 상황에서 여유롭다고 판단하는 것이다. 하루 종료 기본값이 23:59라 이건 이론적
 * 위험이 아니다.
 *
 * <p><b>이 빈은 상태를 갖지 않는다.</b> 요청마다 필요한 것은 전부 {@link Context}에 담아
 * 지역 변수로 흘려보낸다 — 싱글턴 빈에 요청별 데이터를 필드로 두면 동시 요청이 서로의 계산을
 * 덮어쓴다.
 */
@Component
public class RouteOptimizer {

    /** 하루를 분 단위로 표현할 때의 자정. */
    private static final int MINUTES_PER_DAY = 24 * 60;

    /** 사용자에게 보여줄 시각의 단위(분). {@code 09:37}은 코스 표에 어울리지 않는다. */
    private static final int DISPLAY_TIME_UNIT_MINUTES = 5;

    /**
     * 완전탐색을 시도하는 최대 장소 수.
     *
     * <p>{@code 7! = 5,040} 순열이고 순열당 계산이 상수 시간이라 1ms 미만이다. 이보다 많으면
     * <b>입력 순서를 그대로 둔다</b> — 근사 알고리즘(nearest-neighbor + 2-opt)을 붙이지 않은
     * 것은 Planner 가 슬롯을 3~6개로 제한하므로 현재 이 경로에 도달할 방법이 없기 때문이다.
     * 도달하지 않는 코드를 만들어 두면 검증되지 않은 채 썩는다. 임계값의 근거는
     * {@code RouteOptimizerBenchmarkTest}의 실측이다.
     */
    static final int MAX_BRUTE_FORCE_PLACES = 7;

    /**
     * 이동거리 1km 를 몇 분으로 환산할지.
     *
     * <p>비용 함수의 세 항은 각각 km·분·분이라 그냥 더할 수 없다. <b>거리를 분으로 환산해</b>
     * 저울을 맞춘다. 값 4.0은 {@link TravelMode#UNSPECIFIED}의 유효속도 15km/h 의 역수라,
     * 거리 항이 곧 "이동에 쓰는 시간"의 근사가 된다 — "1km 우회 = 4분 손해"로 읽힌다.
     *
     * <p>이동수단별로 나누지 않는다. 나누면 "얼마나 돌아가야 늦은 점심을 감수할 만한가"라는
     * 정책이 사용자의 이동수단 선택에 따라 조용히 바뀐다. 이동수단은 이미 시간 모델에 반영돼 있다.
     */
    private static final double DISTANCE_WEIGHT = 4.0;

    /**
     * 식사 시간창을 1분 벗어날 때의 벌점 — "2분 더 이동하는 것"과 같게 본다.
     *
     * <p>30분 늦은 점심이 15km 우회와 맞먹는다는 뜻이다. 한국 도심 하루 코스의 총 이동거리가
     * 통상 5~15km 이므로, 이 값이면 <b>제시간 식사를 위해 동선을 재배열하는 일이 실제로
     * 일어난다.</b> 반대로 5분 위반(2.5km)은 동선을 크게 망치면서까지 고치지 않는다.
     */
    private static final double MEAL_PENALTY_PER_MIN = 2.0;

    /**
     * 하루 종료 시각을 1분 넘길 때의 벌점. 식사 위반보다 무겁다.
     *
     * <p>초과는 체류 축소와 장소 드롭을 촉발하는데, <b>드롭은 장소가 코스에서 사라지는 것</b>이라
     * 사용자 체감 손실이 가장 크다. 탐색 단계에서 초과를 미리 피할 수 있으면 축소·드롭을 아예
     * 하지 않아도 된다.
     */
    private static final double OVERRUN_PENALTY_PER_MIN = 3.0;

    /**
     * 두 순열의 비용이 같다고 볼 허용 오차.
     *
     * <p><b>없으면 동점 처리를 부동소수점 잡음이 결정한다.</b> 논리적으로 같은 비용인 두 순열도
     * 합산 순서가 달라 마지막 비트가 다를 수 있는데, 그러면 승자가 재현은 되지만 좌표 리터럴을
     * 조금만 건드려도 뒤집힌다. 비용 규모가 10~1,000이라 1e-9는 상대오차 1e-12 — double 유효
     * 자릿수 안쪽이라 실제 차이를 삼키지 않는다.
     */
    private static final double COST_EPSILON = 1e-9;

    /** 점심 적정 시간대. */
    private static final TimeWindow LUNCH_WINDOW = new TimeWindow(11 * 60 + 30, 13 * 60 + 30);

    /** 저녁 적정 시간대. */
    private static final TimeWindow DINNER_WINDOW = new TimeWindow(17 * 60 + 30, 19 * 60 + 30);

    /**
     * 하루치 장소의 방문 순서와 시각을 계산한다.
     *
     * <p>장소가 {@link #MAX_BRUTE_FORCE_PLACES}개 이하면 가능한 순열을 <b>전부</b> 계산해
     * 비용이 가장 낮은 것을 고른다. 근사 알고리즘을 쓰지 않는 이유는 최적해가 공짜이기
     * 때문이다 — nearest-neighbor + 2-opt 는 구현이 더 길고 최적성 보장이 없어 "옳다"를
     * 테스트로 증명하기도 어렵다. <b>더 복잡한데 더 나쁘다.</b>
     *
     * <p>하루 종료 시각을 넘기면 ① 체류시간 일괄 축소 ② 후순위 장소 드롭 ③ 세 개에서 중단
     * 순으로 대응한다. <b>축소는 탐색 전에 할 수 없다</b> — 넘치는지 여부가 순열에 따라 달라져
     * 탐색 전에는 알 수 없기 때문이다.
     *
     * <p><b>축소·드롭 후에는 다시 탐색한다.</b> 체류가 줄면 도착 시각이 전부 앞당겨져 식사
     * 시간창 위반 항이 달라지므로, 이전 최적 순열이 더 이상 최적이 아니다. 재탐색하지 않으면
     * "완전탐색은 정확해를 준다"는 이 설계의 전제를 스스로 깨는 셈이다. 최악의 탐색 횟수는
     * {@code 1 + 1 + (n-3)}으로 {@code n=7}이면 여섯 번, 6ms 미만이다.
     */
    public RoutedDay optimize(RouteRequest request) {
        return optimize(request, MAX_BRUTE_FORCE_PLACES);
    }

    /**
     * 완전탐색 임계값을 지정할 수 있는 형태. <b>벤치마크 전용 이음매다.</b>
     *
     * <p>임계값 7의 근거를 남기려면 {@code n=8,9}의 소요시간을 실측해야 하는데, 공개 진입점은
     * 그 구간에서 탐색을 하지 않는다. 임계값을 공개 파라미터로 노출하면 테스트 때문에 정식
     * 기능이 하나 늘어나므로 package-private 으로 둔다. 이 저장소에는 같은 목적의 이음매가
     * 이미 둘 있다({@code LlmRetryExecutor.Sleeper}, {@code OpenAiLlmClient}의 테스트 생성자).
     */
    RoutedDay optimize(RouteRequest request, int maxBruteForcePlaces) {
        if (request.places().isEmpty()) {
            return RoutedDay.empty(request.day(), request.dayStartTime());
        }

        // 임계값 판정은 진입 시 한 번이다. 드롭으로 장소가 줄어 임계값 아래로 내려가도 탐색을
        // 재개하지 않는다 — 그러면 "8개짜리 날은 순서가 그대로인데 초과된 8개짜리 날만 순서가
        // 통째로 바뀐다"는 설명하기 어려운 동작이 생긴다.
        boolean bruteForce = request.places().size() <= maxBruteForcePlaces;

        List<RoutePlace> places = new ArrayList<>(request.places());
        List<RoutePlace> dropped = new ArrayList<>();
        int[] stayMinutes = defaultStayMinutes(places);
        boolean shrunk = false;

        while (true) {
            Context context = Context.of(request, places, stayMinutes);
            Schedule best = search(context, bruteForce);

            if (best.endMinutes() <= context.endMinutes()) {
                return toRoutedDay(request, places, best, stayMinutes, dropped);
            }

            // ① 체류시간을 한 번 줄여본다.
            if (!shrunk) {
                stayMinutes = shrink(stayMinutes);
                shrunk = true;
                continue;
            }

            // ② 그래도 넘치면 후순위 장소를 하나 뺀다. ③ 세 개 아래로는 빼지 않는다.
            if (places.size() <= MIN_PLACES_PER_DAY) {
                return toRoutedDay(request, places, best, stayMinutes, dropped);
            }

            int victim = dropIndex(places);
            dropped.add(places.remove(victim));
            stayMinutes = removeAt(stayMinutes, victim);
        }
    }

    /**
     * 하루가 넘칠 때 체류시간에 곱하는 비율.
     *
     * <p>하한 상수를 따로 두지 않았다. 축소는 최대 한 번이고 가장 짧은 체류가 전망대 45분이라
     * 축소 후 최소가 36분인데, 여기에 하한을 걸면 <b>절대 실행되지 않는 분기</b>가 생긴다.
     */
    private static final double SHRINK_RATIO = 0.8;

    /**
     * day 당 남겨야 하는 최소 장소 수. 이 아래로는 빼지 않고 <b>초과인 채로 반환</b>한다.
     *
     * <p>두 곳만 남은 하루는 코스라고 부르기 어렵다. 시간을 맞추는 것보다 코스의 형태를 지키는
     * 쪽이 낫다는 판단이다.
     */
    private static final int MIN_PLACES_PER_DAY = 3;

    /**
     * 먼저 버려지는 슬롯 순서. <b>{@link SlotType#MEAL}은 목록에 없다 = 드롭 대상이 아니다.</b>
     *
     * <p>산책로·전망대·쇼핑은 대체 가능하고 체류가 짧아 하나 빠져도 컨셉이 유지된다. 관광명소와
     * 체험은 그날의 목적 자체라 마지막까지 남긴다. "체류가 긴 체험(120분)을 빼야 초과가 빨리
     * 풀린다"는 반대 논리는 <b>의도적으로 버렸다</b> — 목표는 시간을 맞추는 것이 아니라
     * <b>가치를 최대한 남기고</b> 시간을 맞추는 것이다. 대신 드롭이 여러 번 반복되는 것을 허용한다.
     *
     * <p><b>{@code popularityWeight}를 기준으로 쓰지 않는다.</b> 그건 블로그 언급량을 랭킹에
     * 얼마나 반영할지를 정하는 신호의 신뢰도이지 중요도가 아니다. 중요도로 오독하면
     * 관광명소(0.2)가 카페(1.0)보다 먼저 버려진다 — "대릉원을 빼고 카페를 남긴다".
     *
     * <p>식사를 목록에서 통째로 빼면 "day 당 최소 한 끼"가 자명하게 보장된다. "식사가 둘이면
     * 하나는 빼도 된다"는 규칙도 생각할 수 있으나, 식사가 서열 최하위인 이상
     * {@link #MIN_PLACES_PER_DAY} 가드가 먼저 걸려 <b>도달할 수 없는 코드</b>가 된다.
     */
    private static final List<SlotType> DROP_ORDER = List.of(
        SlotType.SHOPPING,
        SlotType.WALK,
        SlotType.VIEWPOINT,
        SlotType.CAFE,
        SlotType.ACTIVITY,
        SlotType.ATTRACTION);

    /**
     * 다음에 뺄 장소의 인덱스. {@link #DROP_ORDER}가 앞선 슬롯부터, 같은 슬롯이면 <b>뒤에
     * 있는 것부터</b> 뺀다.
     *
     * <p>드롭 대상이 하나도 없으면(전부 식사) {@code -1} 대신 예외를 낼 수도 있지만, 그 경우는
     * {@link #MIN_PLACES_PER_DAY} 가드에 먼저 걸린다. 그래도 도달했다면 마지막 장소를 뺀다.
     */
    private static int dropIndex(List<RoutePlace> places) {
        for (SlotType slotType : DROP_ORDER) {
            for (int i = places.size() - 1; i >= 0; i--) {
                if (places.get(i).slotType() == slotType) {
                    return i;
                }
            }
        }
        return places.size() - 1;
    }

    /**
     * 체류시간을 일괄 축소한다. <b>한 번 줄이면 되돌리지 않는다.</b>
     *
     * <p>드롭으로 여유가 생겼다고 원래 시간으로 되돌리면 "축소 → 드롭 → 확대"라는 비단조
     * 동작이 되어, 루프가 끝난다는 것도 사용자에게 설명하는 것도 어려워진다.
     */
    private static int[] shrink(int[] stayMinutes) {
        int[] shrunk = new int[stayMinutes.length];
        for (int i = 0; i < stayMinutes.length; i++) {
            shrunk[i] = (int) Math.round(stayMinutes[i] * SHRINK_RATIO);
        }
        return shrunk;
    }

    private static int[] removeAt(int[] values, int index) {
        int[] remaining = new int[values.length - 1];
        System.arraycopy(values, 0, remaining, 0, index);
        System.arraycopy(values, index + 1, remaining, index, values.length - index - 1);
        return remaining;
    }

    /**
     * 탐색 한 판에 필요한 입력 전부. 요청마다 새로 만들어지므로 {@code RouteOptimizer} 자체는
     * 상태를 갖지 않는다.
     *
     * <p>배열을 그대로 들고 다니는 것은 순열 하나를 평가할 때마다 접근하는 값들이라 박싱이나
     * 인덱싱 우회를 넣고 싶지 않기 때문이다.
     *
     * @param slotTypes    장소별 슬롯 종류. 식사 시간창 판정에 쓴다
     * @param stayMinutes  장소별 체류시간. 하루 초과 시 축소되므로 슬롯 기본값과 다를 수 있다
     * @param distances    장소 간 거리(km) 대칭 행렬
     * @param startMinutes 하루 시작(자정 기준 분)
     * @param endMinutes   하루 종료(자정 기준 분)
     */
    private record Context(
        SlotType[] slotTypes,
        int[] stayMinutes,
        double[][] distances,
        int startMinutes,
        int endMinutes,
        TravelMode travelMode
    ) {

        static Context of(RouteRequest request, List<RoutePlace> places, int[] stayMinutes) {
            return new Context(
                slotTypesOf(places),
                stayMinutes,
                distanceMatrix(places),
                toMinutes(request.dayStartTime()),
                toMinutes(request.dayEndTime()),
                request.travelMode());
        }

        int size() {
            return slotTypes.length;
        }
    }

    /**
     * 한 순열을 시간축에 배치한 결과. 비용 계산과 출력 생성이 모두 이것을 읽는다.
     *
     * @param order          방문 순서. {@code places} 인덱스의 배열이다
     * @param arrivalMinutes {@code order} 순서대로의 도착 시각(자정 기준 분)
     * @param endMinutes     마지막 장소에서 나오는 시각
     */
    private record Schedule(int[] order, int[] arrivalMinutes, int endMinutes) {
    }

    /**
     * 비용이 가장 낮은 순열을 찾는다. {@code bruteForce}가 꺼져 있으면 입력 순서를 그대로 쓴다.
     */
    private Schedule search(Context context, boolean bruteForce) {
        int n = context.size();
        if (!bruteForce) {
            return buildSchedule(identityOrder(n), context);
        }

        BestSoFar best = new BestSoFar();
        permute(new int[n], new boolean[n], 0, context, best);
        return best.schedule;
    }

    /** 탐색 중 최선을 들고 다니는 가변 홀더. 지역 변수로만 쓰인다. */
    private static final class BestSoFar {
        private Schedule schedule;
        private double cost = Double.POSITIVE_INFINITY;
    }

    /**
     * 순열을 <b>사전순으로</b> 생성하며 최선을 갱신한다.
     *
     * <p>Heap's algorithm 을 쓰지 않는 이유는 성능이 아니라 동점 처리다. Heap's 는 순열을
     * 사전순으로 내놓지 않아서, 여러 순열의 비용이 같을 때 어느 것이 이길지 설명할 수 없다.
     * 사전순 백트래킹은 인덱스 스캔이 조금 더 들지만(n=7에서 마이크로초) 다음 규칙을 공짜로 준다:
     *
     * <blockquote>동점이면 인덱스가 사전순으로 앞선 순열이 이긴다. 전부 동점이면 <b>입력 순서가
     * 그대로 유지된다.</b></blockquote>
     *
     * <p>이 규칙은 테스트가 단언할 수 있고, {@code n >= 8} 폴백 경로(입력 순서 유지)와도
     * 원칙이 일치한다.
     *
     * <p><b>가지치기를 넣지 않았다.</b> {@code n=7}이면 5,040 순열 × 상수 시간이라 마이크로초
     * 단위이고, 무엇보다 벤치마크의 목적이 "완전탐색이 공짜임을 데이터로 남기는 것"인데 측정
     * 전에 최적화를 넣으면 그 측정이 의미를 잃는다. 접두사 하한 계산은 초과 벌점(종료 시각에
     * 의존)과 얽혀, 비용 항을 추가할 때마다 단조성을 다시 증명해야 하는 부채가 되기도 한다.
     */
    private void permute(int[] order, boolean[] used, int depth, Context context, BestSoFar best) {
        if (depth == order.length) {
            Schedule candidate = buildSchedule(order.clone(), context);
            double cost = cost(candidate, context);
            if (cost < best.cost - COST_EPSILON) {
                best.cost = cost;
                best.schedule = candidate;
            }
            return;
        }

        for (int i = 0; i < order.length; i++) {
            if (used[i]) {
                continue;
            }
            used[i] = true;
            order[depth] = i;
            permute(order, used, depth + 1, context, best);
            used[i] = false;
        }
    }

    /**
     * 주어진 순서대로 도착 시각을 누적한다.
     *
     * <p><b>5분 올림을 여기서 하지 않는 것이 중요하다.</b> 올림은 단조 증가라 장소마다 최대 4분씩
     * 밀리고, 7개면 하루 종료가 최대 28분 뒤로 간다. 그 28분이 하루 초과 판정에 그대로 들어가서
     * <b>실제로는 넘치지 않는 코스가 넘친 것으로 판정돼 장소가 삭제된다.</b> 표시 편의를 위한
     * 반올림이 알고리즘의 결정을 바꾸는 것은 명백히 틀렸다. 올림은 출력 직전 한 번만 한다.
     *
     * <p>부수적으로, 도착 시각이 5분 격자에 스냅되면 식사 위반분도 5의 배수로만 나와
     * <b>순열 간 동점이 대량 발생</b>하고 최적화 신호가 동점 처리에 먹힌다.
     */
    private Schedule buildSchedule(int[] order, Context context) {
        int[] arrivals = new int[order.length];
        int t = context.startMinutes();

        for (int i = 0; i < order.length; i++) {
            arrivals[i] = t;
            t += context.stayMinutes()[order[i]];
            if (i < order.length - 1) {
                t += travelMinutes(
                    context.distances()[order[i]][order[i + 1]], context.travelMode());
            }
        }

        return new Schedule(order, arrivals, t);
    }

    /**
     * 한 순열의 비용. 세 항 모두 <b>분 단위</b>로 환산돼 있어 그냥 더할 수 있다.
     *
     * <pre>
     * cost = Σ 이동거리(km) × DISTANCE_WEIGHT
     *      + Σ 식사 시간창 위반(분) × MEAL_PENALTY_PER_MIN
     *      + 하루 초과(분) × OVERRUN_PENALTY_PER_MIN
     * </pre>
     *
     * <p>순수 TSP 가 아니라 시간창 제약이 들어간 초소형 TSPTW 다. {@code n <= 7}에서 완전탐색은
     * <b>정확해</b>를 주므로, "day 당 식사를 적절한 시간대에"가 LLM 판단이 아니라 알고리즘의
     * 성질이 된다.
     */
    private double cost(Schedule schedule, Context context) {
        int[] order = schedule.order();

        double totalDistanceKm = 0.0;
        for (int i = 0; i < order.length - 1; i++) {
            totalDistanceKm += context.distances()[order[i]][order[i + 1]];
        }

        int overrunMinutes = Math.max(0, schedule.endMinutes() - context.endMinutes());

        return totalDistanceKm * DISTANCE_WEIGHT
            + mealViolationMinutes(mealArrivalsOf(schedule, context)) * MEAL_PENALTY_PER_MIN
            + overrunMinutes * OVERRUN_PENALTY_PER_MIN;
    }

    /**
     * 식사 슬롯의 시간창 위반 합(분).
     *
     * <p><b>도착 시각 기준</b>이다. "체류 구간이 윈도우와 겹치는 정도"로 재는 방법도 있으나
     * 버렸다 — 윈도우 폭(120분) 대비 식사 체류(75분)가 커서 13:00 점심조차 45분 위반이 되고,
     * 저녁은 18:15 이후 시작이 전부 위반이라 벌점이 상수처럼 깔려 최적화 신호가 흐려진다.
     * 도착 기준은 "언제 밥을 먹기 시작하는가"라는 직관과 정확히 일치한다.
     *
     * <p><b>윈도우는 일대일로 배정한다.</b> 매번 가까운 쪽을 고르게 두면 12:00과 13:00에 두 끼가
     * 나란히 붙어도 둘 다 위반 0이 되어, 그 배열이 최단거리이기만 하면 최적화기가 그걸 고른다 —
     * 점심 먹고 한 시간 뒤 또 점심인 코스다. 이른 쪽을 점심, 늦은 쪽을 저녁에 묶으면 두 번째
     * 식사가 저녁 윈도우까지의 거리를 벌점으로 물어 최적화기가 알아서 두 끼를 벌린다.
     *
     * <p>식사가 하나뿐일 때만 가까운 쪽을 고른다. "첫 식사는 무조건 점심"으로 고정하면 오후에
     * 시작하는 day 가 큰 벌점을 뒤집어쓰고, 그걸 줄이려 저녁을 점심 시간대로 끌어당기는 왜곡이
     * 생긴다.
     *
     * <p><b>끼니가 셋 이상이면 이 모델이 표현할 수 있는 범위를 벗어난다.</b> 윈도우가 둘뿐이라
     * 아침을 넣을 자리가 없다. 그래서 "두 윈도우를 가장 잘 채우는 두 끼를 고르고, 나머지는 가까운
     * 윈도우까지의 거리로 잰다"로 일반화했다. 이 경우 벌점의 절대값에는 큰 의미가 없지만,
     * 최적화기는 순열 간 <b>상대 비교</b>만 하므로 "가장 덜 나쁜 시각"으로 밀어주는 데는 지장이
     * 없다. Planner 가 day 당 식사를 둘 이하로 내보내므로 실제로 흔한 경우도 아니다(로드맵 6-3).
     *
     * <p>순수 함수로 떼어내 값으로 직접 검증한다({@code LlmRetryExecutor.backoffMillis} 선례).
     *
     * @param mealArrivals 식사 슬롯의 도착 시각(자정 기준 분). 순서는 무관하다
     */
    static int mealViolationMinutes(int[] mealArrivals) {
        if (mealArrivals.length == 0) {
            return 0;
        }
        if (mealArrivals.length == 1) {
            return nearestWindowViolation(mealArrivals[0]);
        }

        // 어느 두 끼가 점심·저녁을 차지할지 전부 따져 최소를 고른다. 끼니가 둘이면 후보가
        // 둘뿐이고, 셋 이상이어도 k(k-1) 가지라 비용이 무시할 만하다.
        //
        // 도착이 이른 쪽을 점심에 묶는 것이 항상 최적이라는 것은 증명된다 — 두 윈도우가
        // 시간축에서 분리돼 있고 위반 함수가 각각에 대해 볼록하므로 교환 논증이 성립한다.
        // 그럼에도 전부 따지는 쪽을 택한 것은, 끼니가 셋 이상일 때 "이른 두 개"를 기계적으로
        // 고르면 잘 벌어진 아침·점심·저녁이 몰아넣은 배열보다 비싸지는 역전이 생기기 때문이다.
        int best = Integer.MAX_VALUE;

        for (int lunchIndex = 0; lunchIndex < mealArrivals.length; lunchIndex++) {
            for (int dinnerIndex = 0; dinnerIndex < mealArrivals.length; dinnerIndex++) {
                if (lunchIndex == dinnerIndex) {
                    continue;
                }
                int total = LUNCH_WINDOW.violationMinutes(mealArrivals[lunchIndex])
                    + DINNER_WINDOW.violationMinutes(mealArrivals[dinnerIndex]);

                for (int i = 0; i < mealArrivals.length; i++) {
                    if (i != lunchIndex && i != dinnerIndex) {
                        total += nearestWindowViolation(mealArrivals[i]);
                    }
                }
                best = Math.min(best, total);
            }
        }
        return best;
    }

    private static int nearestWindowViolation(int arrivalMinutes) {
        return Math.min(
            LUNCH_WINDOW.violationMinutes(arrivalMinutes),
            DINNER_WINDOW.violationMinutes(arrivalMinutes));
    }

    private static int[] mealArrivalsOf(Schedule schedule, Context context) {
        int[] order = schedule.order();
        int[] arrivals = new int[order.length];
        int count = 0;

        for (int i = 0; i < order.length; i++) {
            if (context.slotTypes()[order[i]] == SlotType.MEAL) {
                arrivals[count++] = schedule.arrivalMinutes()[i];
            }
        }
        return Arrays.copyOf(arrivals, count);
    }

    /** 식사 적정 시간대. 자정 기준 분으로 표현한다. */
    private record TimeWindow(int startMinutes, int endMinutes) {

        /** 윈도우 안이면 0, 밖이면 가장 가까운 경계까지의 거리(분). */
        int violationMinutes(int arrivalMinutes) {
            return Math.max(0, startMinutes - arrivalMinutes)
                + Math.max(0, arrivalMinutes - endMinutes);
        }
    }

    private RoutedDay toRoutedDay(RouteRequest request, List<RoutePlace> places,
        Schedule schedule, int[] stayMinutes, List<RoutePlace> dropped) {

        List<RoutedPlace> routed = new ArrayList<>(schedule.order().length);
        for (int i = 0; i < schedule.order().length; i++) {
            int placeIndex = schedule.order()[i];
            routed.add(new RoutedPlace(
                places.get(placeIndex),
                toDisplayTime(schedule.arrivalMinutes()[i]),
                stayMinutes[placeIndex]));
        }

        return new RoutedDay(
            request.day(),
            toDisplayTime(schedule.arrivalMinutes()[0]),
            toDisplayTime(schedule.endMinutes()),
            routed,
            dropped);
    }

    /**
     * 두 장소 사이 이동시간(분). 항상 올림하고 고정 오버헤드를 더한다.
     *
     * <p><b>{@code 1e-9}를 빼는 이유.</b> {@code 1.0 × 60 / 15.0}은 수학적으로 정확히 4지만
     * double 연산에서는 {@code 4.000000000000001}이 나올 수 있고, 그러면 {@code Math.ceil}이
     * 5를 준다. 하루 여섯 구간이면 근거 없는 6분이 붙어 하루 초과 판정을 바꾼다.
     *
     * <p><b>거리가 0이어도 오버헤드는 붙는다.</b> 같은 건물 안 두 장소를 0분에 이동하는 것으로
     * 계산하면 안 된다 — 그게 오버헤드의 정의다(환승 대기, 주차).
     */
    static int travelMinutes(double distanceKm, TravelMode travelMode) {
        // 나눗셈보다 곱셈을 먼저 해 오차를 줄인다.
        double raw = distanceKm * 60.0 / travelMode.getEffectiveSpeedKmh();
        return (int) Math.ceil(raw - 1e-9) + travelMode.getFixedOverheadMinutes();
    }

    /**
     * 내부 계산값(자정 기준 분)을 사용자에게 보여줄 시각으로 바꾼다. 5분 단위 올림.
     *
     * <p>표시 시각은 내부 시각보다 <b>최대 4분 늦다.</b> 항상 늦는 방향이므로 사용자가 이 시각에
     * 맞춰 움직이면 계획보다 여유가 생긴다. 연속한 두 장소의 실제 간격이 최소 41분(축소된 체류
     * 36분 + 자차 오버헤드 5분)이라 <b>표시 시각이 겹치거나 역전되지는 않는다.</b>
     *
     * <p>자정을 넘긴 값은 23:55로 자른다. {@code LocalTime}은 24:00 이상을 표현할 수 없어
     * 그대로 두면 예외가 난다. 하루 최소 3개 하한 때문에 병적인 입력(늦은 시작 + 긴 체류)에서
     * 실제로 도달할 수 있는 경로다.
     */
    static LocalTime toDisplayTime(int minutes) {
        int ceiled = Math.floorDiv(minutes + DISPLAY_TIME_UNIT_MINUTES - 1, DISPLAY_TIME_UNIT_MINUTES)
            * DISPLAY_TIME_UNIT_MINUTES;

        if (ceiled >= MINUTES_PER_DAY) {
            return LocalTime.of(23, 55);
        }
        if (ceiled < 0) {
            return LocalTime.MIDNIGHT;
        }
        return LocalTime.of(ceiled / 60, ceiled % 60);
    }

    private static int toMinutes(LocalTime time) {
        return time.getHour() * 60 + time.getMinute();
    }

    private static int[] defaultStayMinutes(List<RoutePlace> places) {
        int[] stayMinutes = new int[places.size()];
        for (int i = 0; i < places.size(); i++) {
            stayMinutes[i] = places.get(i).slotType().getDefaultStayMinutes();
        }
        return stayMinutes;
    }

    private static SlotType[] slotTypesOf(List<RoutePlace> places) {
        SlotType[] slotTypes = new SlotType[places.size()];
        for (int i = 0; i < places.size(); i++) {
            slotTypes[i] = places.get(i).slotType();
        }
        return slotTypes;
    }

    /**
     * 장소 간 거리를 미리 전부 계산해 둔다.
     *
     * <p>완전탐색은 순열마다 거리를 다시 묻는데, {@code n=7}이면 haversine 호출이 30,240회다.
     * 미리 계산하면 21회로 줄어든다. 10줄이고, 벤치마크가 "완전탐색은 공짜"라는 결론을 낼 때
     * 그 숫자를 흐리지 않는다.
     */
    private static double[][] distanceMatrix(List<RoutePlace> places) {
        int n = places.size();
        double[][] distances = new double[n][n];

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                RoutePlace a = places.get(i);
                RoutePlace b = places.get(j);
                double km = GeoUtils.haversineKm(
                    a.latitude(), a.longitude(), b.latitude(), b.longitude());
                distances[i][j] = km;
                distances[j][i] = km;
            }
        }
        return distances;
    }

    private static int[] identityOrder(int size) {
        int[] order = new int[size];
        for (int i = 0; i < size; i++) {
            order[i] = i;
        }
        return order;
    }
}
