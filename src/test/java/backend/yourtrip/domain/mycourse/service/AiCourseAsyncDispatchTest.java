package backend.yourtrip.domain.mycourse.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import backend.yourtrip.domain.user.entity.User;
import backend.yourtrip.domain.user.repository.UserRepository;
import backend.yourtrip.global.ai.LlmWorkLease;
import backend.yourtrip.global.ai.pipeline.AiCourseDraft;
import backend.yourtrip.global.ai.pipeline.AiCoursePipeline;
import backend.yourtrip.global.ai.pipeline.CourseBrief;
import backend.yourtrip.global.exception.BusinessException;
import backend.yourtrip.global.exception.errorCode.AiCourseErrorCode;
import backend.yourtrip.global.jwt.JwtTokenProvider;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * AI 코스 생성 요청이 전용 풀에서 돌고 응답까지 이어지는지 실제 Tomcat 위에서 확인한다.
 *
 * <p>{@code MockMvc} 가 아니라 실제 서버를 띄우는 이유: 확인하려는 것이 서블릿 컨테이너의 ASYNC 재디스패치와
 * 보안 필터의 상호작용이다. 재디스패치에서 {@code JwtAuthenticationFilter}가 빠져 컨텍스트가 비므로,
 * {@code SecurityConfig}의 ASYNC 허용이 없으면 저장까지 끝난 요청이 403 으로 나간다(규칙을 뺀 대조 실행에서
 * 재디스패치를 타는 세 테스트가 모두 실패했다). 근거: {@code docs/tasks/ai-bulkhead-loadtest/measurement.md} 8절
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "ai.course.admission.max-work-units=4",
    "ai.course.admission.max-requests=1"
})
@ActiveProfiles("test")
class AiCourseAsyncDispatchTest {

    private static final Long USER_ID = 1L;
    private static final Long COURSE_ID = 77L;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AiCoursePipeline aiCoursePipeline;

    @MockitoBean
    private AiCoursePersister aiCoursePersister;

    @BeforeEach
    void setUp() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(
            User.builder().id(USER_ID).email("async@test.local").build()));
        given(aiCoursePersister.save(any(), any(), any(), any())).willReturn(COURSE_ID);
    }

    @Test
    @DisplayName("파이프라인은 전용 풀에서 돌고, 인증된 사용자로 저장되며, 응답은 201 로 끝난다")
    void completesOnDedicatedPoolWithAuthenticatedUser() {
        AtomicReference<String> pipelineThread = new AtomicReference<>();
        given(aiCoursePipeline.generate(any(CourseBrief.class), any(LlmWorkLease.class)))
            .willAnswer(invocation -> {
                pipelineThread.set(Thread.currentThread().getName());
                return new AiCourseDraft("제목", "컨셉", List.of());
            });

        ResponseEntity<Map> response = post(authorized());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsValue(COURSE_ID.intValue());
        assertThat(pipelineThread.get()).startsWith("ai-request-");
        verify(aiCoursePersister).save(any(), any(), any(), eq(USER_ID));
    }

    @Test
    @DisplayName("토큰이 없으면 첫 디스패치에서 막혀 파이프라인을 부르지 않는다 — ASYNC 허용이 인증을 우회하지 않는다")
    void anonymousRequestIsBlockedBeforePipeline() {
        ResponseEntity<Map> response = post(new HttpHeaders());

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        verify(aiCoursePipeline, never()).generate(any(CourseBrief.class), any(LlmWorkLease.class));
    }

    @Test
    @DisplayName("전용 풀에서 난 업무 예외가 재디스패치 뒤 원래 상태 코드로 나간다")
    void businessExceptionKeepsItsStatus() {
        given(aiCoursePipeline.generate(any(CourseBrief.class), any(LlmWorkLease.class)))
            .willThrow(new BusinessException(AiCourseErrorCode.AI_GROUNDING_FAILED));

        ResponseEntity<Map> response = post(authorized());

        assertThat(response.getStatusCode()).isEqualTo(AiCourseErrorCode.AI_GROUNDING_FAILED.getStatus());
    }

    @Test
    @DisplayName("입구에서 거절된 요청은 전용 풀을 거치지 않고 429 와 Retry-After 로 나간다")
    void admissionRejectionKeepsRetryAfter() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        given(aiCoursePipeline.generate(any(CourseBrief.class), any(LlmWorkLease.class)))
            .willAnswer(invocation -> {
                entered.countDown();
                release.await(10, TimeUnit.SECONDS);
                return new AiCourseDraft("제목", "컨셉", List.of());
            });

        CompletableFuture<ResponseEntity<Map>> first =
            CompletableFuture.supplyAsync(() -> post(authorized()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        try {
            ResponseEntity<Map> second = post(authorized());
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(second.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        } finally {
            release.countDown();
        }
        assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map> post(HttpHeaders headers) {
        Map<String, Object> body = Map.of(
            "location", "경주",
            "startDate", LocalDate.of(2030, 1, 1).toString(),
            "endDate", LocalDate.of(2030, 1, 3).toString(),
            "keywords", List.of("WALK"));
        return rest.postForEntity("/api/my-courses/ai", new HttpEntity<>(body, headers), Map.class);
    }

    private HttpHeaders authorized() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.createAccessToken(USER_ID, "async@test.local"));
        return headers;
    }
}
