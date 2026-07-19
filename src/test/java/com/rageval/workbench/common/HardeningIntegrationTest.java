package com.rageval.workbench.common;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.review.domain.HumanReview;
import com.rageval.workbench.review.domain.ReviewDecision;
import com.rageval.workbench.review.infrastructure.HumanReviewRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.TargetHttpMethod;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Phase 8 hardening slice: run deletion cascades all dependent data, the spec §24 actuator and
 * metrics surface is exposed, client errors that used to fall into the 500 catch-all answer
 * ProblemDetail 400s with a correlation ID, and springdoc serves the OpenAPI document (the
 * "verified compatible with Spring Boot 4.1.0" proof, spec §29).
 */
@SpringBootTest(properties = "spring.ai.ollama.base-url=http://localhost:1")
@AutoConfigureMockMvc
// Boot disables metrics export in tests by default; this re-enables it so /actuator/prometheus
// is real here (Boot 4.1 relocated the annotation to the micrometer-metrics-test module).
@org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
@Import(PostgresTestcontainersConfiguration.class)
class HardeningIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TargetSystemRepository targetSystemRepository;

    @Autowired
    private EvaluationDatasetRepository datasetRepository;

    @Autowired
    private EvaluationTestCaseRepository testCaseRepository;

    @Autowired
    private EvaluationRunRepository runRepository;

    @Autowired
    private TestExecutionRepository executionRepository;

    @Autowired
    private EvaluationMetricResultRepository metricResultRepository;

    @Autowired
    private EvaluationFindingRepository findingRepository;

    @Autowired
    private HumanReviewRepository humanReviewRepository;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @Test
    void deletingARunCascadesExecutionsMetricsReviewsAndFindings() throws Exception {
        UUID runId = completedRun();
        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        List<EvaluationMetricResult> metricResults = metricResultRepository.findByRunId(runId);
        assertThat(executions).isNotEmpty();
        assertThat(metricResults).isNotEmpty();
        HumanReview review = humanReviewRepository.save(new HumanReview(
                metricResults.get(0).getId(), ReviewDecision.CONFIRMED, "IT reviewer", "checked", null, null));

        mockMvc.perform(delete("/api/v1/evaluation-runs/{id}", runId)).andExpect(status().isNoContent());

        assertThat(runRepository.findById(runId)).isEmpty();
        assertThat(executionRepository.findByEvaluationRunId(runId)).isEmpty();
        assertThat(metricResultRepository.findByRunId(runId)).isEmpty();
        assertThat(findingRepository.findByEvaluationRunId(runId)).isEmpty();
        assertThat(humanReviewRepository.findById(review.getId())).isEmpty();
        mockMvc.perform(get("/api/v1/evaluation-runs/{id}", runId)).andExpect(status().isNotFound());
    }

    @Test
    void deletingAnActiveRunIsRejected() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("DEL-QUEUED");
        EvaluationRun run = runRepository.save(new EvaluationRun(
                target.getId(), dataset.getId(), "queued run", com.rageval.workbench.run.domain.RunMode.LIVE,
                1, List.of(), null));
        run.markQueued();
        runRepository.save(run);

        mockMvc.perform(delete("/api/v1/evaluation-runs/{id}", run.getId()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void actuatorExposesHealthInfoMetricsAndPrometheus() throws Exception {
        // Ollama is deliberately unreachable in this context, so the aggregate health is honestly
        // 503 with the evaluatorModel component DOWN (Phase 5 decision: visible, never hidden).
        // The application itself keeps working — proven by every other test in this class.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.components.evaluatorModel.status").value("DOWN"))
                .andExpect(jsonPath("$.components.db.status").value("UP"));
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/prometheus")).andExpect(status().isOk());
    }

    @Test
    void workbenchMetricsAppearAfterARun() throws Exception {
        completedRun();
        String metricNames = mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(metricNames)
                .contains("rageval.target.invocations")
                .contains("rageval.target.latency")
                .contains("rageval.test.executions")
                .contains("rageval.evaluation.runs")
                .contains("rageval.evaluator.executions")
                .contains("rageval.evaluator.duration")
                .contains("rageval.review.backlog");

        // Spec §24: no high-cardinality tags. The evaluator metric carries only bounded tags.
        mockMvc.perform(get("/actuator/metrics/rageval.evaluator.executions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableTags[*].tag").value(
                        org.hamcrest.Matchers.containsInAnyOrder("evaluator", "status")));
    }

    @Test
    void malformedUuidAndMissingParameterAnswerProblemDetail400WithCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/evaluation-runs/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.title").value("Invalid request parameter"))
                .andExpect(jsonPath("$.correlationId").exists());

        mockMvc.perform(get("/api/v1/run-comparisons"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Missing request parameter"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void openApiDocumentIsServedBySpringdoc() throws Exception {
        String document = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(document).contains("\"openapi\"");
        assertThat(document).contains("/api/v1/evaluation-runs");
        assertThat(document).contains("/api/v1/run-comparisons");
    }

    private UUID completedRun() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("HARD-1");
        wireMockServer.stubFor(post(urlEqualTo("/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"answer\":\"An answer\",\"model\":\"it-model\",\"citations\":[],\"retrievedChunks\":[]}")));

        ObjectNode request = jsonMapper.createObjectNode();
        request.put("targetSystemId", target.getId().toString());
        request.put("datasetId", dataset.getId().toString());
        request.put("name", "hardening run");
        request.put("repetitionsPerTestCase", 1);
        String body = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/evaluation-runs")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID runId = UUID.fromString(jsonMapper.readTree(body).at("/id").asString());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/evaluation-runs/{id}/start", runId))
                .andExpect(status().isAccepted());
        awaitTerminal(runId);
        return runId;
    }

    private void awaitTerminal(UUID runId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            RunStatus status = runRepository.findById(runId).orElseThrow().getStatus();
            if (status != RunStatus.CREATED && status != RunStatus.QUEUED && status != RunStatus.RUNNING) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Run " + runId + " did not reach a terminal status within 30s");
    }

    private TargetSystem saveTarget() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        return targetSystemRepository.save(new TargetSystem(
                "Hardening IT Target " + UUID.randomUUID(), null, AdapterType.STANDARD_HTTP,
                wireMockServer.baseUrl() + "/chat", TargetHttpMethod.POST, template, "/answer", "/citations",
                "/retrievedChunks", "/model", true, 5000, 2000, false, 2, AuthenticationType.NONE, null, null, null));
    }

    private EvaluationDataset saveReadyDataset(String... externalIds) {
        EvaluationDataset dataset =
                new EvaluationDataset("Hardening IT Dataset " + UUID.randomUUID(), null, "1.0.0");
        dataset.markReady();
        datasetRepository.save(dataset);
        for (String externalId : externalIds) {
            testCaseRepository.save(new EvaluationTestCase(
                    dataset.getId(), externalId, "Question for " + externalId, null, true, false,
                    Severity.MEDIUM, List.of(), List.of(), List.of(), List.of(), Map.of(), null));
        }
        return dataset;
    }
}
