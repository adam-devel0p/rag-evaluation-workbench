package com.rageval.workbench.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 6 end to end: dataset import → demo target → run → deterministic evaluation → human-review
 * workflow (pending queue with filters, full review context, decision with override that never
 * deletes the automated result) → server-rendered HTML report with all 20 spec §22 sections and
 * both the automated and human verdicts — plus the proof that recommendation templates are
 * editable data, not strings baked into the report template.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("demo")
@Import(PostgresTestcontainersConfiguration.class)
@TestPropertySource(properties = {
    "rag-evaluation.demo.slow-response-delay-millis=2500",
    "spring.ai.ollama.base-url=http://localhost:1"
})
class ReviewAndReportIntegrationTest {

    private static final String[] DETERMINISTIC_EVALUATOR_KEYS = {
        "transport", "latency", "required-facts", "forbidden-claims", "refusal", "citation-presence",
        "expected-documents", "citation-retrieval-consistency", "answerability"
    };

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
    void reviewWorkflowAndReportRenderEndToEnd() throws Exception {
        RestClient client = client();
        UUID runId = runDemoEvaluation(client);

        // --- Human-review queue (spec §20): pending items exist and filters narrow them. ---
        JsonNode pending = client.get()
                .uri("/api/v1/reviews/pending?runId={runId}&size=100", runId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(pending.at("/totalElements").asLong()).isGreaterThan(0);
        JsonNode first = pending.at("/content/0");
        assertThat(first.at("/question").asString()).isNotBlank();
        assertThat(first.at("/evaluatorKey").asString()).isNotBlank();
        // The queue is ordered most-severe first.
        assertThat(first.at("/severity").asString()).isEqualTo("CRITICAL");

        JsonNode criticalOnly = client.get()
                .uri("/api/v1/reviews/pending?runId={runId}&severity=CRITICAL&size=100", runId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(criticalOnly.at("/totalElements").asLong()).isGreaterThan(0);
        for (JsonNode item : criticalOnly.at("/content")) {
            assertThat(item.at("/severity").asString()).isEqualTo("CRITICAL");
        }

        // DEMO-008 (CRITICAL, refusal-then-disclosure) has a flagged refusal verdict.
        JsonNode refusalPending = client.get()
                .uri("/api/v1/reviews/pending?runId={runId}&evaluatorKey=refusal&severity=CRITICAL&size=10", runId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(refusalPending.at("/totalElements").asLong()).isGreaterThan(0);
        JsonNode reviewable = null;
        for (JsonNode item : refusalPending.at("/content")) {
            if ("DEMO-008".equals(item.at("/testCaseExternalId").asString())) {
                reviewable = item;
                break;
            }
        }
        assertThat(reviewable).as("a pending refusal review for DEMO-008").isNotNull();
        UUID metricResultId = UUID.fromString(reviewable.at("/metricResultId").asString());

        // --- Full review context: question, answer, retrieved context, reference, evidence. ---
        JsonNode context = client.get()
                .uri("/api/v1/metric-results/{id}/review-context", metricResultId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(context.at("/testCase/externalId").asString()).isEqualTo("DEMO-008");
        assertThat(context.at("/testCase/question").asString()).isNotBlank();
        assertThat(context.at("/testCase/forbiddenClaims").size()).isGreaterThan(0);
        assertThat(context.at("/execution/answer").asString()).isNotBlank();
        assertThat(context.at("/metricResult/explanation").asString()).isNotBlank();
        assertThat(context.at("/metricResult/evidence").size()).isGreaterThan(0);
        assertThat(context.at("/reviews").size()).isZero();

        // --- Submit a decision with an override; the automated result must survive untouched. ---
        EvaluationMetricResult before = metricResultRepository.findById(metricResultId).orElseThrow();
        ObjectNode reviewRequest = jsonMapper.createObjectNode();
        reviewRequest.put("decision", "CONFIRMED");
        reviewRequest.put("reviewerName", "Jane Reviewer");
        reviewRequest.put("comment", "Verified: the answer discloses the emergency override code.");
        reviewRequest.put("overridePassed", false);
        reviewRequest.put("overrideScore", 0.0);
        ResponseEntity<JsonNode> created = client.post()
                .uri("/api/v1/metric-results/{id}/reviews", metricResultId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(reviewRequest))
                .retrieve()
                .toEntity(JsonNode.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody().at("/decision").asString()).isEqualTo("CONFIRMED");

        EvaluationMetricResult after = metricResultRepository.findById(metricResultId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getPassed()).isEqualTo(before.getPassed());
        assertThat(after.getScore()).isEqualTo(before.getScore());
        assertThat(after.getExplanation()).isEqualTo(before.getExplanation());

        // The reviewed result leaves the pending queue; its review is listed.
        JsonNode refusalPendingAfter = client.get()
                .uri("/api/v1/reviews/pending?runId={runId}&evaluatorKey=refusal&severity=CRITICAL&size=10", runId)
                .retrieve()
                .body(JsonNode.class);
        for (JsonNode item : refusalPendingAfter.at("/content")) {
            assertThat(item.at("/metricResultId").asString()).isNotEqualTo(metricResultId.toString());
        }
        JsonNode reviews = client.get()
                .uri("/api/v1/metric-results/{id}/reviews", metricResultId)
                .retrieve()
                .body(JsonNode.class);
        assertThat(reviews.size()).isEqualTo(1);

        // --- Validation: a malformed override score answers 400, not 500. ---
        ObjectNode badRequest = jsonMapper.createObjectNode();
        badRequest.put("decision", "REJECTED");
        badRequest.put("reviewerName", "Jane Reviewer");
        badRequest.put("overrideScore", 3.5);
        ResponseEntity<JsonNode> badResponse = client.post()
                .uri("/api/v1/metric-results/{id}/reviews", metricResultId)
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(badRequest))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(JsonNode.class);
        assertThat(badResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // --- The report (spec §22): all 20 sections, server-rendered, no JavaScript. ---
        String html = client.get()
                .uri("/reports/runs/{id}", runId)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);

        assertThat(html).doesNotContain("<script");
        String[] sectionHeadings = {
            "1. Report title and run metadata", "2. Scope", "3. Limitations", "4. Executive summary",
            "5. Overall outcome counts", "6. Metrics table", "7. Results by severity", "8. Results by tag",
            "9. Reliability by test category", "10. High and critical findings",
            "11. Representative failure examples", "12. Retrieval findings",
            "13. Refusal and unsupported-claim findings", "14. Consistency findings",
            "15. Latency and target errors", "16. Human-review status", "17. Prioritized recommendations",
            "18. Acceptance criteria", "19. Methodology", "20. Evaluator configuration and versions"
        };
        for (String heading : sectionHeadings) {
            assertThat(html).as("section heading '%s'", heading).contains(heading);
        }

        // Findings appear with their distinct categories — never one blended "failed" bucket.
        assertThat(html).contains("RETRIEVAL_MISS", "RETRIEVAL_RANKING", "FAILED_REFUSAL",
                "UNSAFE_DISCLOSURE", "MISSING_CITATION", "INCORRECT_CITATION", "HIGH_LATENCY",
                "TARGET_SYSTEM_ERROR", "INCONSISTENT_RESPONSE", "INCOMPLETE_ANSWER");
        // Major findings carry their full context.
        assertThat(html).contains("DEMO-008", "Expected behavior", "Actual response",
                "Observed problem", "Likely cause", "Acceptance criteria", "Human-review status");
        // Both verdicts visible: the automated refusal failure and the recorded human decision.
        assertThat(html).contains("Jane Reviewer", "CONFIRMED");
        assertThat(html).contains("Confirmed by human review");
        // Seeded template text reaches the recommendations section.
        assertThat(html).contains("Tune retrieval ranking");
        // Evaluator versions section lists the deterministic evaluators that ran.
        assertThat(html).contains("required-facts", "expected-documents");

        // --- Templates are data, not baked-in strings: edit one and re-render. ---
        ObjectNode templateEdit = jsonMapper.createObjectNode();
        templateEdit.put("recommendation",
                "EDITED-BY-CONSULTANT: verify chunking granularity for the payment docs corpus.");
        client.put()
                .uri("/api/v1/recommendation-templates/RETRIEVAL_MISS")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(templateEdit))
                .retrieve()
                .toBodilessEntity();
        String htmlAfterEdit = client.get()
                .uri("/reports/runs/{id}", runId)
                .accept(MediaType.TEXT_HTML)
                .retrieve()
                .body(String.class);
        assertThat(htmlAfterEdit).contains("EDITED-BY-CONSULTANT: verify chunking granularity");

        // --- Unknown run answers 404. ---
        ResponseEntity<String> missing = client.get()
                .uri("/reports/runs/{id}", UUID.randomUUID())
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    /** Import the demo dataset, register the demo target, and run 2 repetitions deterministically. */
    private UUID runDemoEvaluation(RestClient client) throws Exception {
        String datasetJson = Files.readString(Path.of("examples/demo-dataset.json"));
        JsonNode dataset = client.post().uri("/api/v1/datasets/import/json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(datasetJson)
                .retrieve()
                .body(JsonNode.class);
        String datasetId = dataset.at("/id").asString();
        client.post().uri("/api/v1/datasets/{id}/mark-ready", datasetId).retrieve().toBodilessEntity();

        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        template.put("conversationId", "{{conversationId}}");
        ObjectNode targetRequest = jsonMapper.createObjectNode();
        targetRequest.put("name", "Demo Target (review & report)");
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

        ObjectNode evaluatorConfiguration = jsonMapper.createObjectNode();
        ObjectNode citationPolicyByTag = jsonMapper.createObjectNode();
        citationPolicyByTag.put("missing-citation", "REQUIRED");
        evaluatorConfiguration.set("citationPolicyByTag", citationPolicyByTag);
        ObjectNode runRequest = jsonMapper.createObjectNode();
        runRequest.put("targetSystemId", target.at("/id").asString());
        runRequest.put("datasetId", datasetId);
        runRequest.put("name", "Phase 6 review & report");
        runRequest.put("repetitionsPerTestCase", 2);
        runRequest.set("evaluatorConfiguration", evaluatorConfiguration);
        var enabledKeys = runRequest.putArray("enabledEvaluatorKeys");
        for (String key : DETERMINISTIC_EVALUATOR_KEYS) {
            enabledKeys.add(key);
        }
        JsonNode created = client.post().uri("/api/v1/evaluation-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(runRequest))
                .retrieve()
                .body(JsonNode.class);
        UUID runId = UUID.fromString(created.at("/id").asString());
        client.post().uri("/api/v1/evaluation-runs/{id}/start", runId).retrieve().toBodilessEntity();
        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.PARTIALLY_COMPLETED);
        return runId;
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
