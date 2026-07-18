package com.rageval.workbench.run.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.application.StartupRunRecovery;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RunMode;
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

@SpringBootTest(properties = "spring.ai.ollama.base-url=http://localhost:1")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class RunControllerIntegrationTest {

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
    private StartupRunRecovery startupRunRecovery;

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

    private TargetSystem saveTarget() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        TargetSystem target = new TargetSystem(
                "Run IT Target", null, AdapterType.STANDARD_HTTP, wireMockServer.baseUrl() + "/chat",
                TargetHttpMethod.POST, template, "/answer", "/citations", "/retrievedChunks", "/model",
                true, 5000, 2000, false, 2, AuthenticationType.NONE, null, null, null);
        return targetSystemRepository.save(target);
    }

    private EvaluationDataset saveReadyDataset(String... externalIds) {
        EvaluationDataset dataset = new EvaluationDataset("Run IT Dataset " + UUID.randomUUID(), null, "1.0.0");
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

    private String createRun(UUID targetId, UUID datasetId, int repetitions) throws Exception {
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("targetSystemId", targetId.toString());
        request.put("datasetId", datasetId.toString());
        request.put("name", "IT run");
        request.put("repetitionsPerTestCase", repetitions);
        String body = mockMvc.perform(post("/api/v1/evaluation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.runMode").value("LIVE"))
                .andReturn().getResponse().getContentAsString();
        return jsonMapper.readTree(body).at("/id").asString();
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

    @Test
    void fullLifecycleCompletesAndPersistsNormalizedResults() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("CASE-1", "CASE-2");
        stubAnswer("CASE-1", 200, "Answer one");
        stubAnswer("CASE-2", 200, "Answer two");

        UUID runId = UUID.fromString(createRun(target.getId(), dataset.getId(), 2));
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", runId))
                .andExpect(status().isAccepted());

        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.COMPLETED);

        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        assertThat(executions).hasSize(4);
        assertThat(executions).allSatisfy(execution -> {
            assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
            assertThat(execution.getAnswer()).startsWith("Answer");
            assertThat(execution.getTargetModel()).isEqualTo("it-model");
            assertThat(execution.getHttpStatus()).isEqualTo(200);
            assertThat(execution.getLatencyMilliseconds()).isNotNull();
            assertThat(execution.getAttemptCount()).isEqualTo(1);
            assertThat(execution.getRequestPayload()).isNotNull();
            assertThat(execution.getStartedAt()).isNotNull();
            assertThat(execution.getCompletedAt()).isNotNull();
        });

        EvaluationRun run = runRepository.findById(runId).orElseThrow();
        assertThat(run.getStartedAt()).isNotNull();
        assertThat(run.getCompletedAt()).isNotNull();

        mockMvc.perform(get("/api/v1/evaluation-runs/{id}/executions", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void partialTargetFailureYieldsPartiallyCompleted() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("OK-1", "BAD-1");
        stubAnswer("OK-1", 200, "Fine");
        stubAnswer("BAD-1", 500, "ignored");

        UUID runId = UUID.fromString(createRun(target.getId(), dataset.getId(), 1));
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", runId)).andExpect(status().isAccepted());

        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.PARTIALLY_COMPLETED);
        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        assertThat(executions).extracting(TestExecution::getStatus)
                .containsExactlyInAnyOrder(ExecutionStatus.SUCCEEDED, ExecutionStatus.TARGET_ERROR);
        TestExecution failed = executions.stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.TARGET_ERROR)
                .findFirst().orElseThrow();
        assertThat(failed.getHttpStatus()).isEqualTo(500);
        assertThat(failed.getErrorType()).isNotNull();
    }

    @Test
    void createValidatesTargetAndDatasetFirst() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset draft = new EvaluationDataset("Draft", null, "1.0.0");
        datasetRepository.save(draft);

        ObjectNode request = jsonMapper.createObjectNode();
        request.put("targetSystemId", target.getId().toString());
        request.put("datasetId", draft.getId().toString());
        request.put("name", "invalid");
        mockMvc.perform(post("/api/v1/evaluation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("datasetId"));

        request.put("datasetId", UUID.randomUUID().toString());
        mockMvc.perform(post("/api/v1/evaluation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }

    @Test
    void cancelBeforeStartAndInvalidTransitionsAreRejected() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("CASE-A");
        UUID runId = UUID.fromString(createRun(target.getId(), dataset.getId(), 1));

        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/cancel", runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        // A cancelled run cannot be started, only resumed.
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", runId))
                .andExpect(status().isConflict());
        // And cancelling again is an invalid transition.
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/cancel", runId))
                .andExpect(status().isConflict());
    }

    @Test
    void resumeSchedulesOnlyIncompleteExecutionsAndNeverRepeatsSuccessfulOnes() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("DONE-1", "TODO-1");
        EvaluationTestCase doneCase = testCaseRepository.findByDatasetId(dataset.getId()).stream()
                .filter(testCase -> testCase.getExternalId().equals("DONE-1")).findFirst().orElseThrow();
        EvaluationTestCase todoCase = testCaseRepository.findByDatasetId(dataset.getId()).stream()
                .filter(testCase -> testCase.getExternalId().equals("TODO-1")).findFirst().orElseThrow();

        // Seed an INTERRUPTED run: one execution already succeeded, one was cancelled mid-flight.
        EvaluationRun run = new EvaluationRun(
                target.getId(), dataset.getId(), "resumable", RunMode.LIVE, 1, List.of(), Map.of());
        run.markQueued();
        run.markStarted();
        run.markInterrupted();
        runRepository.save(run);

        TestExecution done = new TestExecution(run.getId(), doneCase.getId(), null, 1);
        done.markRunning();
        done.recordSuccess(null, "already answered", null, List.of(), List.of(), "it-model", 10L, null, null,
                200, 1, Instant.now(), Instant.now());
        executionRepository.save(done);
        TestExecution todo = new TestExecution(run.getId(), todoCase.getId(), null, 1);
        todo.markCancelled("interrupted by application restart");
        executionRepository.save(todo);

        stubAnswer("TODO-1", 200, "Resumed answer");
        stubAnswer("DONE-1", 200, "MUST NOT BE CALLED");

        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/resume", run.getId()))
                .andExpect(status().isAccepted());
        assertThat(awaitTerminal(run.getId())).isEqualTo(RunStatus.COMPLETED);

        List<TestExecution> executions = executionRepository.findByEvaluationRunId(run.getId());
        assertThat(executions).hasSize(2);
        assertThat(executionRepository.findById(done.getId()).orElseThrow().getAnswer())
                .isEqualTo("already answered");
        assertThat(executionRepository.findById(todo.getId()).orElseThrow().getAnswer())
                .isEqualTo("Resumed answer");
        // The already-successful execution's question was never sent to the target again.
        wireMockServer.verify(0, postRequestedFor(urlEqualTo("/chat"))
                .withRequestBody(equalToJson(
                        "{\"question\":\"Question for DONE-1\",\"testCaseId\":\"DONE-1\"}", true, false)));
    }

    @Test
    void resumeOfRunWithNothingIncompleteIsRejected() throws Exception {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("ONLY-1");
        EvaluationTestCase testCase = testCaseRepository.findByDatasetId(dataset.getId()).get(0);
        EvaluationRun run = new EvaluationRun(
                target.getId(), dataset.getId(), "all-done", RunMode.LIVE, 1, List.of(), Map.of());
        run.markQueued();
        run.markStarted();
        run.markFailed("target kept failing");
        runRepository.save(run);
        TestExecution failed = new TestExecution(run.getId(), testCase.getId(), null, 1);
        failed.markRunning();
        failed.recordFailure(ExecutionStatus.TARGET_ERROR, null, null, "HTTP_500", "boom", 500, 2,
                Instant.now(), Instant.now());
        executionRepository.save(failed);

        // The only execution completed (with a real target error) — that is evidence, not incomplete work.
        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/resume", run.getId()))
                .andExpect(status().isConflict());
    }

    @Test
    void startupRecoveryMarksStaleRunsInterrupted() {
        TargetSystem target = saveTarget();
        EvaluationDataset dataset = saveReadyDataset("STALE-1");
        EvaluationTestCase testCase = testCaseRepository.findByDatasetId(dataset.getId()).get(0);

        EvaluationRun staleRunning = new EvaluationRun(
                target.getId(), dataset.getId(), "stale-running", RunMode.LIVE, 1, List.of(), Map.of());
        staleRunning.markQueued();
        staleRunning.markStarted();
        runRepository.save(staleRunning);
        TestExecution inFlight = new TestExecution(staleRunning.getId(), testCase.getId(), null, 1);
        inFlight.markRunning();
        executionRepository.save(inFlight);

        EvaluationRun staleQueued = new EvaluationRun(
                target.getId(), dataset.getId(), "stale-queued", RunMode.LIVE, 1, List.of(), Map.of());
        staleQueued.markQueued();
        runRepository.save(staleQueued);

        startupRunRecovery.recoverStaleRuns();

        assertThat(runRepository.findById(staleRunning.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.INTERRUPTED);
        assertThat(runRepository.findById(staleQueued.getId()).orElseThrow().getStatus())
                .isEqualTo(RunStatus.INTERRUPTED);
        TestExecution recovered = executionRepository.findById(inFlight.getId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(recovered.getErrorMessage()).contains("restart");
    }

    @Test
    void demoTargetEndpointIsAbsentOutsideDemoProfile() throws Exception {
        mockMvc.perform(post("/demo-target/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"anything\"}"))
                .andExpect(status().isNotFound());
    }
}
