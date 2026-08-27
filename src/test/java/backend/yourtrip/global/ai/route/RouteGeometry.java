package backend.yourtrip.global.ai.route;

import java.util.List;

/**
 * 방문 순서가 정해진 경로의 <b>기하 지표</b> — 총 이동거리·자기교차·역행 꼭짓점.
 *
 * <p>3-7 효과 실측과 3-8 계수 스윕이 같은 자를 써야 해서 {@code RouteOptimizationEffectTest}
 * 에서 뗐다. 두 하네스가 각자 지표를 들면 "−32.5%"와 스윕 곡선의 y축이 조용히 다른 것을 재게
 * 되고, 그 어긋남은 결과 어디에도 드러나지 않는다.
 *
 * <p><b>거리는 프로덕션 {@link GeoUtils#haversineKm}를 그대로 호출한다.</b> 복제하면 재는 자와
 * 최적화기가 쓰는 자가 언젠가 어긋난다.
 */
final class RouteGeometry {

    private RouteGeometry() {
    }

    /** 위도 1도의 거리(km). 평면 투영은 교차 판정에만 쓰고 거리에는 쓰지 않는다. */
    private static final double KM_PER_LAT_DEGREE = 111.0;

    /** 적도에서 경도 1도의 거리(km). 실제로는 위도에 따라 {@code cos} 배로 줄어든다. */
    private static final double KM_PER_LON_DEGREE_AT_EQUATOR = 111.32;

    /**
     * 열린 경로의 총 이동거리. 숙소 복귀를 더하지 않는 이유는 이 서비스가 숙소를 다루지 않아
     * 복귀 지점을 알 수 없기 때문이다 — {@link RouteOptimizer}의 비용 함수도 같은 가정을 쓴다.
     */
    static double totalDistanceKm(List<RoutePlace> ordered) {
        double sum = 0.0;
        for (int i = 0; i < ordered.size() - 1; i++) {
            sum += GeoUtils.haversineKm(
                ordered.get(i).latitude(), ordered.get(i).longitude(),
                ordered.get(i + 1).latitude(), ordered.get(i + 1).longitude());
        }
        return sum;
    }

