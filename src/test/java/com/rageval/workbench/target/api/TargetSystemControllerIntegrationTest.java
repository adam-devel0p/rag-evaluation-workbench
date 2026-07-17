package com.rageval.workbench.target.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.RunMode;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.TargetHttpMethod;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.util.List;
import java.util.Map;
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

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class TargetSystemControllerIntegrationTest {

    private static WireMockServer wireMockServer;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private TargetSystemRepository targetSystemRepository;

    @Autowired
    private EvaluationDatasetRepository evaluationDatasetRepository;

    @Autowired
    private EvaluationRunRepository evaluationRunRepository;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @AfterEach
    void resetStubs() {
        wireMockServer.resetAll();
    }

    private String createTargetRequestJson(String endpointUrl) {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        ObjectNode request = jsonMapper.createObjectNode();
        request.put("name", "Acme Chatbot");
        request.put("description", "Demo target");
        request.put("adapterType", "STANDARD_HTTP");
        request.put("endpointUrl", endpointUrl);
        request.put("httpMethod", "POST");
        request.set("requestTemplate", template);
        request.put("answerJsonPointer", "/answer");
        request.put("citationsJsonPointer", "/citations");
        request.put("retrievedChunksJsonPointer", "/retrievedChunks");
        request.put("modelJsonPointer", "/model");
        request.put("enabled", true);
        request.put("timeoutMilliseconds", 5000);
        request.put("connectTimeoutMilliseconds", 2000);
        request.put("followRedirects", false);
        request.put("maximumConcurrency", 2);
        request.put("authenticationType", "BEARER_TOKEN");
        request.put("secretEnvironmentVariable", "ACME_CHATBOT_TOKEN");
        return jsonMapper.writeValueAsString(request);
    }

    @Test
    void createReturnsResponseWithSecretNameOnlyNeverAValue() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTargetRequestJson(wireMockServer.baseUrl() + "/chat")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Acme Chatbot"))
                .andExpect(jsonPath("$.secretEnvironmentVariable").value("ACME_CHATBOT_TOKEN"))
                .andExpect(content().string(not(containsString("Bearer"))));
    }

    @Test
    void rejectsNonHttpEndpointUrl() throws Exception {
        mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTargetRequestJson("ftp://example.com/chat")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endpointUrl"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void getListUpdateRoundTrip() throws Exception {
        String createResponse = mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTargetRequestJson(wireMockServer.baseUrl() + "/chat")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = jsonMapper.readTree(createResponse).at("/id").asString();

        mockMvc.perform(get("/api/v1/targets/{id}", id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Chatbot"));

        mockMvc.perform(get("/api/v1/targets")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        ObjectNode updateRequest = (ObjectNode) jsonMapper.readTree(createTargetRequestJson(wireMockServer.baseUrl() + "/chat"));
        updateRequest.put("name", "Acme Chatbot Renamed");
        mockMvc.perform(put("/api/v1/targets/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Acme Chatbot Renamed"));
    }

    @Test
    void deleteSucceedsWhenNoDependentRuns() throws Exception {
        TargetSystem target = new TargetSystem(
                "Deletable", null, AdapterType.STANDARD_HTTP, wireMockServer.baseUrl() + "/chat", TargetHttpMethod.POST,
                null, "/answer", null, null, null, true, 5000, 2000, false, 1, AuthenticationType.NONE, null, null, null);
        targetSystemRepository.save(target);

        mockMvc.perform(delete("/api/v1/targets/{id}", target.getId())).andExpect(status().isNoContent());
        assertThat(targetSystemRepository.findById(target.getId())).isEmpty();
    }

    @Test
    void deleteIsRejectedWhenDependentRunsExist() throws Exception {
        TargetSystem target = new TargetSystem(
                "Has Dependents", null, AdapterType.STANDARD_HTTP, wireMockServer.baseUrl() + "/chat", TargetHttpMethod.POST,
                null, "/answer", null, null, null, true, 5000, 2000, false, 1, AuthenticationType.NONE, null, null, null);
        targetSystemRepository.save(target);
        EvaluationDataset dataset = new EvaluationDataset("Dataset", null, "1.0.0");
        evaluationDatasetRepository.save(dataset);
        EvaluationRun run = new EvaluationRun(
                target.getId(), dataset.getId(), "Baseline", RunMode.LIVE, 1, List.of(), Map.of());
        evaluationRunRepository.save(run);

        mockMvc.perform(delete("/api/v1/targets/{id}", target.getId())).andExpect(status().isConflict());
        assertThat(targetSystemRepository.findById(target.getId())).isPresent();
    }

    @Test
    void testConnectionReportsSuccessAndExtractedFields() throws Exception {
        wireMockServer.stubFor(WireMock.post(WireMock.urlEqualTo("/chat"))
                .willReturn(WireMock.okJson(
                        "{\"answer\":\"Java 21\",\"citations\":[{\"documentId\":\"doc-1\"}],\"model\":\"demo\"}")));

        // NONE auth here (unlike createTargetRequestJson's default BEARER_TOKEN) so this test
        // exercises the success path without depending on any environment variable being set.
        ObjectNode request = (ObjectNode) jsonMapper.readTree(createTargetRequestJson(wireMockServer.baseUrl() + "/chat"));
        request.put("authenticationType", "NONE");
        request.remove("secretEnvironmentVariable");
        String createResponse = mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(request)))
                .andReturn().getResponse().getContentAsString();
        String id = jsonMapper.readTree(createResponse).at("/id").asString();

        mockMvc.perform(post("/api/v1/targets/{id}/test-connection", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.answerPreview").value("Java 21"))
                .andExpect(jsonPath("$.citationsExtracted").value(1))
                .andExpect(jsonPath("$.targetModel").value("demo"));
    }

    @Test
    void testConnectionFailsFastWhenReferencedSecretEnvironmentVariableIsNotSet() throws Exception {
        // ACME_CHATBOT_TOKEN is not set in the test environment (nor anywhere in this repo, by
        // design — secrets are never committed). This is the realistic path: the moment a target
        // is misconfigured (or the env var simply isn't exported yet), connection-test must fail
        // with a clear validation error instead of leaking a null/blank Authorization header to
        // the target. Real secret injection + never-logged is covered at the adapter unit level
        // by StandardHttpTargetSystemAdapterWireMockTest#secretHeaderIsInjectedButNeverAppearsInLogOutput,
        // which can supply an actual secret value directly rather than depending on process env state.
        String createResponse = mockMvc.perform(post("/api/v1/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createTargetRequestJson(wireMockServer.baseUrl() + "/chat")))
                .andReturn().getResponse().getContentAsString();
        String id = jsonMapper.readTree(createResponse).at("/id").asString();

        mockMvc.perform(post("/api/v1/targets/{id}/test-connection", id))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("secretEnvironmentVariable"));
    }
}
