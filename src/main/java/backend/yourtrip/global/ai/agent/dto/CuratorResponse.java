package backend.yourtrip.global.ai.agent.dto;

import java.util.List;

/**
 * Curator LLM 응답을 그대로 받는 DTO (ROADMAP 6-4). <b>{@code CuratedDay}와 일부러 분리했다.</b>
 *
 * <h2>enum 이 될 값을 {@code String}으로 받는다</h2>
 * {@code source}·{@code slotType}을 enum 으로 바로 역직렬화하면 목록 밖 값 하나에 Jackson 이 예외를
 * 던져 <b>day 하나가 통째로 죽는다.</b> 6-7 은 그런 값을 버리는 대신 {@code SUGGESTED}로 강등하기로
 * 했는데(이름이 실존할 수 있다), 역직렬화 단계에서 죽으면 그 결정을 실행할 기회 자체가 없다.
 *
 * <h2>좌표·id·URL 필드가 없는 것이 핵심이다</h2>
 * "{@code SEEDED}·{@code LISTED}는 재검증을 생략한다"의 전제는 목록 항목의 좌표·주소를 <b>코드가</b>
 * 승계하는 것이지 LLM 이 옮겨 적는 것이 아니다. 스키마에 그 필드를 두는 순간 모델이 값을 지어낼
 * 자리가 생기고, <b>그 값은 아무도 검증하지 않는다.</b>
 */
public record CuratorResponse(Integer day, List<Slot> slots) {

    /**
     * @param slotIndex 채울 자리의 번호. 프롬프트가 준 값을 그대로 돌려받는다
     * @param slotType  그 자리의 종류. <b>정본은 Planner 다</b> — 이 값은 대조용이고, 어긋나면
     *                  그 자리의 선택을 {@code SUGGESTED}로 강등한다(6-7)
     */
    public record Slot(Integer slotIndex, String slotType, List<Choice> choices) {
    }

    /**
     * @param listIndex 목록에서 고른 위치(0부터). {@code SUGGESTED}면 null 이다.
     *                  <b>상호명이 아니라 인덱스로 참조하게 하는 이유</b>는, 선별 과제라도 LLM 출력인
     *                  이상 "목록에서 골랐다"고 주장하며 목록에 없는 이름을 내놓을 수 있기 때문이다
     * @param placeName 모델이 적은 상호명. 목록 항목과 대조하는 데 쓰이고, {@code SUGGESTED}일 때만
     *                  실제 검색어가 된다
     */
    public record Choice(String source, Integer listIndex, String placeName) {
    }
}
