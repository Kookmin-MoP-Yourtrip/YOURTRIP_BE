package backend.yourtrip.global.ai.prompt;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 렌더링 대상 프롬프트 파일의 목록 (ROADMAP 6-1).
 *
 * <p><b>리소스 이름을 문자열이 아니라 enum으로 두는 이유.</b> 경로를 호출부마다 문자열로 적으면
 * 오타가 <b>기동도 컴파일도 아닌 첫 호출 시점</b>에야 드러난다. enum이면 오타가 컴파일 오류가 되고,
 * 상수 목록 자체가 "이 애플리케이션이 요구하는 프롬프트 파일 전량"이라는 문서가 된다 —
 * {@code AiConfig}가 {@code @ConfigurationPropertiesScan}을 전역으로 켜지 않고 바인딩 대상을
 * 명시 등록하는 것과 같은 태도다.
 *
 * <p><b>프롬프트를 자바 텍스트블록이 아니라 파일로 빼는 근거</b>는 설계 문서(프롬프트 전략)에 있다.
 * 요지만 옮기면 셋이다 — ① 프롬프트에 JSON이 들어가는 한 {@code \"} 이스케이프 지옥이 계속된다
 * (구 {@code GeminiService}가 그 상태였다) ② 프롬프트 diff가 자바 로직 diff와 섞이지 않아
 * {@code git blame}이 유의미해진다(프롬프트 튜닝은 로직 변경보다 훨씬 잦다) ③ 텍스트블록의 유일한
 * 실질 장점인 컴파일타임 안전성은 {@link PromptLoader}의 eager 로드가 대신 확보한다.
 *
 * <p>흔히 드는 <b>"재컴파일 없이 바꿀 수 있다"는 근거는 쓰지 않는다</b> — 이 파일들은 jar 안에
 * 패키징되므로 성립하지 않는다.
 */
@Getter
@RequiredArgsConstructor
public enum PromptTemplate {

    /** Planner의 고정 규칙. 가변 데이터가 없어 향후 컨텍스트 캐싱 대상이 자명해진다. */
    PLANNER_SYSTEM("prompts/planner-system.md"),

    /** Planner의 가변 데이터 — 여행지·일수·키워드뿐이다. */
    PLANNER_USER("prompts/planner-user.md"),

    /** Curator의 고정 규칙 — 후보 목록에서 고르는 선별 규칙. */
    CURATOR_SYSTEM("prompts/curator-system.md"),

    /** Curator의 가변 데이터 — day 하나의 권역·테마·슬롯 구성·후보 목록. */
    CURATOR_USER("prompts/curator-user.md");

    private final String path;
}
