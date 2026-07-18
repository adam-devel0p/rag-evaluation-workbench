package com.rageval.workbench.evaluation.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The full LLM evaluation path end to end — run orchestration, all eight LLM evaluators, evidence
 * and version metadata persistence — against the deterministic fake judge, so the default suite
 * exercises everything except the actual model call without any Ollama.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Import({PostgresTestcontainersConfiguration.class, LlmEvaluationIntegrationTest.FakeJudgeConfiguration.class})
@TestPropertySource(properties = "spring.ai.ollama.base-url=http://localhost:1")
class LlmEvaluationIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeJudgeConfiguration {

        @Bean
        @Primary
        JudgeClient fakeJudgeClient() {
            return FakeJudgeClient.withBenignDefaults();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private EvaluationRunRepository runRepository;

    @Autowired
    private EvaluationMetricResultRepository metricResultRepository;

    @Test
    void allEightLlmEvaluatorsProduceStructuredEvidenceBackedResults() throws Exception {
        RestClient client = RestClient.builder().baseUrl("http://localhost:" + port).build();
        UUID runId = LlmIntegrationTestSupport.runDemoScenario(client, jsonMapper, port);
        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.COMPLETED);

        Map<String, EvaluationMetricResult> llmResults = metricResultRepository.findByRunId(runId).stream()
                .filter(result -> result.getEvaluatorKey().startsWith("llm-"))
                .collect(Collectors.toMap(EvaluationMetricResult::getEvaluatorKey, Function.identity()));

        assertThat(llmResults.keySet()).containsExactlyInAnyOrder(
                ClaimExtractionEvaluator.KEY,
                GroundednessEvaluator.KEY,
                ReferenceCorrectnessEvaluator.KEY,
                CompletenessEvaluator.KEY,
                AnswerRelevanceEvaluator.KEY,
                ContextRelevanceEvaluator.KEY,
                SemanticRefusalEvaluator.KEY,
                ContradictionEvaluator.KEY);

        for (EvaluationMetricResult result : llmResults.values()) {
            assertThat(result.getStatus()).as(result.getEvaluatorKey()).isEqualTo(MetricResultStatus.COMPLETED);
            assertThat(result.getPassed()).as(result.getEvaluatorKey()).isTrue();
            assertThat(result.getEvaluatorType()).isEqualTo(EvaluatorType.LLM);
            assertThat(result.getEvaluatorModel()).isEqualTo(FakeJudgeClient.MODEL_NAME);
            assertThat(result.getPromptVersion()).isNotBlank();
            assertThat(result.getExplanation()).isNotBlank();
            assertThat(result.getEvidence()).as(result.getEvaluatorKey()).isNotEmpty();
        }

        // Spot checks: the structured evidence spec §12/§13 requires actually round-tripped.
        assertThat(llmResults.get(GroundednessEvaluator.KEY).getEvidence())
                .anySatisfy(evidence -> assertThat(evidence.type())
                        .isEqualTo(GroundednessEvaluator.CLAIM_GROUNDING_EVIDENCE));
        assertThat(llmResults.get(ContextRelevanceEvaluator.KEY).getEvidence())
                .anySatisfy(evidence -> assertThat(evidence.type())
                        .isEqualTo(ContextRelevanceEvaluator.CHUNK_DECISION_EVIDENCE));
        assertThat(llmResults.get(ClaimExtractionEvaluator.KEY).getEvidence())
                .anySatisfy(evidence -> assertThat(evidence.type())
                        .isEqualTo(ClaimExtractionEvaluator.CLAIM_EVIDENCE));

        List<EvaluationMetricResult> deterministic = metricResultRepository.findByRunId(runId).stream()
                .filter(result -> !result.getEvaluatorKey().startsWith("llm-"))
                .toList();
        assertThat(deterministic).isNotEmpty();
    }

    private RunStatus awaitTerminal(UUID runId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(120));
        while (Instant.now().isBefore(deadline)) {
            RunStatus status = runRepository.findById(runId).orElseThrow().getStatus();
            if (status != RunStatus.CREATED && status != RunStatus.QUEUED && status != RunStatus.RUNNING) {
                return status;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Run " + runId + " did not reach a terminal status within 120s");
    }
}
