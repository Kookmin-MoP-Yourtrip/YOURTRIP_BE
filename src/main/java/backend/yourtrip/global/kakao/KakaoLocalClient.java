package backend.yourtrip.global.kakao;

import backend.yourtrip.global.ai.candidate.PlaceNameNormalizer;
import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

@Component
@RequiredArgsConstructor
@Slf4j
public class KakaoLocalClient {

    private final WebClient kakaoWebClient;

    public KakaoSearchResponse searchPlace(String keyword, int size) {
        return kakaoWebClient.get()
            .uri(uriBuilder -> uriBuilder
                .path("/v2/local/search/keyword.json")
                .queryParam("query", keyword)
                .queryParam("size", size)
                .build())
            .retrieve()
            .bodyToMono(KakaoSearchResponse.class)
            // 타임아웃은 KakaoConfig의 HttpClient(connect 2초 / response 3초)가 담당한다.
            // block(Duration)으로 제한하면 초과 시 IllegalStateException이 던져져
            // 아래 lookup()의 catch를 빠져나가지만, HttpClient가 끊으면
            // WebClientRequestException으로 올라와 정상적으로 잡힌다.
            .block();
    }

    /**
     * 이름 게이트를 건 검색 1회. <b>결과를 값으로</b> 돌려준다 (ROADMAP 4-8).
     *
     * <p>왜 값이어야 하는지는 {@link PlaceLookup}에 적혀 있다 — 지오코딩 캐스케이드가 무결과와
     * 호출 실패를 정반대로 다뤄야 하기 때문이다.
     *
     * <p><b>예전에는 이 위에 {@code findBestPlace}가 얹혀 있었다</b> — 무결과를 {@code null}로,
     * 호출 실패를 {@code BusinessException}으로 번역해 {@code MyCourseServiceImpl}의 옛 계약을
     * 지키던 얇은 껍질이다. 8-1이 그 경로를 파이프라인 호출로 교체하면서 호출부가 사라져 지웠다.
     *
     * @param placeName     찾을 장소 이름. <b>이름 게이트의 기준</b>이라 비면 항상 무결과가 된다
     * @param placeLocation 앞에 붙일 지역 접두사. 비어 있으면 {@code placeName}만으로 검색한다
     */
    public PlaceLookup lookupBestPlace(String placeName, String placeLocation) {
        return lookup(buildKeyword(placeName, placeLocation), docs -> docs.stream()
            // 이름이 일치하는 후보만 남긴 뒤 그중 점수가 가장 높은 장소를 고른다.
            // 필터가 max()보다 앞에 있어야 한다 — 순서가 반대면 이름이 전혀 안 맞는 후보가
            // 주소·카테고리 가점만으로 1등이 되어 그대로 선택된다.
            .filter(doc -> nameMatches(doc, placeName))
            .max(bestCandidateOrder(placeName, placeLocation)));
    }

    /**
     * 게이트를 통과한 후보들의 우선순위. <b>부속 POI를 뒤로 미는 것이 1순위 키다</b> (이슈 #164).
     *
     * <h2>왜 점수가 아니라 별도 키인가</h2>
     * {@code 해운대시장 공영주차장}·{@code 환선굴휴게실가든}처럼 본체 이름을 통째로 품은 부속
     * 시설은 <b>이름 가점(+5)을 본체와 똑같이 받고</b> 업종 덕에 카테고리 가점(+2)까지 붙어
     * 본체보다 높은 점수가 나온다. 실제로 {@code 환선굴}(8점)이 {@code 환선굴휴게실가든}(10점)에게
     * 졌다. <b>점수 안에서는 해결되지 않는 역전</b>이라 순위 키를 하나 위에 얹는다.
     *
     * <p>{@link PlaceMatchScorer#score}에 감점을 넣지 않은 이유도 여기 적어 둔다. 그 점수는
     * 환각률 하네스의 <b>층화 추출 축</b>이라(그 클래스 javadoc의 경고) 값이 바뀌면 측정 간 구간
     * 기록이 어긋난다. 순위 키를 밖에 두면 점수 함수는 한 글자도 바뀌지 않는다.
     *
     * <h2>탈락이 아니라 강등이다 — 손실이 구조적으로 0이다</h2>
     * 게이트에서 걸러내지 않으므로 <b>부속밖에 없으면 현행과 똑같이 그것을 고른다.</b> 공주
     * {@code 계룡산 동학사}가 실제로 그렇다 — 게이트를 통과한 셋이 자동차야영장·펜션·오토캠핑장
     * 전부 부속이었다. 탈락시켰다면 그 장소를 통째로 잃는다.
     *
     * <p>후보 덤프 실측(389행 기준 29건, 파이프라인 525행 기준 19건)에서 선택이 바뀐 건은 전부
     * 본체 쪽으로 갔다({@code 스타벅스 경주대릉원점} → {@code 대릉원},
     * {@code 리정원 경의선숲길 대흥점} → {@code 경의선숲길}).
     *
     * <p><b>동률이면 카카오 순위가 남는다.</b> {@code Stream.max}는 비교가 클 때만 교체하므로
     * 부속 여부도 점수도 같으면 앞선 후보가 유지된다 — 이 변경 전과 같은 성질이다.
     */
    private static Comparator<Document> bestCandidateOrder(String placeName,
        String placeLocation) {
        return Comparator
            .comparingInt((Document doc) ->
                PlaceNameNormalizer.isSubordinateName(doc.place_name(), placeName) ? 0 : 1)
            .thenComparingInt(doc -> PlaceMatchScorer.score(doc, placeName, placeLocation));
    }

