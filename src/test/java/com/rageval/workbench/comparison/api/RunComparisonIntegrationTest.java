package com.rageval.workbench.comparison.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec §19 end to end over the demo target: a baseline run of the shipped demo dataset, a
 * deliberately changed demo target (scenario redirects "fix" DEMO-002 and "break" DEMO-001), a
 * candidate run, and then both the structured {@code GET /api/v1/run-comparisons} comparison and
 * the server-rendered HTML comparison report. DEMO-014's rotating answers additionally regress
 * naturally between the two single-repetition runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Import(PostgresTestcontainersConfiguration.class)
// The delay value is deliberately different from every other demo-profile test so Spring's test
// context cache gives this class its OWN context: the DEMO-014 rotation counter must start at 0
// here (baseline answer correct, candidate answer wrong → a deterministic natural regression),
// which a context shared with DemoTargetIntegrationTest would break.
@org.springframework.test.context.TestPropertySource(properties = {
    "rag-evaluation.demo.slow-response-delay-millis=450",
    "spring.ai.ollama.base-url=http://localhost:1"
})
class RunComparisonIntegrationTest {

    private static final List<String> DETERMINISTIC_KEYS = List.of(
            "transport", "latency", "required-facts", "forbidden-claims", "refusal",
            "citation-presence", "expected-documents", "citation-retrieval-consistency", "answerability");

    @LocalServerPort
    private int port;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private EvaluationRunRepository runRepository;

