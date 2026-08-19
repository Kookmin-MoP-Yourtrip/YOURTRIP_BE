package backend.yourtrip.global.kakao;

import backend.yourtrip.global.ai.candidate.PlaceNameNormalizer;
import backend.yourtrip.global.common.ApiFailureCause;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
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
            // 아래 findBestPlace의 catch를 빠져나가지만, HttpClient가 끊으면
            // WebClientRequestException으로 올라와 정상적으로 잡힌다.
            .block();
    }

    /**
     * AI가 준 placeName + placeLocation을 기반으로 가장 그럴듯한 Kakao 장소 하나를 고른다.
     * 이름이 일치하는 후보가 하나도 없으면 매칭 실패로 보고 null을 반환한다.
     *
     * <p><b>{@link #lookupBestPlace} 위에 얇게 얹혀 있다</b>(ROADMAP 4-8). 검색·이름 게이트·점수
     * 로직을 두 벌로 만들지 않기 위해서다 — 두 벌이면 1-2에서 잡은 이름 게이트 결함이 한쪽에만
     * 고쳐지는 날이 온다. 이 메서드의 시그니처와 예외 계약은 그대로 두어 {@code MyCourseServiceImpl}의
     * 동작이 변하지 않는다.
     */
    public KakaoSearchResponse.Document findBestPlace(String placeName, String placeLocation) {
        return switch (lookupBestPlace(placeName, placeLocation)) {
            case PlaceLookup.Found found -> found.document();
            case PlaceLookup.NoResult ignored -> null;
            // 실패를 값으로 받았지만 이 경로의 계약은 예외다. 기존 호출부는 부분 실패를 다룰
            // 준비가 되어 있지 않다(장소 하나를 못 붙이면 코스 저장 자체가 무의미하다).
            case PlaceLookup.Failed ignored -> throw new BusinessException(
                MyCourseErrorCode.KAKAO_API_FAILED);
        };
    }

    /**
     * {@link #findBestPlace}와 같은 검색·판정을 하되 <b>결과를 값으로</b> 돌려준다 (ROADMAP 4-8).
     *
     * <p>왜 값이어야 하는지는 {@link PlaceLookup}에 적혀 있다 — 지오코딩 캐스케이드가 무결과와
     * 호출 실패를 정반대로 다뤄야 하기 때문이다.
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
            .max(Comparator.comparingInt(doc -> score(doc, placeName, placeLocation))));
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
                // findBestPlace가 이 상황에서 null을 돌려주던 동작을 바꾸지 않기 위해서다.
                return new PlaceLookup.NoResult();
            }

            List<Document> docs = response.documents();
            if (docs == null || docs.isEmpty()) { //적절한 장소가 검색되지 않으면 무결과
                return new PlaceLookup.NoResult();
            }

            return pick.apply(docs)
                .<PlaceLookup>map(PlaceLookup.Found::new)
                .orElseGet(PlaceLookup.NoResult::new);
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
     */
    private static String buildKeyword(String placeName, String placeLocation) {
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
     * <p>이 게이트가 필요한 이유는 {@code score()}의 가점 구조 때문이다. 검색 키워드가
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

    private int score(Document doc, String placeName, String placeLocation) {
        int score = 0;

        String name = doc.place_name() != null ? doc.place_name() : "";
        String addr = (doc.road_address_name() != null && !doc.road_address_name().isBlank())
            ? doc.road_address_name()
            : (doc.address_name() != null ? doc.address_name() : "");

        // 1) 이름 유사도 (단순 contains 기반)
        if (!placeName.isBlank()) {
            String lowerName = name.toLowerCase();
            String lowerInput = placeName.toLowerCase();
            if (lowerName.contains(lowerInput) || lowerInput.contains(lowerName)) {
                score += 5;
            }
        }

        // 2) 주소 유사도 (placeLocation 문자열이 카카오 주소(addr)에 포함되면 +3)
        if (placeLocation != null && !placeLocation.isBlank()) {
            String lowerKakaoAddr = addr.toLowerCase();
            String lowerLocation = placeLocation.toLowerCase();

            if (lowerKakaoAddr.contains(lowerLocation)) {
                score += 3;
            }
        }

        // 3) 카테고리 그룹이 관광/카페/음식점이면 가산점
        String groupCode = doc.category_group_code();
        if (groupCode != null) {
            if (groupCode.equals("FD6") || groupCode.equals("CE7") || groupCode.equals("AT4")) {
                score += 2;
            }
        }

        return score;
    }


}
