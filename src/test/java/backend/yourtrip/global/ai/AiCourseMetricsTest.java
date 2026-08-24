package backend.yourtrip.global.ai;

import static org.assertj.core.api.Assertions.assertThat;

import backend.yourtrip.global.ai.pipeline.PipelineStage;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 묻는 것은 <b>"실제로 Prometheus 로 나가는 형태가 우리가 필요한 형태인가"</b>다 (ROADMAP 7-5).
 *
 * <p><b>{@code SimpleMeterRegistry} 로는 이 질문에 답할 수 없다</b> — 그쪽은 집계 가능한 백분위를
 * 지원하지 않아 {@code publishPercentileHistogram()} 을 켜도 버킷을 만들지 않는다. 즉 목 레지스트리로
 * 단언하면 <b>설정이 빠져 있어도 테스트는 통과</b>한다. 실제 스크레이프 출력에 대고 물어야 한다.
 *
 * <p>같은 종류의 함정이 이 저장소에 이미 있었다 — 4단계 판정 11에서 스텁의 {@code Content-Type}이
 * 실제와 달라 전 호출이 실패하는데도 테스트가 통과한 사건이다.
 */
@DisplayName("AiCourseMetrics — Prometheus 노출 형태 (ROADMAP 7-5)")
class AiCourseMetricsTest {

    private PrometheusMeterRegistry registry;
    private AiCourseMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        metrics = new AiCourseMetrics(registry);
    }

    @Nested
    @DisplayName("지연은 히스토그램 버킷으로 낸다 — p95 를 계산할 수 있어야 한다")
    class LatencyHistogram {

        @Test
        @DisplayName("파이프라인 단계 지연에 le 버킷이 실린다")
        void pipelineDurationHasBuckets() {
            metrics.pipelineStage(PipelineStage.PLANNER, TimeUnit.SECONDS.toNanos(3));

            assertThat(registry.scrape())
                .as("버킷이 없으면 histogram_quantile 로 p95 를 계산할 수 없다 — 평균은 꼬리를 가린다")
                .contains("ai_course_pipeline_duration_seconds_bucket")
                .contains("stage=\"planner\"");
        }

        @Test
        @DisplayName("LLM 호출 지연에도 le 버킷이 실린다 — 5-11 도 같은 결함을 갖고 있었다")
        void llmCallHasBuckets() {
            metrics.llmCall("planner", "openai", AiCourseMetrics.LLM_OUTCOME_SUCCESS,
                TimeUnit.SECONDS.toNanos(4));

            assertThat(registry.scrape())
                .contains("ai_llm_call_seconds_bucket")
                .contains("agent=\"planner\"");
        }

        @Test
        @DisplayName("상한이 요청 예산(30초)이라 그 위는 +Inf 로 모인다 — 그것 자체가 예산 초과라는 답이다")
        void upperBoundIsTheRequestBudget() {
            metrics.pipelineStage(PipelineStage.CURATOR, TimeUnit.SECONDS.toNanos(31));

            String scrape = registry.scrape();
            assertThat(scrape).contains("ai_course_pipeline_duration_seconds_bucket");
            assertThat(scrape).contains("le=\"+Inf\"");
        }
    }

    @Nested
    @DisplayName("기동 시점 0 등록")
    class ZeroSeries {

        @Test
        @DisplayName("호출이 없어도 슬롯 집계 세 값이 시계열로 존재한다")
        void curationSlotIsRegistered() {
            String scrape = registry.scrape();

            assertThat(scrape).contains("ai_curation_slot_total");
            assertThat(scrape).contains("result=\"curator\"");
            assertThat(scrape).contains("result=\"fallback\"");
            assertThat(scrape).contains("result=\"unfilled\"");
        }
    }
}
