package backend.yourtrip.global.ai.agent.dto;

import java.util.List;

/**
 * Planner LLM 응답을 그대로 받는 DTO (ROADMAP 6-2). <b>{@code PlannerPlan}과 일부러 분리했다.</b>
 *
 * <h2>왜 도메인 record 로 직접 역직렬화하지 않는가</h2>
 * {@code slots}를 {@code SlotType} enum 으로 바로 받으면 <b>목록 밖 값 하나에 Jackson 이 예외를
 * 던진다.</b> 그러면 어댑터의 의미 재시도가 한 번 더 돌고, 그래도 같은 값이 나오면 요청이 죽는다 —
 * 6-3 이 "LLM 을 다시 부르지 않고 코드로 보정한다"고 정한 것과 정면으로 충돌한다.
 *
 * <p>그래서 <b>enum 이 될 값만 {@code String}으로 받는다.</b> 나머지(정수·문자열)는 그대로 둔다 —
 * 스키마가 형식을 강제하고, 형식이 어긋난 정수는 애초에 보정할 수 있는 성질의 값이 아니다.
 *
 * <p>스키마에 {@code enum} 제약을 <b>넣는 것과 이 결정은 모순되지 않는다</b>. 제약은 모델이 맞는 값을
 * 내도록 돕는 장치이고, 이 DTO 는 그 장치가 실패했을 때의 방어다. 둘 다 있어야 "대부분 맞고, 틀려도
 * 안 죽는다"가 된다.
 *
 * @param days 필드 이름이 요청의 "여행 일수"와 겹치지만 여기서는 <b>day 계획의 목록</b>이다.
 *             프롬프트·스키마와 이름을 맞추는 쪽을 택했다 — 모델이 보는 이름과 코드가 어긋나면
 *             프롬프트를 고칠 때 스키마를 함께 고쳐야 하는 것을 놓치기 쉽다
 */
public record PlannerResponse(String title, String concept, List<Day> days) {

    /**
     * @param day          며칠째인지. <b>이 값은 신뢰하지 않는다</b> — 6-3 이 위치 기준으로 다시 매긴다
     * @param dayStartTime {@code HH:mm}. 파싱은 6-3 이 하고 실패하면 기본값으로 떨어뜨린다
     * @param slots        {@code SlotType} 이름. 위 설명대로 {@code String}으로 받는다
     */
    public record Day(Integer day, String area, String anchor, String theme, String dayStartTime,
                      List<String> slots) {
    }
}
