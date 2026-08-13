package backend.yourtrip.global.kakao;

import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.MyCourseErrorCode;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
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
            .block(Duration.ofSeconds(20)); //20초 타임아웃
    }

    /**
     * AI가 준 placeName + placeLocation을 기반으로 가장 그럴듯한 Kakao 장소 하나를 고른다.
     * 이름이 일치하는 후보가 하나도 없으면 매칭 실패로 보고 null을 반환한다.
     */
    public KakaoSearchResponse.Document findBestPlace(String placeName, String placeLocation) {
        String keyword = placeLocation + " " + placeName;
        try {
            KakaoSearchResponse response = searchPlace(keyword, 5); //최대 5개의 후보 장소 가져오기

            if (response == null) {
                return null;
            }

            List<Document> docs = response.documents();
            if (docs == null || docs.isEmpty()) { //적절한 장소가 검색되지 않으면 null 리턴
                return null;
            }

            // 이름이 일치하는 후보만 남긴 뒤 그중 점수가 가장 높은 장소를 고른다.
            // 필터가 max()보다 앞에 있어야 한다 — 순서가 반대면 이름이 전혀 안 맞는 후보가
            // 주소·카테고리 가점만으로 1등이 되어 그대로 선택된다.
            return docs.stream()
                .filter(doc -> nameMatches(doc, placeName))
                .max(Comparator.comparingInt(doc -> score(doc, placeName, placeLocation)))
                .orElse(null);
        } catch (WebClientResponseException e) {
            log.error("Kakao search API error: {} - {}", e.getStatusCode(),
                e.getResponseBodyAsString());
            throw new BusinessException(MyCourseErrorCode.KAKAO_API_FAILED);
        }
    }

    /**
     * 이름 비교에서 무시할 문자들. 공백·중점·문장부호가 실측 거짓 음성의 원인이었다
     * ("동궁과 월지" vs "동궁과월지", "허균·허난설헌 기념공원" vs "허균허난설헌기념공원").
     */
    private static final Pattern NAME_NOISE = Pattern.compile("[\\s·・.,\\-_()\\[\\]/&|]+");

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        // Locale.ROOT — 기본 로케일에 따라 결과가 달라지지 않게 한다(터키어의 I 처리 등).
        return NAME_NOISE.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
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
        String aiName = normalize(placeName);
        String kakaoName = normalize(doc.place_name());

        if (aiName.isEmpty() || kakaoName.isEmpty()) {
            return false; //비교할 이름이 없으면 검증되지 않은 것으로 본다
        }
        return kakaoName.contains(aiName) || aiName.contains(kakaoName);
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
