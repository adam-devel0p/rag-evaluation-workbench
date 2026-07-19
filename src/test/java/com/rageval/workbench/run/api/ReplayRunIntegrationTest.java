package com.rageval.workbench.run.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
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
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
 * Spec §6.4/§21 replay: a REPLAY run re-evaluates a source run's captured executions through the
 * current evaluator pipeline and produces metric results <em>without a single target HTTP call</em>
 * — proven by counting WireMock requests. Copied executions reference their original via
 * {@code sourceExecutionId}, keep the captured answer/latency/failure evidence, and go through the
 * normal classification, so a replay run yields findings of its own.
 */
@SpringBootTest(properties = "spring.ai.ollama.base-url=http://localhost:1")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class ReplayRunIntegrationTest {

    private static final List<String> DETERMINISTIC_KEYS = List.of(
            "transport", "latency", "required-facts", "forbidden-claims", "refusal",
            "citation-presence", "expected-documents", "citation-retrieval-consistency", "answerability");

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

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    @Test
    void replayRunReEvaluatesCapturedExecutionsWithoutAnyTargetCall() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("REPLAY-OK", "REPLAY-BAD");
        stubAnswer("REPLAY-OK", 200, "The captured answer");
        stubAnswer("REPLAY-BAD", 500, "ignored");

        UUID sourceRunId = createAndRunToTerminal(target.getId(), dataset.getId());
        assertThat(runRepository.findById(sourceRunId).orElseThrow().getStatus())
                .isEqualTo(RunStatus.PARTIALLY_COMPLETED);
        int targetCallsAfterSourceRun = targetCallCount();
        assertThat(targetCallsAfterSourceRun).isGreaterThan(0);

        // Create + start the replay run through the API.
        String replayBody = mockMvc.perform(post("/api/v1/evaluation-runs/{id}/replay", sourceRunId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.runMode").value("REPLAY"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        UUID replayRunId = UUID.fromString(jsonMapper.readTree(replayBody).at("/id").asString());
        EvaluationRun replayRun = runRepository.findById(replayRunId).orElseThrow();
        assertThat(replayRun.getName()).endsWith("(replay)");
        assertThat(replayRun.getEnabledEvaluatorKeys())
                .containsExactlyInAnyOrderElementsOf(DETERMINISTIC_KEYS);

        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", replayRunId))
                .andExpect(status().isAccepted());
        assertThat(awaitTerminal(replayRunId)).isEqualTo(RunStatus.PARTIALLY_COMPLETED);

        // The whole point: replay never contacted the target.
        assertThat(targetCallCount()).isEqualTo(targetCallsAfterSourceRun);

        // Executions are copies referencing the original captured executions.
        Map<UUID, TestExecution> sourceById = executionRepository.findByEvaluationRunId(sourceRunId).stream()
                .collect(Collectors.toMap(TestExecution::getId, execution -> execution));
        List<TestExecution> replayExecutions = executionRepository.findByEvaluationRunId(replayRunId);
        assertThat(replayExecutions).hasSize(2);
        for (TestExecution replayExecution : replayExecutions) {
            TestExecution source = sourceById.get(replayExecution.getSourceExecutionId());
            assertThat(source).as("sourceExecutionId must reference an execution of the source run").isNotNull();
            assertThat(replayExecution.getStatus()).isEqualTo(source.getStatus());
            assertThat(replayExecution.getAnswer()).isEqualTo(source.getAnswer());
            assertThat(replayExecution.getRepetitionNumber()).isEqualTo(source.getRepetitionNumber());
        }
        TestExecution replayedSuccess = replayExecutions.stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.SUCCEEDED)
                .findFirst().orElseThrow();
        TestExecution originalSuccess = sourceById.get(replayedSuccess.getSourceExecutionId());
        assertThat(replayedSuccess.getAnswer()).isEqualTo("The captured answer");
        // The captured latency is the measurement — copied, not re-measured.
        assertThat(replayedSuccess.getLatencyMilliseconds()).isEqualTo(originalSuccess.getLatencyMilliseconds());
        TestExecution replayedFailure = replayExecutions.stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.TARGET_ERROR)
                .findFirst().orElseThrow();
        assertThat(replayedFailure.getHttpStatus()).isEqualTo(500);
        assertThat(replayedFailure.getErrorType()).isNotNull();

        // The evaluator pipeline produced fresh metric results for the replay run's executions.
        List<EvaluationMetricResult> replayResults = metricResultRepository.findByRunId(replayRunId);
        assertThat(replayResults).isNotEmpty();
        assertThat(replayResults).allSatisfy(result ->
                assertThat(result.getStatus()).isNotEqualTo(MetricResultStatus.FAILED));
        EvaluationMetricResult transportOfFailure = replayResults.stream()
                .filter(result -> result.getTestExecutionId().equals(replayedFailure.getId())
                        && result.getEvaluatorKey().equals("transport"))
                .findFirst().orElseThrow();
        assertThat(transportOfFailure.getPassed()).isFalse();

        // Classification ran for the replay run too: the copied HTTP-500 yields its own finding.
        assertThat(findingRepository.findByEvaluationRunId(replayRunId))
                .anyMatch(finding -> finding.getCategory() == FailureCategory.TARGET_SYSTEM_ERROR);

        // Replaying the replay still points sourceExecutionId at the ORIGINAL executions.
        String secondReplayBody = mockMvc.perform(post("/api/v1/evaluation-runs/{id}/replay", replayRunId))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        UUID secondReplayRunId = UUID.fromString(jsonMapper.readTree(secondReplayBody).at("/id").asString());
        assertThat(executionRepository.findByEvaluationRunId(secondReplayRunId))
                .allSatisfy(execution -> assertThat(sourceById).containsKey(execution.getSourceExecutionId()));
    }

    @Test
    void replayAcceptsOverridesAndValidatesThem() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("OVERRIDE-1");
        stubAnswer("OVERRIDE-1", 200, "Answer");
        UUID sourceRunId = createAndRunToTerminal(target.getId(), dataset.getId());

        ObjectNode override = jsonMapper.createObjectNode();
        override.put("name", "Re-judged with transport only");
        override.putArray("enabledEvaluatorKeys").add("transport");
        String body = mockMvc.perform(post("/api/v1/evaluation-runs/{id}/replay", sourceRunId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(override)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.name").value("Re-judged with transport only"))
                .andReturn().getResponse().getContentAsString();
        UUID replayRunId = UUID.fromString(jsonMapper.readTree(body).at("/id").asString());
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", replayRunId)).andExpect(status().isAccepted());
        assertThat(awaitTerminal(replayRunId)).isEqualTo(RunStatus.COMPLETED);
        assertThat(metricResultRepository.findByRunId(replayRunId))
                .isNotEmpty()
                .allSatisfy(result -> assertThat(result.getEvaluatorKey()).isEqualTo("transport"));

        ObjectNode invalid = jsonMapper.createObjectNode();
        invalid.putArray("enabledEvaluatorKeys").add("no-such-evaluator");
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/replay", sourceRunId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("enabledEvaluatorKeys"));
    }

    @Test
    void replayIsRejectedWhileTheSourceRunIsStillExecutingOrEmpty() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("PENDING-1");
        UUID createdOnly = createRun(target.getId(), dataset.getId());
        // Source run has not executed yet — nothing captured, nothing to replay.
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/replay", createdOnly))
                .andExpect(status().isConflict());

        // A cancelled-before-start run is terminal but has no completed executions either.
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/cancel", createdOnly)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/replay", createdOnly))
                .andExpect(status().isConflict());
    }

    private int targetCallCount() {
        return wireMockServer.findAll(postRequestedFor(urlEqualTo("/chat"))).size();
    }

    private TargetSystem saveTarget() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        TargetSystem target = new TargetSystem(
                "Replay IT Target", null, AdapterType.STANDARD_HTTP, wireMockServer.baseUrl() + "/chat",
                TargetHttpMethod.POST, template, "/answer", "/citations", "/retrievedChunks", "/model",
                true, 5000, 2000, false, 2, AuthenticationType.NONE, null, null, null);
        return targetSystemRepository.save(target);
    }

    private EvaluationDataset saveReadyDataset(String... externalIds) {
        EvaluationDataset dataset = new EvaluationDataset("Replay IT Dataset " + UUID.randomUUID(), null, "1.0.0");
        dataset.markReady();
        datasetRepository.save(dataset);
        for (String externalId : externalIds) {
            testCaseRepository.save(new EvaluationTestCase(
                    dataset.getId(), externalId, "Question for " + externalId, null, true, false,
                    Severity.MEDIUM, List.of(), List.of(), List.of(), List.of(), Map.of(), null));
        }
        return dataset;
    }

    private void stubAnswer(String testCaseId, int status, String answer) {
        wireMockServer.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/chat"))
                .withRequestBody(equalToJson(
                        "{\"question\":\"Question for " + testCaseId + "\",\"testCaseId\":\"" + testCaseId + "\"}",
                        true,
                        false))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"answer\":\"" + answer + "\",\"model\":\"it-model\",\"citations\":[],"
                                + "\"retrievedChunks\":[]}")));
    }

    private UUID createRun(UUID targetId, UUID datasetId) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("targetSystemId", targetId.toString());
        request.put("datasetId", datasetId.toString());
        request.put("name", "Replay IT source run");
        request.put("repetitionsPerTestCase", 1);
        var keys = request.putArray("enabledEvaluatorKeys");
        DETERMINISTIC_KEYS.forEach(keys::add);
        String body = mockMvc.perform(post("/api/v1/evaluation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(jsonMapper.readTree(body).at("/id").asString());
    }

    private UUID createAndRunToTerminal(UUID targetId, UUID datasetId) throws Exception {
        UUID runId = createRun(targetId, datasetId);
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", runId)).andExpect(status().isAccepted());
        awaitTerminal(runId);
        return runId;
    }

    private RunStatus awaitTerminal(UUID runId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(deadline)) {
            RunStatus status = runRepository.findById(runId).orElseThrow().getStatus();
            if (status != RunStatus.CREATED && status != RunStatus.QUEUED && status != RunStatus.RUNNING) {
                return status;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Run " + runId + " did not reach a terminal status within 30s");
    }
}
