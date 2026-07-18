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
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec §12's graceful-degradation contract, exercised end to end with Ollama entirely
 * unreachable: deterministic evaluators keep working, every LLM evaluator result becomes
 * metric-status FAILED (never a chatbot failure — the test case still passes on its mandatory
 * deterministic metrics), and the health endpoint identifies evaluator-model unavailability.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Import(PostgresTestcontainersConfiguration.class)
@TestPropertySource(properties = {
    "spring.ai.ollama.base-url=http://localhost:1",
    "management.endpoint.health.show-details=always"
})
class LlmDegradationIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private EvaluationRunRepository runRepository;

    @Autowired
    private EvaluationMetricResultRepository metricResultRepository;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void llmEvaluatorsDegradeToFailedMetricsWhileDeterministicOnesKeepWorking() throws Exception {
        RestClient client = client();
        UUID runId = LlmIntegrationTestSupport.runDemoScenario(client, jsonMapper, port);
        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.COMPLETED);

        Map<Boolean, List<EvaluationMetricResult>> resultsByLlm = metricResultRepository.findByRunId(runId).stream()
                .collect(Collectors.partitioningBy(result -> result.getEvaluatorKey().startsWith("llm-")));

        List<EvaluationMetricResult> llmResults = resultsByLlm.get(true);
        assertThat(llmResults).hasSize(8);
        for (EvaluationMetricResult result : llmResults) {
            assertThat(result.getStatus()).as(result.getEvaluatorKey()).isEqualTo(MetricResultStatus.FAILED);
            assertThat(result.getPassed()).as(result.getEvaluatorKey()).isNull();
            assertThat(result.getErrorMessage()).as(result.getEvaluatorKey()).contains("JudgeUnavailableException");
            assertThat(result.getEvaluatorType()).isEqualTo(EvaluatorType.LLM);
            assertThat(result.getEvaluatorModel()).isNotBlank();
            assertThat(result.isRequiresHumanReview()).isTrue();
        }

        List<EvaluationMetricResult> deterministicResults = resultsByLlm.get(false);
        assertThat(deterministicResults).isNotEmpty();
        assertThat(deterministicResults)
                .allSatisfy(result -> assertThat(result.getStatus()).isNotEqualTo(MetricResultStatus.FAILED));

        // Never a chatbot failure: the test-case outcome rests on the mandatory deterministic
        // metrics, which all passed for the demo happy-path scenario.
        JsonNode summary = client.get().uri("/api/v1/evaluation-runs/{id}/summary", runId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(summary.at("/testCaseOutcomes/passed").asInt()).isEqualTo(1);
        assertThat(summary.at("/testCaseOutcomes/failed").asInt()).isZero();
    }

    @Test
    void healthDetailsIdentifyEvaluatorModelUnavailability() {
        String body = client().get().uri("/actuator/health")
                .exchange((request, response) -> new String(response.getBody().readAllBytes()));
        JsonNode health = jsonMapper.readTree(body);
        assertThat(health.at("/components/evaluatorModel/status").asString()).isEqualTo("DOWN");
        assertThat(health.at("/components/evaluatorModel/details/reason").asString())
                .contains("Ollama is unreachable");
        assertThat(health.at("/components/evaluatorModel/details/evaluatorModel").asString()).isNotBlank();
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
