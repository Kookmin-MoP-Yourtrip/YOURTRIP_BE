package backend.yourtrip.global.gemini.service;

import backend.yourtrip.domain.uploadcourse.entity.enums.KeywordType;
import com.google.genai.Client;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GeminiService {

    private final Client geminiClient;

    public String generateAICourse(String location, int days, List<KeywordType> keywords) {
        String prompt = """
             당신은 한국인 여행자를 위한 전문 여행 코스 플래너 AI입니다.
             사용자의 선호도와 여행 정보를 바탕으로, 하루 단위로 잘 쪼개진 동선 최적화 여행 일정을 설계하세요.

             [입력 정보]
             - 여행지(도시/지역): %s
             - 여행 기간(일 수): %d
             - 여행 선호도 키워드(카테고리별 JSON): %s


            [여행 선호도 키워드 사용 방법]
            - 제공된 JSON은 다음과 같은 형식을 가집니다.
              {
                "travelMode": ["뚜벅이"],
                "companionType": ["연인"],
                "mood": ["힐링", "감성"],
                "duration": ["1박2일"],
                "budget": ["가성비"]
              }
            - 각 필드에 따라 다음과 같이 일정을 조정하세요.
              - travelMode: 이동 방식과 동선을 조정 (뚜벅이 → 대중교통/도보 위주, 자차 → 주차 가능한 장소 중심)
              - companionType: 함께 가는 사람에 맞게 분위기·난이도 조정
              - mood: 장소의 분위기(힐링/감성/액티비티 등)와 코스의 템포 조정
              - budget: 무료/저렴/프리미엄 장소의 비율과 식당 가격대 조정
            - 키워드가 여러 개일 수 있으므로, 가능한 한 모든 키워드를 동시에 만족하는 코스를 설계하려고 노력하세요.

             [요구 사항]
              1. 아래 JSON 스키마에 맞게만 응답하고, 자연어 설명 문장은 절대 포함하지 마세요.

              {
                "title": "여행지 테마 (예: 서울 감성 여행)",
                "daySchedules": [
                  {
                    "day": 1,
                    "places": [
                      {
                        "placeName": "장소 이름",
                        "startTime": "HH:mm 형식 (예: \\"10:30\\")",
                        "placeLocation": "지역명을 포함한 최대한 정확한 주소 (예: \\"경북 경주시 포석로 1080\\")",
                        "memo": "해당 장소를 가야 하는 이유, 팁 등을 간단히 한 문장으로 (선택 사항으로 없으면 null로 표시해도 됩니다)"
                      }
                    ]
                  }
                ]
              }
             
             2. "daySchedules" 배열의 길이는 반드시 %d가 되어야 합니다. (각 day는 1일부터 시작해 1씩 증가)
             3. 각 day마다 최소 3개, 최대 6개의 장소를 추천하세요.
             4. 동선은 가능하면 역주행하지 않도록, 서로 가까운 장소끼리 같은 day에 배치하세요. 완벽히 정확할 필요는 없지만, 사용자 입장에서 무리 없는 동선을 목표로 합니다.
             5. "placeLocation"은 최대한 구체적으로 작성해 주세요. (예: "서울특별시 종로구 사직로 161" vs "서울 종로구")
             6. "startTime"은 각 장소 방문이 하루 일정 내에 자연스럽게 이어지도록 설정하세요. (예: 아침 9시부터 저녁 8시 사이)
             7. travelMode가 "뚜벅이"이면 도보/대중교통 위주의 일정으로 구성하고, "자차"이면 자차 이동이 자연스러운 일정과 장소를 우선합니다.
             8. companionType에 따라 추천 톤과 장소 선택을 조정하세요.
               - "연인": 데이트에 어울리는 분위기 좋은 카페, 야경, 로맨틱한 동선
               - "친구": 활발한 액티비티, 맛집, 카페, 놀거리 위주
               - "가족": 이동이 너무 빡세지 않게, 어린이나 부모님을 고려한 무난한 일정
               - "혼자": 혼자 이동·식사를 해도 어색하지 않은 장소, 카페, 전시, 산책 코스 등
             9. mood, budget 키워드도 반영해 장소의 분위기와 가격대를 조정하세요.
                - 예: "힐링"+"감성"이면 여유로운 카페/산책/전망 좋은 스팟 위주
                - "액티비티"면 체험형, 활동적인 장소를 포함
                - "가성비"면 과도하게 비싼 레스토랑보다는 가성비 좋은 맛집 위주
                - "프리미엄"이면 뷰/서비스가 좋은 레스토랑이나 고급스러운 장소 포함
            10. 응답은 반드시 유효한 JSON 형식이어야 하며, ```json 같은 코드 블록 표시는 절대 사용하지 마세요.
            11. 모든 텍스트(제목, 메모 등)는 자연스러운 한국어로 작성하세요.
            12. 스키마에 정의되지 않은 필드는 절대 추가하지 마세요.
            13. 각 daySchedules[*].places[*].startTime은 문자열 비교 기준으로 오름차순이 되도록 설정하세요. (예: "09:00" → "11:30" → "14:00" → "19:00")
            14. 각 day마다 최소 1개 이상의 "식사(맛집)"에 해당하는 장소를 포함해 주세요. 예를 들어 점심/저녁 시간대(12:00~14:00, 18:00~20:00)에 맛집을 배치합니다.
            15. 같은 day 안에서 startTime끼리 겹치지 않도록 하세요.
            16. "placeLocation"은 가능한 한 실제로 존재할 법한 도로명 주소 또는 지번 주소 형식으로 작성하세요.
                  (예: "서울특별시 종로구 사직로 161" 또는 "경상북도 경주시 황남동 123-1")
                "placeName"과 "placeLocation"만으로도 지도 검색 API에서 해당 장소를 찾을 수 있도록, 장소 이름과 주소를 구체적으로 작성하세요.
            17. "memo"를 제외한 모든 필드에는 null 및 빈 문자열이 허용되지 않습니다.
            18. 모든 문자열 값(장소 이름, 주소, 메모 등) 안에서는 큰따옴표(")를 사용하지 말고, 필요한 경우 작은따옴표(')를 사용하세요.

             [출력 형식 예시]
             {
               "title": "서울 북촌 한옥마을 산책 투어",
               "daySchedules": [
                 {
                   "day": 1,
                   "places": [
                     {
                       "placeName": "경복궁",
                       "startTime": "09:30",
                       "placeLocation": "서울특별시 종로구 사직로 161",
                       "memo": "조선 왕조의 역사를 느낄 수 있는 대표적인 궁궐입니다."
                     },
                     {
                       "placeName": "북촌 한옥마을",
                       "startTime": "11:30",
                       "placeLocation": "서울특별시 종로구 계동길 37",
                       "memo": "전통 한옥의 아름다움을 감상하며 산책하기 좋은 곳입니다."
                     }
                   ]
                 }
               ]
             }
            """.formatted(
            location,
            days,
            KeywordType.buildKeywordsJson(keywords),
            days);

        GenerateContentResponse response = geminiClient.models.generateContent("gemini-2.5-flash",
            prompt, getGenerationConfig());

        return response.text();
    }

    private GenerateContentConfig getGenerationConfig() {
        return GenerateContentConfig.builder()
            .temperature(0.3f)
            .topK(40f)
            .topP(0.85f)
            .maxOutputTokens(3072)
            .candidateCount(1)
            .responseMimeType("application/json")
            .build();
    }

}
