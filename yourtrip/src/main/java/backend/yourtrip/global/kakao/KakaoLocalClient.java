package backend.yourtrip.global.kakao;

import backend.yourtrip.global.kakao.dto.KakaoSearchResponse;
import backend.yourtrip.global.kakao.dto.KakaoSearchResponse.Document;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

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
            .block();
    }

    /**
     * AI가 준 placeName + placeLocation을 기반으로 가장 그럴듯한 Kakao 장소 하나를 고른다.
     */
    public KakaoSearchResponse.Document findBestPlace(String placeName, String placeLocation) {
        String keyword = placeLocation + " " + placeName;
        KakaoSearchResponse response = searchPlace(keyword, 5); //최대 5개의 후보 장소 가져오기

        List<Document> docs = response.documents();
        if (docs == null || docs.isEmpty()) { //적절한 장소가 검색되지 않으면 null 리턴
            return null;
        }

        // 점수가 가장 높은 장소 선택
        return docs.stream()
            .max(Comparator.comparingInt(doc -> score(doc, placeName, placeLocation)))
            .orElse(null);
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