    /**
     * 경로 폴리라인이 스스로 교차하는 횟수 — <b>"지그재그"의 기하학적 정의</b>다.
     *
     * <p>인접한 두 선분은 끝점을 공유하므로 제외하고 {@code j >= i + 2}인 쌍만 본다. 임계값이
     * 없다는 것이 이 지표의 장점이다 — "얼마나 되돌아가야 역주행인가"를 정하지 않아도 된다.
     *
     * <p><b>진짜 교차(proper intersection)만 센다.</b> 끝점이 맞닿거나 선분이 한 직선 위에 겹치는
     * 퇴화 사례는 세지 않는데, 좌표가 같은 장소가 둘 있으면 선분이 점으로 줄어들어 판정 자체가
     * 무의미해지기 때문이다. 그런 경우는 거리 지표에서 0km 구간으로 이미 드러난다.
     */
    static int selfIntersections(List<RoutePlace> ordered) {
        double[][] xy = project(ordered);
        int count = 0;
        for (int i = 0; i + 1 < xy.length; i++) {
            for (int j = i + 2; j + 1 < xy.length; j++) {
                if (properlyIntersects(xy[i], xy[i + 1], xy[j], xy[j + 1])) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * 진행 방향과 반대 성분이 생기는 꼭짓점의 수.
     *
     * <p>연속 3점 {@code A→B→C}에서 {@code (B−A)·(C−B) < 0}이면 B 에서 90도 넘게 꺾여 왔던
     * 방향으로 되돌아간다는 뜻이다. <b>90도는 자의적인 임계값이 아니라 "되돌아감 성분이 0에서
     * 음수로 바뀌는 경계"</b>다.
     *
     * <p>자기교차와 함께 재는 이유는 둘이 서로를 보완하기 때문이다 — 왕복 직선 코스는 교차 없이
     * 되돌아가고, 넓게 도는 코스는 되돌아감 없이 교차할 수 있다.
     */
    static int backtrackVertices(List<RoutePlace> ordered) {
        double[][] xy = project(ordered);
        int count = 0;
        for (int i = 1; i + 1 < xy.length; i++) {
            double ux = xy[i][0] - xy[i - 1][0];
            double uy = xy[i][1] - xy[i - 1][1];
            double vx = xy[i + 1][0] - xy[i][0];
            double vy = xy[i + 1][1] - xy[i][1];
            if (ux * vx + uy * vy < 0) {
                count++;
            }
        }
        return count;
    }

    /**
     * 위경도를 km 단위 등거리 평면으로 편다. <b>교차·각도 판정에만 쓰고 거리에는 쓰지 않는다</b> —
     * 거리는 haversine 이 맡는다. 한국 도시 규모(50km 미만)에서 이 투영의 왜곡은 교차 여부를
     * 뒤집을 만한 크기가 아니다.
     */
    private static double[][] project(List<RoutePlace> ordered) {
        double meanLat = ordered.stream().mapToDouble(RoutePlace::latitude).average().orElse(0.0);
        double lonScale = KM_PER_LON_DEGREE_AT_EQUATOR * Math.cos(Math.toRadians(meanLat));

        double[][] xy = new double[ordered.size()][2];
        for (int i = 0; i < ordered.size(); i++) {
            xy[i][0] = ordered.get(i).longitude() * lonScale;
            xy[i][1] = ordered.get(i).latitude() * KM_PER_LAT_DEGREE;
        }
        return xy;
    }

    /** 네 방향값이 모두 0이 아니고 부호가 갈릴 때만 참 — 끝점 접촉·공선 겹침은 제외된다. */
    private static boolean properlyIntersects(double[] p1, double[] p2, double[] q1, double[] q2) {
        double d1 = cross(q1, q2, p1);
        double d2 = cross(q1, q2, p2);
        double d3 = cross(p1, p2, q1);
        double d4 = cross(p1, p2, q2);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static double cross(double[] a, double[] b, double[] c) {
        return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
    }

    /**
     * <b>거리만 최소화했을 때</b>의 총 이동거리 — 프로덕션과 비교하기 위한 참조값이다.
     *
     * <p>이 값이 없으면 <b>after 가 before 보다 긴 day</b>를 설명할 수 없다. {@link RouteOptimizer}가
     * 최소화하는 것은 거리가 아니라 {@code 거리 + 식사 시간창 위반 + 하루 초과} 비용이라 그런
     * day 는 나올 수 있고 그 자체는 설계대로지만, 참조값이 없으면 "얼마를 양보했는가"가 추측으로
     * 남는다. 나란히 두면 {@code after - shortest}가 <b>식사 시간창을 맞추려고 지불한 거리</b>로
     * 읽힌다.
     *
     * <p>프로덕션을 부르지 않고 직접 순열을 도는 이유는 벌점을 끄는 스위치가
     * {@link RouteOptimizer}에 없었기 때문이다. 3-8이 식사 벌점 이음매를 열면서 사정이 달라졌고,
     * 그래서 이 값은 이제 <b>교차검증 대상</b>이기도 하다 — 식사 벌점 0으로 돌린 프로덕션의
     * 거리와 여기서 나온 값이 일치해야 한다(3-8 판정 기준 4). 두 경로가 서로를 검산한다.
     *
     * <p>Planner 가 슬롯을 7개로 clamp 하므로 5,040 순열이 최대이고, 여기서 필요한 것은
     * 최적성뿐이라 완전탐색이 그대로 답이다.
     */
    static double shortestPathKm(List<RoutePlace> places) {
        int n = places.size();
        double[][] distances = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                distances[i][j] = GeoUtils.haversineKm(
                    places.get(i).latitude(), places.get(i).longitude(),
                    places.get(j).latitude(), places.get(j).longitude());
            }
        }
        return permuteShortest(new int[n], new boolean[n], 0, 0.0, distances, Double.MAX_VALUE);
    }

    private static double permuteShortest(int[] order, boolean[] used, int depth, double sum,
        double[][] distances, double best) {

        if (depth == order.length) {
            return Math.min(best, sum);
        }
        for (int candidate = 0; candidate < order.length; candidate++) {
            if (used[candidate]) {
                continue;
            }
            double next = depth == 0 ? sum : sum + distances[order[depth - 1]][candidate];
            // 부분합이 이미 최선을 넘었으면 그 가지는 답이 될 수 없다.
            if (next >= best) {
                continue;
            }
            used[candidate] = true;
            order[depth] = candidate;
            best = permuteShortest(order, used, depth + 1, next, distances, best);
            used[candidate] = false;
        }
        return best;
    }
}
