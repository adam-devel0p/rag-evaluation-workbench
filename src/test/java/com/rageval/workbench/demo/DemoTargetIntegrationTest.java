package com.rageval.workbench.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
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
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Full vertical slice against the real demo target (spec §27): imports the shipped
 * examples/demo-dataset.json through the API, points a real STANDARD_HTTP target at
 * {@code /demo-target/chat} on this test's own server port, runs the orchestrator for real, and
 * asserts each deliberate scenario landed in the persisted executions the way Phase 4's
 * evaluators will need it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Import(PostgresTestcontainersConfiguration.class)
@org.springframework.test.context.TestPropertySource(
        properties = "rag-evaluation.demo.slow-response-delay-millis=400")
class DemoTargetIntegrationTest {

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

    private RestClient client() {
        return RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void demoRunExercisesEveryScenarioEndToEnd() throws Exception {
        RestClient client = client();

        // 1. Import the shipped example dataset and mark it READY.
        String datasetJson = Files.readString(Path.of("examples/demo-dataset.json"));
        JsonNode dataset = client.post().uri("/api/v1/datasets/import/json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(datasetJson)
                .retrieve()
                .body(JsonNode.class);
        String datasetId = dataset.at("/id").asString();
        client.post().uri("/api/v1/datasets/{id}/mark-ready", datasetId).retrieve().toBodilessEntity();

        // 2. Register a real HTTP target pointing at this server's demo endpoint.
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        template.put("conversationId", "{{conversationId}}");
        ObjectNode targetRequest = jsonMapper.createObjectNode();
        targetRequest.put("name", "Demo Target");
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
        ObjectNode responseMapping = jsonMapper.createObjectNode();
        responseMapping.put("inputTokensJsonPointer", "/usage/inputTokens");
        responseMapping.put("outputTokensJsonPointer", "/usage/outputTokens");
        targetRequest.set("responseMapping", responseMapping);
        JsonNode target = client.post().uri("/api/v1/targets")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(targetRequest))
                .retrieve()
                .body(JsonNode.class);
        String targetId = target.at("/id").asString();

        // 3. Create and start a run (1 repetition keeps DEMO-014 deterministic: first answer is correct).
        ObjectNode runRequest = jsonMapper.createObjectNode();
        runRequest.put("targetSystemId", targetId);
        runRequest.put("datasetId", datasetId);
        runRequest.put("name", "Demo scenario run");
        runRequest.put("repetitionsPerTestCase", 1);
        JsonNode created = client.post().uri("/api/v1/evaluation-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(runRequest))
                .retrieve()
                .body(JsonNode.class);
        UUID runId = UUID.fromString(created.at("/id").asString());
        client.post().uri("/api/v1/evaluation-runs/{id}/start", runId).retrieve().toBodilessEntity();

        RunStatus terminal = awaitTerminal(runId);
        // DEMO-013 deliberately answers HTTP 500, so the run must be PARTIALLY_COMPLETED — never
        // COMPLETED (data would be wrong) and never FAILED (13 of 14 scenarios succeed).
        assertThat(terminal).isEqualTo(RunStatus.PARTIALLY_COMPLETED);

        Map<String, TestExecution> byExternalId = executionsByExternalId(runId);
        assertThat(byExternalId).hasSize(14);

        TestExecution correct = byExternalId.get("DEMO-001");
        assertThat(correct.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(correct.getAnswer()).contains("Java 21");
        assertThat(correct.getCitations()).extracting(citation -> citation.documentId())
                .containsExactly("payments-setup-v4");
        assertThat(correct.getRetrievedChunks()).hasSize(2);
        assertThat(correct.getTargetModel()).isEqualTo("demo-model");
        assertThat(correct.getInputTokens()).isNotNull();
        assertThat(correct.getOutputTokens()).isNotNull();
        assertThat(correct.getHttpStatus()).isEqualTo(200);

        TestExecution wrongVersion = byExternalId.get("DEMO-002");
        assertThat(wrongVersion.getAnswer()).contains("Java 17");
        assertThat(wrongVersion.getCitations()).extracting(citation -> citation.documentId())
                .containsExactly("payments-setup-v2");

        TestExecution retrievalMiss = byExternalId.get("DEMO-003");
        assertThat(retrievalMiss.getRetrievedChunks())
                .noneMatch(chunk -> "payments-db-config".equals(chunk.documentId()));

        TestExecution rankingFailure = byExternalId.get("DEMO-004");
        assertThat(rankingFailure.getRetrievedChunks())
                .anyMatch(chunk -> "security-standards".equals(chunk.documentId()) && chunk.rank() == 4);
        assertThat(rankingFailure.getAnswer()).contains("TLS 1.1");

        TestExecution unsupportedClaim = byExternalId.get("DEMO-005");
        assertThat(unsupportedClaim.getAnswer()).contains("rolled back after 5 minutes");
        assertThat(unsupportedClaim.getRetrievedChunks())
                .noneMatch(chunk -> chunk.content().contains("rolled back"));

        TestExecution incomplete = byExternalId.get("DEMO-006");
        assertThat(incomplete.getAnswer()).contains("30 seconds").doesNotContain("retry");

        TestExecution refusal = byExternalId.get("DEMO-007");
        assertThat(refusal.getAnswer()).containsIgnoringCase("cannot answer");
        assertThat(refusal.getCitations()).isEmpty();

        TestExecution disclosure = byExternalId.get("DEMO-008");
        assertThat(disclosure.getAnswer()).contains("cannot share").contains("st4ging-Demo-Pass");

        TestExecution missingCitation = byExternalId.get("DEMO-009");
        assertThat(missingCitation.getAnswer()).contains("500");
        assertThat(missingCitation.getCitations()).isEmpty();
        assertThat(missingCitation.getRetrievedChunks()).isNotEmpty();

        TestExecution unretrievedCitation = byExternalId.get("DEMO-010");
        assertThat(unretrievedCitation.getCitations()).extracting(citation -> citation.documentId())
                .containsExactly("payments-legacy-notes");
        assertThat(unretrievedCitation.getRetrievedChunks())
                .noneMatch(chunk -> "payments-legacy-notes".equals(chunk.documentId()));

        TestExecution contradictory = byExternalId.get("DEMO-011");
        List<String> contents = contradictory.getRetrievedChunks().stream()
                .map(chunk -> chunk.content()).toList();
        assertThat(contents).anyMatch(content -> content.contains("10 MB"));
        assertThat(contents).anyMatch(content -> content.contains("50 MB"));

        TestExecution slow = byExternalId.get("DEMO-012");
        assertThat(slow.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(slow.getLatencyMilliseconds()).isGreaterThanOrEqualTo(400L);

        TestExecution httpError = byExternalId.get("DEMO-013");
        assertThat(httpError.getStatus()).isEqualTo(ExecutionStatus.TARGET_ERROR);
        assertThat(httpError.getHttpStatus()).isEqualTo(500);
        assertThat(httpError.getAnswer()).isNull();

        TestExecution inconsistent = byExternalId.get("DEMO-014");
        assertThat(inconsistent.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(inconsistent.getAnswer()).contains("retained");
    }

    @Test
    void inconsistentScenarioRotatesAnswersAcrossCalls() {
        RestClient client = client();
        java.util.Set<String> answers = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            JsonNode response = client.post().uri("/demo-target/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"question\":\"How long are payment records retained?\",\"testCaseId\":\"DEMO-014\"}")
                    .retrieve()
                    .body(JsonNode.class);
            answers.add(response.at("/answer").asString());
        }
        assertThat(answers).hasSizeGreaterThan(1);
    }

    private Map<String, TestExecution> executionsByExternalId(UUID runId) {
        EvaluationRun run = runRepository.findById(runId).orElseThrow();
        Map<UUID, String> externalIdsByTestCaseId = testCaseRepository.findByDatasetId(run.getDatasetId()).stream()
                .collect(Collectors.toMap(testCase -> testCase.getId(), testCase -> testCase.getExternalId()));
        Map<String, TestExecution> result = new HashMap<>();
        for (TestExecution execution : executionRepository.findByEvaluationRunId(runId)) {
            result.put(externalIdsByTestCaseId.get(execution.getTestCaseId()), execution);
        }
        return result;
    }

    private RunStatus awaitTerminal(UUID runId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            RunStatus status = runRepository.findById(runId).orElseThrow().getStatus();
            if (status != RunStatus.CREATED && status != RunStatus.QUEUED && status != RunStatus.RUNNING) {
                return status;
            }
            Thread.sleep(150);
        }
        throw new AssertionError("Run " + runId + " did not reach a terminal status within 60s");
    }
}
