package backend.yourtrip.global.ai;

import backend.yourtrip.global.ai.exception.LlmParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * LLM 응답 텍스트를 도메인 타입으로 옮긴다.
 *
 * <p><b>이 클래스는 재시도하지 않는다.</b> LLM 포트 설계의 표는 의미 계층 재시도를 이 클래스의
 * 책임으로 적었지만, 재시도하려면 LLM을 다시 불러야 하고 그건 파서가 할 수 있는 일이 아니다.
 * 그래서 파서는 <b>순수 변환</b>만 맡고, "실패하면 보정 지시를 붙여 1회 더" 부분은 어댑터가
 * 오케스트레이션한다. 그 대가로 이 클래스는 외부 의존이 없어 완전히 결정론적으로 테스트된다.
 *
 * <p>구조화 출력(strict json_schema)을 쓰면 여기서 실패할 일이 거의 없어야 정상이다. 그런데
 * <b>실측은 그 전제에 단서를 단다</b> — Gemini baseline의 파싱 실패 5건은 전부 응답 절단이었고,
 * 절단은 스키마 강제로 막히지 않는다({@code BASELINE-ARTIFACT-ANALYSIS.md} 판정 3). 그래서
 * 절단은 파싱을 시도하기 <b>전에</b> 어댑터가 걸러내고, 여기까지 온 실패는 진짜 스키마 위반이다.
 */
@Component
@RequiredArgsConstructor
public class LlmResponseParser {

    private final ObjectMapper objectMapper;

    /**
     * @param agentName 실패 시 어느 에이전트였는지 남기기 위해서만 쓴다
     * @param rawText   LLM이 돌려준 응답 본문
     * @throws LlmParseException 역직렬화 실패. 원문을 예외에 실어 보낸다 — Gemini baseline에서
     *                           수치만 남고 원문이 없어 실패 원인 규명이 몇 달 막혔던 전례가 있다
     */
    public <T> T parse(String agentName, String rawText, Class<T> responseType) {
        if (rawText == null || rawText.isBlank()) {
            throw new LlmParseException(agentName, rawText,
                new IllegalStateException("응답 본문이 비어 있다"));
        }
        try {
            return objectMapper.readValue(rawText, responseType);
        } catch (Exception e) {
            throw new LlmParseException(agentName, rawText, e);
        }
    }
}