    /**
     * 이름 게이트 <b>없이</b> 카카오가 1등으로 준 장소를 그대로 돌려준다 (ROADMAP 4-8).
     *
     * <h2>게이트를 빼는 것이 왜 안전한가</h2>
     * 이름 게이트는 <b>LLM이 지어낸 이름</b>을 좌표로 굳히지 않기 위한 장치다(설계 "area → 좌표":
     * *"Planner가 없는 랜드마크를 지어내도 카카오가 못 찾으면 다음 단계로 넘어가니 환각이 파이프라인에
     * 박히지 않는다"*). 그런데 지오코딩 캐스케이드의 <b>마지막 단계는 사용자가 입력한 여행지</b>를
     * 그대로 던진다 — 막을 환각이 없다.
     *
     * <h2>오히려 게이트가 해를 끼치는 자리다</h2>
     * {@code "순천시"}로 검색해 {@code "순천만국가정원"}이 1등으로 오면 두 이름은 서로를 포함하지
     * 않아 게이트에서 탈락하고, <b>캐스케이드 전체가 무결과로 끝나 그 day의 TourAPI가 통째로
     * 건너뛰어진다.</b> 하필 이런 표기 불일치가 잦은 곳이 지방 소도시인데, 4-10이 측정한 대로
     * <b>무인지 지역일수록 파라메트릭이 약해</b> TourAPI가 가장 필요한 곳이 바로 거기다.
     * 마지막 안전망이 가장 필요한 지역에서 먼저 끊어지는 셈이라 게이트를 걷어낸다.
     *
     * <p>대신 점수 계산도 하지 않는다 — 이름을 안 보는 마당에 이름 가점이 의미가 없고, 남는 것은
     * 카카오 자신의 관련도 순위가 가장 나은 신호다.
     */
    public PlaceLookup lookupFirstPlace(String keyword) {
        return lookup(keyword == null ? "" : keyword.strip(),
            docs -> Optional.of(docs.get(0)));
    }