    private RestClient client() {
        return RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    // Tests assert on status codes; don't throw so 4xx responses can be inspected.
                })
                .build();
    }

    @Test
    void baselineVersusCandidateComparisonAcrossAChangedDemoTarget() throws Exception {
        RestClient client = client();
        String datasetId = importDemoDataset(client);
        String targetId = createDemoTarget(client);

        // Baseline: the unmodified demo target.
        UUID baselineRunId = runToTerminal(client, targetId, datasetId, "Baseline demo run");

        // Deliberately change the demo target: DEMO-002 now answers correctly ("fixed"), DEMO-001
        // now answers with the outdated version ("broken").
        var redirectResponse = client.put().uri("/demo-target/scenario-redirects")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"DEMO-002\":\"DEMO-001\",\"DEMO-001\":\"DEMO-002\"}")
                .retrieve()
                .toBodilessEntity();
        assertThat(redirectResponse.getStatusCode().value()).isEqualTo(204);

        UUID candidateRunId = runToTerminal(client, targetId, datasetId, "Candidate demo run");

        // Structured comparison.
        JsonNode comparison = client.get()
                .uri("/api/v1/run-comparisons?baselineRunId={b}&candidateRunId={c}", baselineRunId, candidateRunId)
                .retrieve()
                .body(JsonNode.class);

        assertThat(comparison.at("/baseline/runId").asString()).isEqualTo(baselineRunId.toString());
        assertThat(comparison.at("/candidate/runId").asString()).isEqualTo(candidateRunId.toString());

        // Same dataset, same repetitions, same evaluator versions, both PARTIALLY_COMPLETED
        // (DEMO-013 always answers HTTP 500) — the only expected caveats are the two
        // PARTIALLY_COMPLETED notes, never a version warning.
        List<String> warnings = new java.util.ArrayList<>();
        comparison.at("/warnings").forEach(warning -> warnings.add(warning.asString()));
        assertThat(warnings).hasSize(2);
        assertThat(warnings).allMatch(warning -> warning.contains("PARTIALLY_COMPLETED"));

        assertThat(caseChange(comparison, "DEMO-002")).isEqualTo("IMPROVED");
        assertThat(caseChange(comparison, "DEMO-001")).isEqualTo("REGRESSED");
        // DEMO-014's rotating answer: correct in the baseline (first call), wrong in the candidate.
        assertThat(caseChange(comparison, "DEMO-014")).isEqualTo("REGRESSED");
        // DEMO-013 fails identically in both runs (HTTP 500 → mandatory transport failure).
        assertThat(caseChange(comparison, "DEMO-013")).isEqualTo("UNCHANGED");

        assertThat(comparison.at("/caseChanges/added").asLong()).isZero();
        assertThat(comparison.at("/caseChanges/removed").asLong()).isZero();
        assertThat(comparison.at("/caseChanges/improved").asLong()).isEqualTo(1);
        assertThat(comparison.at("/caseChanges/regressed").asLong()).isEqualTo(2);
        long total = comparison.at("/caseChanges/improved").asLong()
                + comparison.at("/caseChanges/regressed").asLong()
                + comparison.at("/caseChanges/unchanged").asLong()
                + comparison.at("/caseChanges/inconclusive").asLong();
        assertThat(total).isEqualTo(14);

        // Metric rates come with their counts; required-facts exists on both sides.
        JsonNode requiredFacts = metricRate(comparison, "required-facts");
        assertThat(requiredFacts.at("/baselineRate").isNumber()).isTrue();
        assertThat(requiredFacts.at("/candidateRate").isNumber()).isTrue();
        assertThat(requiredFacts.at("/baselineCompletedCount").asLong()).isGreaterThan(0);

        // High-severity finding counts and latency are present for both runs.
        assertThat(comparison.at("/highSeverityFindings/baselineCount").isNumber()).isTrue();
        assertThat(comparison.at("/highSeverityFindings/candidateCount").isNumber()).isTrue();
        assertThat(comparison.at("/latency/baseline/sampleCount").asLong()).isGreaterThan(0);
        assertThat(comparison.at("/latency/candidate/sampleCount").asLong()).isGreaterThan(0);

        // Evaluator versions all match — the runs used the same build of every evaluator.
        comparison.at("/evaluatorVersions").forEach(version ->
                assertThat(version.at("/matching").asBoolean())
                        .as("evaluator %s versions must match", version.at("/evaluatorKey").asString())
                        .isTrue());

        // HTML comparison report: arrow-style summary, caveat-free of version warnings, no JS.
        String html = client.get()
                .uri("/reports/comparisons?baselineRunId={b}&candidateRunId={c}", baselineRunId, candidateRunId)
                .retrieve()
                .body(String.class);
        assertThat(html).contains("Baseline vs. Candidate Comparison");
        assertThat(html).contains("required-facts").contains("→").contains("%");
        assertThat(html).contains("DEMO-002").contains("IMPROVED").contains("REGRESSED");
        assertThat(html).contains("Evaluator versions");
        assertThat(html).doesNotContain("<script");
    }

    /**
     * Deliberately uses only never-started (CREATED) runs: the validation rules fire before any
     * execution exists, and not touching the demo target keeps DEMO-014's rotation counter — which
     * the comparison test above depends on — untouched regardless of test order.
     */
    @Test
    void comparisonValidatesRunIdentityAndDatasetCompatibility() throws Exception {
        RestClient client = client();
        String datasetId = importDemoDataset(client);
        String targetId = createDemoTarget(client);
        UUID runId = createRun(client, targetId, datasetId, "Validation baseline");

        // Comparing a run with itself is meaningless.
        var selfComparison = client.get()
                .uri("/api/v1/run-comparisons?baselineRunId={b}&candidateRunId={c}", runId, runId)
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(selfComparison.getStatusCode().value()).isEqualTo(400);

        // A run on a different dataset requires the explicit allowDatasetMismatch acknowledgement.
        String otherDatasetId = importDemoDataset(client);
        UUID otherRunId = createRun(client, targetId, otherDatasetId, "Other-dataset run");
        var rejected = client.get()
                .uri("/api/v1/run-comparisons?baselineRunId={b}&candidateRunId={c}", runId, otherRunId)
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(rejected.getStatusCode().value()).isEqualTo(400);
        assertThat(rejected.getBody().at("/detail").asString()).contains("allowDatasetMismatch");

        JsonNode allowed = client.get()
                .uri("/api/v1/run-comparisons?baselineRunId={b}&candidateRunId={c}&allowDatasetMismatch=true",
                        runId, otherRunId)
                .retrieve()
                .body(JsonNode.class);
        List<String> warnings = new java.util.ArrayList<>();
        allowed.at("/warnings").forEach(warning -> warnings.add(warning.asString()));
        assertThat(warnings).anyMatch(warning -> warning.contains("different datasets"));
        // The candidate run never executed: every matched case is inconclusive or removed-like,
        // and the not-yet-executed state is called out.
        assertThat(warnings).anyMatch(warning -> warning.contains("has not finished executing"));
    }

    private String importDemoDataset(RestClient client) throws Exception {
        String datasetJson = Files.readString(Path.of("examples/demo-dataset.json"));
        JsonNode dataset = client.post().uri("/api/v1/datasets/import/json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(datasetJson)
                .retrieve()
                .body(JsonNode.class);
        String datasetId = dataset.at("/id").asString();
        client.post().uri("/api/v1/datasets/{id}/mark-ready", datasetId).retrieve().toBodilessEntity();
        return datasetId;
    }

    private String createDemoTarget(RestClient client) throws Exception {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        template.put("conversationId", "{{conversationId}}");
        ObjectNode targetRequest = jsonMapper.createObjectNode();
        targetRequest.put("name", "Comparison Demo Target " + UUID.randomUUID());
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

    private UUID createRun(RestClient client, String targetId, String datasetId, String name) throws Exception {
        ObjectNode runRequest = jsonMapper.createObjectNode();
        runRequest.put("targetSystemId", targetId);
        runRequest.put("datasetId", datasetId);
        runRequest.put("name", name);
        runRequest.put("repetitionsPerTestCase", 1);
        var keys = runRequest.putArray("enabledEvaluatorKeys");
        DETERMINISTIC_KEYS.forEach(keys::add);
        JsonNode created = client.post().uri("/api/v1/evaluation-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(runRequest))
                .retrieve()
                .body(JsonNode.class);
        return UUID.fromString(created.at("/id").asString());
    }

    private UUID runToTerminal(RestClient client, String targetId, String datasetId, String name) throws Exception {
        UUID runId = createRun(client, targetId, datasetId, name);
        client.post().uri("/api/v1/evaluation-runs/{id}/start", runId).retrieve().toBodilessEntity();
        RunStatus terminal = awaitTerminal(runId);
        assertThat(terminal).isEqualTo(RunStatus.PARTIALLY_COMPLETED);
        return runId;
    }

    private String caseChange(JsonNode comparison, String externalTestCaseId) {
        for (JsonNode caseComparison : comparison.at("/caseChanges/cases")) {
            if (caseComparison.at("/externalTestCaseId").asString().equals(externalTestCaseId)) {
                return caseComparison.at("/change").asString();
            }
        }
        throw new AssertionError("No case comparison for " + externalTestCaseId);
    }

    private JsonNode metricRate(JsonNode comparison, String evaluatorKey) {
        for (JsonNode rate : comparison.at("/metricRates")) {
            if (rate.at("/evaluatorKey").asString().equals(evaluatorKey)) {
                return rate;
            }
        }
        throw new AssertionError("No metric rate for " + evaluatorKey);
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
