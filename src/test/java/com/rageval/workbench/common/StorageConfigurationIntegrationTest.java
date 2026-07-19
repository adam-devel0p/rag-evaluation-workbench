package com.rageval.workbench.common;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Spec §23: raw-response storage and retrieved-chunk-content storage are configurable. With both
 * off, a live run still captures the normalized evidence (answer, citation/chunk identifiers,
 * ranks, latency) and still evaluates — but no raw JSON body and no chunk content text land in
 * the database.
 */
@SpringBootTest(properties = {
    "spring.ai.ollama.base-url=http://localhost:1",
    "rag-evaluation.storage.store-raw-responses=false",
    "rag-evaluation.storage.store-retrieved-chunk-content=false"
})
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class StorageConfigurationIntegrationTest {

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
    void disabledStorageKeepsNormalizedEvidenceButDropsRawBodyAndChunkContent() throws Exception {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        TargetSystem target = targetSystemRepository.save(new TargetSystem(
                "Storage IT Target " + UUID.randomUUID(), null, AdapterType.STANDARD_HTTP,
                wireMockServer.baseUrl() + "/chat", TargetHttpMethod.POST, template, "/answer", "/citations",
                "/retrievedChunks", "/model", true, 5000, 2000, false, 2, AuthenticationType.NONE, null, null, null));
        EvaluationDataset dataset =
                new EvaluationDataset("Storage IT Dataset " + UUID.randomUUID(), null, "1.0.0");
        dataset.markReady();
        datasetRepository.save(dataset);
        testCaseRepository.save(new EvaluationTestCase(
                dataset.getId(), "STORE-1", "Question for STORE-1", null, true, false, Severity.MEDIUM,
                List.of(), List.of(), List.of(), List.of(), Map.of(), null));

        wireMockServer.stubFor(post(urlEqualTo("/chat")).willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"answer\":\"The answer text\",\"model\":\"it-model\",\"citations\":"
                        + "[{\"documentId\":\"doc-1\",\"documentTitle\":\"Doc one\"}],"
                        + "\"retrievedChunks\":[{\"chunkId\":\"chunk-1\",\"documentId\":\"doc-1\","
                        + "\"content\":\"Sensitive client content that must not be stored\",\"rank\":1,"
                        + "\"retrievalScore\":0.9}]}")));

        ObjectNode request = jsonMapper.createObjectNode();
        request.put("targetSystemId", target.getId().toString());
        request.put("datasetId", dataset.getId().toString());
        request.put("name", "storage run");
        request.put("repetitionsPerTestCase", 1);
        String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/evaluation-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn()
                .getResponse()
                .getContentAsString();
        UUID runId = UUID.fromString(jsonMapper.readTree(body).at("/id").asString());
        mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/evaluation-runs/{id}/start", runId))
                .andExpect(status().isAccepted());
        awaitTerminal(runId);

        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        assertThat(executions).hasSize(1);
        TestExecution execution = executions.get(0);
        assertThat(execution.getAnswer()).isEqualTo("The answer text");
        assertThat(execution.getRawResponse()).as("raw response must not be stored").isNull();
        assertThat(execution.getCitations()).hasSize(1);
        assertThat(execution.getRetrievedChunks()).hasSize(1);
        assertThat(execution.getRetrievedChunks().get(0).chunkId()).isEqualTo("chunk-1");
        assertThat(execution.getRetrievedChunks().get(0).rank()).isEqualTo(1);
        assertThat(execution.getRetrievedChunks().get(0).content())
                .as("chunk content must not be stored")
                .isNull();
        assertThat(metricResultRepository.findByRunId(runId))
                .as("evaluation still runs with storage limits on")
                .isNotEmpty();
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
}