    /**
     * 검색 1회 + 후보 선택. 두 공개 메서드가 <b>선택 규칙만</b> 다르고 나머지를 공유한다 —
     * 장애 처리가 두 벌이 되면 한쪽에만 고쳐지는 날이 온다.
     */
    private PlaceLookup lookup(String keyword, Function<List<Document>, Optional<Document>> pick) {
        if (keyword.isBlank()) {
            // 물어볼 말이 없으면 호출하지 않는다. 캐스케이드에서 anchor 가 비는 경우가 실제로 있다
            // (6-3이 "anchor 가 비면 area 텍스트로 대체"한다고 적어 둔 그 경로다).
            return new PlaceLookup.NoResult();
        }

        try {
            KakaoSearchResponse response = searchPlace(keyword, 5); //최대 5개의 후보 장소 가져오기

            if (response == null) {
                // 200인데 본문이 비어 온 경우. MALFORMED로 볼 여지도 있으나 무결과로 둔다 —
                // "물어봤는데 아무것도 없었다"에 가깝지 "물어보지 못했다"가 아니기 때문이다.
                return new PlaceLookup.NoResult();
            }

            List<Document> docs = response.documents();
            if (docs == null || docs.isEmpty()) { //적절한 장소가 검색되지 않으면 무결과
                return new PlaceLookup.NoResult();
            }

            // 여기까지 왔다면 문서는 있었다. 그런데도 picker 가 아무것도 못 고르면 그건
            // "카카오에 없다"가 아니라 "우리가 이름 게이트로 걸렀다"는 뜻이다 — 세탁 위험
            // 구간이라 순수 환각과 갈라 기록한다(5-6).
            return pick.apply(docs)
                .<PlaceLookup>map(PlaceLookup.Found::new)
                .orElseGet(() -> new PlaceLookup.NameMismatch(docs.get(0).place_name()));
        } catch (WebClientResponseException e) {
            ApiFailureCause cause = classify(e);
            log.error("Kakao search API error({}): {} - {}", cause, e.getStatusCode(),
                e.getResponseBodyAsString());
            return new PlaceLookup.Failed(cause, e.getStatusCode().toString());
        } catch (WebClientException e) {
            // 타임아웃·커넥션 실패·풀 고갈은 WebClientResponseException이 아니라
            // WebClientRequestException으로 올라온다. 이걸 잡지 않으면 GlobalExceptionHandler에
            // 핸들러가 없어 원시 500이 나간다.
            log.error("Kakao search API request failed: {}", e.getMessage());
            return new PlaceLookup.Failed(ApiFailureCause.TRANSPORT_ERROR, e.getMessage());
        } catch (RuntimeException e) {
            // 200인데 본문이 스키마와 다른 경우(역직렬화 실패). 예전에는 이것만 원시 500으로
            // 새어 나갔다 — 다른 두 실패와 같은 계약으로 맞춘다.
            log.error("Kakao search API response parse failed: {}", e.getMessage());
            return new PlaceLookup.Failed(ApiFailureCause.MALFORMED, e.getMessage());
        }
    }

    /**
     * 검색 키워드 조립. 지역 접두사가 있으면 {@code "{지역} {장소}"}가 된다.
     *
     * <p>접두사가 비었을 때 {@code " 경주시"}처럼 앞에 공백이 붙지 않게 갈라 둔다 —
     * 캐스케이드의 마지막 단계는 {@code location} 하나만 던지기 때문이다.
     *
     * <p><b>package-private인 이유</b>: 이슈 #164의 후보 덤프 프로브가 같은 키워드로 검색해야
     * 한다. 프로브가 조립을 복제하면 그쪽 덤프와 프로덕션 검색이 다른 질문을 던지게 되고,
     * 그 덤프 위에서 고른 규칙은 <b>실제로 오는 후보와 무관한 근거</b>가 된다.
     */
    static String buildKeyword(String placeName, String placeLocation) {
        String name = placeName == null ? "" : placeName;
        if (placeLocation == null || placeLocation.isBlank()) {
            return name.strip();
        }
        return placeLocation + " " + name;
    }

    /** 사유 분류는 {@code NaverLocalClient}와 같다. 어휘를 공유하는 만큼 판정도 어긋나면 안 된다. */
    private static ApiFailureCause classify(WebClientResponseException e) {
        int status = e.getStatusCode().value();
        if (status == 429) {
            return ApiFailureCause.QUOTA_EXCEEDED;
        }
        if (status == 401 || status == 403) {
            return ApiFailureCause.UNAUTHORIZED;
        }
        return ApiFailureCause.HTTP_ERROR;
    }

    /**
     * AI가 준 장소명과 카카오 후보의 상호명이 같은 곳을 가리키는지 판정한다.
     *
     * <p>이 게이트가 필요한 이유는 {@link PlaceMatchScorer}의 가점 구조 때문이다. 검색 키워드가
     * "지역명 + 장소명"이라 주소 일치(+3)가 거의 자동으로 붙고 음식점·카페면 카테고리(+2)도
     * 자동이라, <b>이름이 하나도 안 맞아도 5점이 나온다.</b> 실측에서 5~7점 구간의 31%가
     * 오매칭이었던 반면 3점 구간은 표본 전부가 정답이었다 — 점수가 정확도와 단조 관계가
     * 아니므로 총점 하한선으로는 걸러낼 수 없고, 이름 일치를 별도 조건으로 두어야 한다.
     * 근거: docs/tasks/ai-course-create/BASELINE-ARTIFACT-ANALYSIS.md 판정 1·2
     */
    private boolean nameMatches(Document doc, String placeName) {
        // 정규화·포함 판정은 PlaceNameNormalizer 가 갖는다 — 4-5의 후보 dedupe 가 같은 판단을
        // 해야 하는데, 두 벌이면 한쪽만 고쳐져 "검증은 같은 장소, dedupe 는 다른 장소"가 된다.
        return PlaceNameNormalizer.similar(doc.place_name(), placeName);
    }
}
