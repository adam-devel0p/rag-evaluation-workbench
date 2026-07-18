package com.rageval.workbench.run.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(properties = "spring.ai.ollama.base-url=http://localhost:1")
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class ImportedTraceControllerIntegrationTest {

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

    private TargetSystem saveImportedTraceTarget() {
        return targetSystemRepository.save(new TargetSystem(
                "Imported Trace Target", null, AdapterType.IMPORTED_TRACE, null, null, null, null, null,
                null, null, true, 5000, 2000, false, 1, AuthenticationType.NONE, null, null, null));
    }

    private EvaluationDataset saveReadyDataset(String... externalIds) {
        EvaluationDataset dataset = new EvaluationDataset("Trace Dataset " + UUID.randomUUID(), null, "1.0.0");
        dataset.markReady();
        datasetRepository.save(dataset);
        for (String externalId : externalIds) {
            testCaseRepository.save(new EvaluationTestCase(
                    dataset.getId(), externalId, "Question for " + externalId, null, true, false,
                    Severity.MEDIUM, List.of(), List.of(), List.of(), List.of(), Map.of(), null));
        }
        return dataset;
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
    void jsonImportCreatesReplayRunAndStartExecutesFromStoredTraces() throws Exception {
        TargetSystem target = saveImportedTraceTarget();
        EvaluationDataset dataset = saveReadyDataset("DEV-001", "DEV-002");

        String payload = """
                {
                  "targetSystemId": "%s",
                  "datasetId": "%s",
                  "runName": "imported baseline",
                  "traces": [
                    {
                      "externalTestCaseId": "DEV-001",
                      "repetitionNumber": 1,
                      "question": "Which Java version is required?",
                      "answer": "Java 17",
                      "citations": [{"documentId": "payments-setup-v2", "documentTitle": "Payments Setup Guide v2",
                                     "section": "Requirements", "url": null, "quotedText": null, "metadata": {}}],
                      "retrievedChunks": [{"chunkId": "old-java-version", "documentId": "payments-setup-v2",
                                           "documentTitle": "Payments Setup Guide v2",
                                           "content": "The payments service requires Java 17.", "rank": 1,
                                           "retrievalScore": 0.91, "metadata": {"status": "deprecated"}}],
                      "targetModel": "example-model",
                      "latencyMilliseconds": 850,
                      "metadata": {"capturedBy": "client-export"}
                    },
                    {
                      "externalTestCaseId": "DEV-001",
                      "repetitionNumber": 2,
                      "question": "Which Java version is required?",
                      "answer": "Java 21",
                      "citations": [],
                      "retrievedChunks": [],
                      "targetModel": "example-model",
                      "latencyMilliseconds": 920,
                      "metadata": {}
                    },
                    {
                      "externalTestCaseId": "DEV-002",
                      "repetitionNumber": 1,
                      "question": "Question for DEV-002",
                      "answer": "Some answer",
                      "citations": [],
                      "retrievedChunks": [],
                      "targetModel": "example-model",
                      "latencyMilliseconds": 500,
                      "metadata": {}
                    }
                  ]
                }
                """.formatted(target.getId(), dataset.getId());

        String body = mockMvc.perform(post("/api/v1/imported-traces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedTraceCount").value(3))
                .andExpect(jsonPath("$.run.runMode").value("REPLAY"))
                .andExpect(jsonPath("$.run.status").value("CREATED"))
                .andExpect(jsonPath("$.run.repetitionsPerTestCase").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(jsonMapper.readTree(body).at("/run/id").asString());

        assertThat(executionRepository.findByEvaluationRunId(runId)).hasSize(3)
                .allMatch(execution -> execution.getStatus() == ExecutionStatus.PENDING);

        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", runId)).andExpect(status().isAccepted());
        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.COMPLETED);

        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        assertThat(executions).allMatch(execution -> execution.getStatus() == ExecutionStatus.SUCCEEDED);
        TestExecution rep1 = executions.stream()
                .filter(execution -> execution.getRepetitionNumber() == 1 && "Java 17".equals(execution.getAnswer()))
                .findFirst().orElseThrow();
        assertThat(rep1.getTargetModel()).isEqualTo("example-model");
        assertThat(rep1.getLatencyMilliseconds()).isEqualTo(850L);
        assertThat(rep1.getCitations()).hasSize(1);
        assertThat(rep1.getCitations().get(0).documentId()).isEqualTo("payments-setup-v2");
        assertThat(rep1.getRetrievedChunks()).hasSize(1);
        assertThat(rep1.getRawResponse().at("/metadata/capturedBy").asString()).isEqualTo("client-export");
        assertThat(rep1.getHttpStatus()).isNull();
    }

    @Test
    void importReportsRowLevelViolationsWithoutPersistingAnything() throws Exception {
        TargetSystem target = saveImportedTraceTarget();
        EvaluationDataset dataset = saveReadyDataset("DEV-001");
        long runsBefore = runRepository.count();

        String payload = """
                {
                  "targetSystemId": "%s",
                  "datasetId": "%s",
                  "runName": "broken import",
                  "traces": [
                    {"externalTestCaseId": "UNKNOWN-9", "repetitionNumber": 1, "question": "q", "answer": "a",
                     "citations": [], "retrievedChunks": [], "targetModel": null, "latencyMilliseconds": null, "metadata": {}},
                    {"externalTestCaseId": "DEV-001", "repetitionNumber": 0, "question": "q", "answer": "a",
                     "citations": [], "retrievedChunks": [], "targetModel": null, "latencyMilliseconds": null, "metadata": {}},
                    {"externalTestCaseId": "DEV-001", "repetitionNumber": 2, "question": " ", "answer": "a",
                     "citations": [], "retrievedChunks": [], "targetModel": null, "latencyMilliseconds": null, "metadata": {}},
                    {"externalTestCaseId": "DEV-001", "repetitionNumber": 2, "question": "q", "answer": "a",
                     "citations": [], "retrievedChunks": [], "targetModel": null, "latencyMilliseconds": null, "metadata": {}}
                  ]
                }
                """.formatted(target.getId(), dataset.getId());

        mockMvc.perform(post("/api/v1/imported-traces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'traces[0].externalTestCaseId')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'traces[1].repetitionNumber')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'traces[2].question')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field == 'traces[3].repetitionNumber')]").exists());

        assertThat(runRepository.count()).isEqualTo(runsBefore);
    }

    @Test
    void importRejectsNonImportedTraceTarget() throws Exception {
        TargetSystem httpTarget = targetSystemRepository.save(new TargetSystem(
                "HTTP Target", null, AdapterType.STANDARD_HTTP, "https://example.com/chat",
                com.rageval.workbench.target.domain.TargetHttpMethod.POST, null, "/answer", null, null, null,
                true, 5000, 2000, false, 1, AuthenticationType.NONE, null, null, null));
        EvaluationDataset dataset = saveReadyDataset("DEV-001");

        String payload = """
                {"targetSystemId": "%s", "datasetId": "%s", "runName": "wrong target",
                 "traces": [{"externalTestCaseId": "DEV-001", "repetitionNumber": 1, "question": "q", "answer": "a",
                             "citations": [], "retrievedChunks": [], "targetModel": null,
                             "latencyMilliseconds": null, "metadata": {}}]}
                """.formatted(httpTarget.getId(), dataset.getId());

        mockMvc.perform(post("/api/v1/imported-traces")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("targetSystemId"));
    }

    @Test
    void csvImportCarriesBasicFieldsAndRunCompletes() throws Exception {
        TargetSystem target = saveImportedTraceTarget();
        EvaluationDataset dataset = saveReadyDataset("DEV-001", "DEV-002");

        String csv = "externalTestCaseId,repetitionNumber,question,answer,targetModel,latencyMilliseconds\n"
                + "DEV-001,1,Which Java version is required?,Java 21,example-model,850\n"
                + "DEV-002,1,\"Question, with a comma\",\"Answer \"\"quoted\"\"\",example-model,430\n";
        MockMultipartFile file =
                new MockMultipartFile("file", "traces.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        String body = mockMvc.perform(multipart("/api/v1/imported-traces/csv")
                        .file(file)
                        .param("targetSystemId", target.getId().toString())
                        .param("datasetId", dataset.getId().toString())
                        .param("runName", "csv import"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.importedTraceCount").value(2))
                .andReturn().getResponse().getContentAsString();
        UUID runId = UUID.fromString(jsonMapper.readTree(body).at("/run/id").asString());

        mockMvc.perform(post("/api/v1/evaluation-runs/{id}/start", runId)).andExpect(status().isAccepted());
        assertThat(awaitTerminal(runId)).isEqualTo(RunStatus.COMPLETED);
        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        assertThat(executions).extracting(TestExecution::getAnswer)
                .containsExactlyInAnyOrder("Java 21", "Answer \"quoted\"");
        // CSV is the basic-fields format: citations/chunks always import empty.
        assertThat(executions).allMatch(execution -> execution.getCitations().isEmpty());
    }

    @Test
    void csvImportRejectsBadHeaderAndBadNumbers() throws Exception {
        TargetSystem target = saveImportedTraceTarget();
        EvaluationDataset dataset = saveReadyDataset("DEV-001");

        MockMultipartFile badHeader = new MockMultipartFile(
                "file", "traces.csv", "text/csv",
                "wrong,header\nDEV-001,1".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/imported-traces/csv")
                        .file(badHeader)
                        .param("targetSystemId", target.getId().toString())
                        .param("datasetId", dataset.getId().toString())
                        .param("runName", "bad header"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("file"));

        MockMultipartFile badNumber = new MockMultipartFile(
                "file", "traces.csv", "text/csv",
                ("externalTestCaseId,repetitionNumber,question,answer,targetModel,latencyMilliseconds\n"
                        + "DEV-001,not-a-number,q,a,m,100")
                        .getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/imported-traces/csv")
                        .file(badNumber)
                        .param("targetSystemId", target.getId().toString())
                        .param("datasetId", dataset.getId().toString())
                        .param("runName", "bad number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("row[1].repetitionNumber"));
    }
}
