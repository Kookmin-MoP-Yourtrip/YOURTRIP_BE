package backend.yourtrip.global.ai.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * 프롬프트·응답 스키마 리소스를 읽고 플레이스홀더를 채운다 (ROADMAP 6-1).
 *
 * <h2>eager 로드 — 파일이 없으면 기동이 실패한다</h2>
 * {@link PromptTemplate}·{@link ResponseSchema}의 <b>전 상수를 조립 시점에</b> 읽는다. 지연 로드로
 * 두면 프롬프트 파일 누락이 <b>배포 뒤 첫 AI 코스 생성 요청</b>에서야 500으로 드러난다. 텍스트블록을
 * 버리면서 잃는 유일한 실질 장점(컴파일타임 안전성)을 여기서 되찾는 것이 이 클래스의 존재 이유다.
 *
 * <p><b>{@code @PostConstruct}가 아니라 생성자에서 읽는다.</b> 로드맵 6-1의 표기는
 * {@code @PostConstruct}였지만, 5단계 {@code AiCourseMetrics}가 0 등록을 생성자로 옮기며 세운 판단을
 * 그대로 따른다 — <b>이건 컨테이너가 얹어 주는 부가 기능이 아니라 이 클래스가 스스로 지키는 계약</b>이다.
 * 라이프사이클에 걸어 두면 스프링 없이 조립하는 단위 테스트에서만 조용히 빠져, "테스트는 통과하는데
 * 실제로는 비어 있는" 어긋남이 생긴다. 어느 쪽이든 빈 생성이 실패하므로 기동 실패라는 효과는 같다.
 *
 * <h2>플레이스홀더는 명명 기반 {@code {{name}}}</h2>
 * 현재 {@code GeminiService}는 {@code .formatted(location, days, keywordsJson, days)}처럼 <b>같은 값을
 * 두 번 넘기고 순서에 의존</b>한다 — 프롬프트를 편집하다 문단 하나만 옮겨도 조용히 깨진다. 명명 기반은
 * 그 결합을 끊고, 아래 세 규칙이 나머지 실수를 막는다.
 * <ul>
 *   <li><b>표기가 틀린 자리는 기동 시점에 잡는다</b> — {@code {{ location }}}처럼 패턴을 벗어난 표기는
 *       치환되지 않고 그대로 LLM에 실려 나간다. 오류 없이 품질만 떨어뜨리는 가장 나쁜 종류의 실패라
 *       런타임이 아니라 로드 시점에 막는다</li>
 *   <li><b>채울 값이 없으면 예외</b> — 호출부가 키를 빠뜨린 경우다</li>
 *   <li><b>치환값 안의 {@code {{...}}}는 다시 치환하지 않는다</b> — 후보 목록·사용자 키워드가 값으로
 *       들어오는데, 그 안의 문자열이 템플릿으로 해석되면 입력이 프롬프트를 바꾸게 된다</li>
 * </ul>
 */
@Component
@Slf4j
public class PromptLoader {

    /** {@code {{name}}} — 이름은 영문으로 시작하고 영숫자·밑줄만 쓴다. */
    private static final Pattern PLACEHOLDER = Pattern.compile("[{][{]([A-Za-z][A-Za-z0-9_]*)[}][}]");

    /** 유효한 플레이스홀더를 걷어낸 뒤에도 남으면 안 되는 표식. */
    private static final String PLACEHOLDER_MARK = "{{";

    private final Map<PromptTemplate, String> templates = new EnumMap<>(PromptTemplate.class);
    private final Map<ResponseSchema, String> schemas = new EnumMap<>(ResponseSchema.class);

    public PromptLoader() {
        for (PromptTemplate template : PromptTemplate.values()) {
            String raw = read(template.getPath());
            requireWellFormedPlaceholders(template, raw);
            templates.put(template, raw);
        }
        for (ResponseSchema schema : ResponseSchema.values()) {
            schemas.put(schema, read(schema.getPath()));
        }
        log.info("프롬프트 {}개, 응답 스키마 {}개를 로드했다", templates.size(), schemas.size());
    }

    /**
     * 플레이스홀더를 채운 프롬프트.
     *
     * @throws IllegalArgumentException 템플릿이 요구하는 이름이 {@code values}에 없는 경우
     */
    public String render(PromptTemplate template, Map<String, String> values) {
        String raw = templates.get(template);
        Map<String, String> given = values == null ? Map.of() : values;

        Matcher matcher = PLACEHOLDER.matcher(raw);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = given.get(name);
            if (value == null) {
                throw new IllegalArgumentException(
                    "%s 의 플레이스홀더 '%s' 에 넣을 값이 없다. 전달된 키: %s"
                        .formatted(template.getPath(), name, given.keySet()));
            }
            // quoteReplacement 가 없으면 값 안의 $1 · \ 가 역참조로 해석된다 — 후보 목록처럼
            // 외부에서 온 문자열이 값으로 들어오는 이상 이건 가정이 아니라 언젠가 일어나는 일이다.
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        // appendReplacement 는 이미 치환한 부분을 다시 훑지 않는다 — 값 안의 {{...}} 가 템플릿으로
        // 해석되지 않는 것은 이 API 의 성질이지 우리가 따로 막아 주는 것이 아니다.
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    /** {@code LlmCall.responseJsonSchema}에 그대로 실리는 JSON 문자열. */
    public String schema(ResponseSchema schema) {
        return schemas.get(schema);
    }

    /**
     * 표기가 틀린 플레이스홀더가 없는가.
     *
     * <p><b>렌더링 결과가 아니라 템플릿 원문을 검사한다.</b> 결과에 남은 {@code {{}}는 템플릿의
     * 오타일 수도 있고 치환값에서 온 것일 수도 있어 구별할 수 없지만, 원문에서는 명백히 오타다.
     */
    private static void requireWellFormedPlaceholders(PromptTemplate template, String raw) {
        String stripped = PLACEHOLDER.matcher(raw).replaceAll("");
        if (stripped.contains(PLACEHOLDER_MARK)) {
            throw new IllegalStateException(
                "%s 에 표기가 틀린 플레이스홀더가 있다 — '{{이름}}' 형식이어야 한다(공백·특수문자 불가)"
                    .formatted(template.getPath()));
        }
    }

    private static String read(String path) {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IllegalStateException("프롬프트 리소스가 없다: " + path + " — 기동을 중단한다");
        }
        try (InputStream in = resource.getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("프롬프트 리소스를 읽지 못했다: " + path, e);
        }
    }
}
