package com.rageval.workbench.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
 * The Phase 4 checkpoint (spec §32): a useful, evidence-backed result set with zero Ollama
 * dependency, proven end to end — import dataset → configure demo target → run with repetitions →
 * deterministic evaluation → failure classification → aggregated summary, all through the real
 * API against the real orchestrator. The slow-response delay (2500 ms) deliberately exceeds the
 * default 2000 ms latency maximum so the latency evaluator has something to catch.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Import(PostgresTestcontainersConfiguration.class)
@TestPropertySource(properties = "rag-evaluation.demo.slow-response-delay-millis=2500")
class EvaluationVerticalSliceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private EvaluationRunRepository runRepository;

    @Autowired
    private TestExecutionRepository executionRepository;

    @Autowired
    private EvaluationTestCaseRepository testCaseRepository;

    @Autowired
    private EvaluationMetricResultRepository metricResultRepository;

    @Autowired
    private EvaluationFindingRepository findingRepository;

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void fullVerticalSliceProducesEvidenceBackedResultsWithoutOllama() throws Exception {
        RestClient client = client();

        // 1. Import the shipped demo dataset through the API and mark it READY.
        String datasetJson = Files.readString(Path.of("examples/demo-dataset.json"));
        JsonNode dataset = client.post().uri("/api/v1/datasets/import/json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(datasetJson)
                .retrieve()
                .body(JsonNode.class);
        String datasetId = dataset.at("/id").asString();
        client.post().uri("/api/v1/datasets/{id}/mark-ready", datasetId).retrieve().toBodilessEntity();

        // 2. Configure the demo target as a real STANDARD_HTTP target on this server's port.
        String targetId = createDemoTarget(client);

        // 3. Create a run with 2 repetitions (so DEMO-014's rotating answers become material
        //    inconsistency) and a tag-scoped citation requirement for the missing-citation case.
        ObjectNode evaluatorConfiguration = jsonMapper.createObjectNode();
        ObjectNode citationPolicyByTag = jsonMapper.createObjectNode();
        citationPolicyByTag.put("missing-citation", "REQUIRED");
        evaluatorConfiguration.set("citationPolicyByTag", citationPolicyByTag);
        ObjectNode runRequest = jsonMapper.createObjectNode();
        runRequest.put("targetSystemId", targetId);
        runRequest.put("datasetId", datasetId);
        runRequest.put("name", "Phase 4 vertical slice");
        runRequest.put("repetitionsPerTestCase", 2);
        runRequest.set("evaluatorConfiguration", evaluatorConfiguration);
        JsonNode created = client.post().uri("/api/v1/evaluation-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(runRequest))
                .retrieve()
                .body(JsonNode.class);
        UUID runId = UUID.fromString(created.at("/id").asString());
        client.post().uri("/api/v1/evaluation-runs/{id}/start", runId).retrieve().toBodilessEntity();

        // DEMO-013 answers HTTP 500, so the run is PARTIALLY_COMPLETED by design.
        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.PARTIALLY_COMPLETED);

        Map<UUID, String> externalIdByExecution = externalIdByExecutionId(runId);
        Map<String, List<EvaluationMetricResult>> resultsByExternalId =
                metricResultRepository.findByRunId(runId).stream()
                        .collect(Collectors.groupingBy(
                                result -> externalIdByExecution.get(result.getTestExecutionId())));

        // Every terminal execution was evaluated: 14 test cases x 2 repetitions.
        assertThat(resultsByExternalId.values().stream().mapToInt(List::size).sum())
                .isGreaterThanOrEqualTo(14 * 2 * 5);

        // 4. Metric-level spot checks per deliberate scenario.
        assertThat(passedOf(resultsByExternalId, "DEMO-001", "required-facts")).containsOnly(true);
        assertThat(passedOf(resultsByExternalId, "DEMO-001", "expected-documents")).containsOnly(true);

        assertThat(passedOf(resultsByExternalId, "DEMO-002", "required-facts")).containsOnly(false);
        assertThat(passedOf(resultsByExternalId, "DEMO-002", "forbidden-claims")).containsOnly(false);

        assertThat(passedOf(resultsByExternalId, "DEMO-003", "expected-documents")).containsOnly(false);

        assertThat(passedOf(resultsByExternalId, "DEMO-006", "required-facts")).containsOnly(false);

        assertThat(passedOf(resultsByExternalId, "DEMO-007", "refusal")).containsOnly(true);
        assertThat(passedOf(resultsByExternalId, "DEMO-007", "answerability")).containsOnly(true);

        assertThat(passedOf(resultsByExternalId, "DEMO-008", "refusal")).containsOnly(false);

        assertThat(passedOf(resultsByExternalId, "DEMO-009", "citation-presence")).containsOnly(false);

        assertThat(passedOf(resultsByExternalId, "DEMO-010", "citation-retrieval-consistency"))
                .containsOnly(false);

        assertThat(passedOf(resultsByExternalId, "DEMO-012", "latency")).containsOnly(false);

        // The HTTP-500 case: transport records a real chatbot failure, answer-quality evaluators
        // are SKIPPED (missing data) — an evaluator is never blamed and never guesses.
        List<EvaluationMetricResult> httpError = resultsByExternalId.get("DEMO-013");
        assertThat(httpError.stream().filter(result -> result.getEvaluatorKey().equals("transport")))
                .allSatisfy(result -> {
                    assertThat(result.getStatus()).isEqualTo(MetricResultStatus.COMPLETED);
                    assertThat(result.getPassed()).isFalse();
                });
        assertThat(httpError.stream().filter(result -> result.getEvaluatorKey().equals("required-facts")))
                .allSatisfy(result -> assertThat(result.getStatus()).isEqualTo(MetricResultStatus.SKIPPED));

        // No evaluator errors anywhere: deterministic evaluation ran clean without Ollama.
        assertThat(metricResultRepository.findByRunId(runId))
                .noneMatch(result -> result.getStatus() == MetricResultStatus.FAILED);

        // 5. Findings: every deliberate scenario produced its evidence-backed category.
        Map<String, List<EvaluationFinding>> findingsByExternalId =
                findingRepository.findByEvaluationRunId(runId).stream()
                        .collect(Collectors.groupingBy(
                                finding -> externalIdByExecution.get(finding.getTestExecutionId())));
        assertThat(categoriesOf(findingsByExternalId, "DEMO-002")).contains(FailureCategory.RETRIEVAL_RANKING);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-003")).contains(FailureCategory.RETRIEVAL_MISS);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-004")).contains(FailureCategory.RETRIEVAL_RANKING);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-006")).contains(FailureCategory.INCOMPLETE_ANSWER);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-008"))
                .contains(FailureCategory.FAILED_REFUSAL, FailureCategory.UNSAFE_DISCLOSURE);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-009")).contains(FailureCategory.MISSING_CITATION);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-010")).contains(FailureCategory.INCORRECT_CITATION);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-012")).contains(FailureCategory.HIGH_LATENCY);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-013")).contains(FailureCategory.TARGET_SYSTEM_ERROR);
        assertThat(categoriesOf(findingsByExternalId, "DEMO-014"))
                .contains(FailureCategory.INCONSISTENT_RESPONSE);

        // Findings carry the spec §16 rule outputs.
        assertThat(findingRepository.findByEvaluationRunId(runId)).allSatisfy(finding -> {
            assertThat(finding.getSummary()).isNotBlank();
            assertThat(finding.getSupportingMetricResultIds()).isNotEmpty();
            assertThat(finding.getEvidence()).isNotEmpty();
        });

        // 6. The findings API serves them.
        JsonNode findingsPage = client.get()
                .uri("/api/v1/evaluation-runs/{id}/findings?size=100", runId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(findingsPage.at("/totalElements").asInt()).isGreaterThan(0);

        // 7. The metrics API pages metric results.
        JsonNode metricsPage = client.get()
                .uri("/api/v1/evaluation-runs/{id}/metrics?size=20", runId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(metricsPage.at("/content").size()).isEqualTo(20);
        assertThat(metricsPage.at("/totalElements").asLong())
                .isEqualTo(metricResultRepository.findByRunId(runId).size());

        // 8. The aggregated summary: transparent counts, no evaluator error in any denominator.
        JsonNode summary = client.get()
                .uri("/api/v1/evaluation-runs/{id}/summary", runId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(summary.at("/executions/total").asInt()).isEqualTo(28);
        assertThat(summary.at("/executions/failed").asInt()).isEqualTo(2);
        assertThat(summary.at("/testCaseOutcomes/total").asInt()).isEqualTo(14);
        // Deterministically expected outcomes: DEMO-001/005/007/009/010/012 pass their mandatory
        // metrics; DEMO-002/003/004/006/008/011/013/014 fail them.
        assertThat(summary.at("/testCaseOutcomes/passed").asInt()).isEqualTo(6);
        assertThat(summary.at("/testCaseOutcomes/failed").asInt()).isEqualTo(8);
        assertThat(summary.at("/testCaseOutcomes/inconclusive").asInt()).isZero();
        assertThat(summary.at("/testCaseOutcomes/passRate").asDouble()).isEqualTo(6.0 / 14.0);
        assertThat(summary.at("/latency/sampleCount").asInt()).isGreaterThan(0);
        assertThat(summary.at("/latency/maximum").asLong()).isGreaterThanOrEqualTo(2500L);
        assertThat(summary.at("/retrieval/evaluatedCount").asInt()).isGreaterThan(0);
        assertThat(summary.at("/retrieval/meanReciprocalRank").isNumber()).isTrue();
        assertThat(summary.at("/targetErrorRate").asDouble()).isEqualTo(2.0 / 28.0);
        assertThat(summary.at("/consistency/comparableTestCases").asInt()).isGreaterThan(0);
        assertThat(summary.at("/humanReviewBacklog").asLong()).isGreaterThan(0);
        // A non-mandatory metric failure (DEMO-012's latency) does not fail the test case — but
        // its finding still exists: useful signal without a misleading overall verdict.
        JsonNode demo12 = findTestCase(summary, "DEMO-012");
        assertThat(demo12.at("/outcome").asString()).isEqualTo("PASSED");
        JsonNode demo13 = findTestCase(summary, "DEMO-013");
        assertThat(demo13.at("/outcome").asString()).isEqualTo("FAILED");

        // 9. Pass rate by severity/tag maps exist with real entries.
        assertThat(summary.at("/passRateBySeverity/CRITICAL").isMissingNode()).isFalse();
        assertThat(summary.at("/passRateByTag/demo/passed").asInt()).isEqualTo(6);
    }

    private String createDemoTarget(RestClient client) {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        template.put("conversationId", "{{conversationId}}");
        ObjectNode targetRequest = jsonMapper.createObjectNode();
        targetRequest.put("name", "Demo Target (evaluation slice)");
        targetRequest.put("adapterType", "STANDARD_HTTP");
        targetRequest.put("endpointUrl", "http://localhost:" + port + "/demo-target/chat");
        targetRequest.put("httpMethod", "POST");
        targetRequest.set("requestTemplate", template);
        targetRequest.put("answerJsonPointer", "/answer");
        targetRequest.put("citationsJsonPointer", "/citations");
        targetRequest.put("retrievedChunksJsonPointer", "/retrievedChunks");
        targetRequest.put("modelJsonPointer", "/model");
        targetRequest.put("enabled", true);
        targetRequest.put("timeoutMilliseconds", 10000);
        targetRequest.put("connectTimeoutMilliseconds", 2000);
        targetRequest.put("followRedirects", false);
        targetRequest.put("maximumConcurrency", 2);
        targetRequest.put("authenticationType", "NONE");
        JsonNode target = client.post().uri("/api/v1/targets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(targetRequest))
                .retrieve()
                .body(JsonNode.class);
        return target.at("/id").asString();
    }

    private List<Boolean> passedOf(
            Map<String, List<EvaluationMetricResult>> resultsByExternalId, String externalId, String evaluatorKey) {
        return resultsByExternalId.getOrDefault(externalId, List.of()).stream()
                .filter(result -> result.getEvaluatorKey().equals(evaluatorKey)
                        && result.getStatus() == MetricResultStatus.COMPLETED)
                .map(EvaluationMetricResult::getPassed)
                .toList();
    }

    private List<FailureCategory> categoriesOf(
            Map<String, List<EvaluationFinding>> findingsByExternalId, String externalId) {
        return findingsByExternalId.getOrDefault(externalId, List.of()).stream()
                .map(EvaluationFinding::getCategory)
                .toList();
    }

    private JsonNode findTestCase(JsonNode summary, String externalId) {
        for (JsonNode testCase : summary.at("/testCases")) {
            if (externalId.equals(testCase.at("/externalId").asString())) {
                return testCase;
            }
        }
        throw new AssertionError("Test case " + externalId + " missing from summary");
    }

    private Map<UUID, String> externalIdByExecutionId(UUID runId) {
        EvaluationRun run = runRepository.findById(runId).orElseThrow();
        Map<UUID, String> externalIdsByTestCaseId = testCaseRepository.findByDatasetId(run.getDatasetId()).stream()
                .collect(Collectors.toMap(testCase -> testCase.getId(), testCase -> testCase.getExternalId()));
        Map<UUID, String> result = new HashMap<>();
        for (TestExecution execution : executionRepository.findByEvaluationRunId(runId)) {
            result.put(execution.getId(), externalIdsByTestCaseId.get(execution.getTestCaseId()));
        }
        return result;
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
