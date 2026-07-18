package com.rageval.workbench.evaluation.llm;

import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Shared flow for the Phase 5 integration tests: imports a one-case dataset (the demo target's
 * DEMO-001 happy-path scenario, which returns an answer, citations, and retrieved chunks — so
 * every LLM evaluator is applicable), configures the demo target, and creates + starts a run with
 * all registered evaluators enabled.
 */
final class LlmIntegrationTestSupport {

    private LlmIntegrationTestSupport() {
    }

    static UUID runDemoScenario(RestClient client, JsonMapper jsonMapper, int port) {
        ObjectNode dataset = jsonMapper.createObjectNode();
        dataset.put("name", "Phase 5 LLM scenario dataset");
        dataset.put("datasetVersion", "1.0.0");
        ArrayNode testCases = dataset.putArray("testCases");
        ObjectNode testCase = testCases.addObject();
        testCase.put("externalId", "DEMO-001");
        testCase.put("question", "Which Java version does the payments service require?");
        testCase.put("referenceAnswer", "The payments service requires Java 21.");
        testCase.put("answerable", true);
        testCase.put("shouldRefuse", false);
        testCase.put("severity", "MEDIUM");
        ObjectNode fact = testCase.putArray("requiredFacts").addObject();
        fact.put("id", "fact-java-version");
        fact.put("value", "Java 21");
        fact.put("matchType", "CASE_INSENSITIVE_TEXT");
        fact.put("mandatory", true);
        testCase.putArray("forbiddenClaims");
        testCase.putArray("expectedDocumentIds").add("payments-setup-v4");
        testCase.putArray("tags").add("demo");

        JsonNode imported = client.post().uri("/api/v1/datasets/import/json")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(dataset))
                .retrieve()
                .body(JsonNode.class);
        String datasetId = imported.at("/id").asString();
        client.post().uri("/api/v1/datasets/{id}/mark-ready", datasetId).retrieve().toBodilessEntity();

        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("testCaseId", "{{testCaseId}}");
        template.put("conversationId", "{{conversationId}}");
        ObjectNode targetRequest = jsonMapper.createObjectNode();
        targetRequest.put("name", "Demo Target (LLM tests)");
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
        String targetId = target.at("/id").asString();

        ObjectNode runRequest = jsonMapper.createObjectNode();
        runRequest.put("targetSystemId", targetId);
        runRequest.put("datasetId", datasetId);
        runRequest.put("name", "Phase 5 LLM run");
        runRequest.put("repetitionsPerTestCase", 1);
        JsonNode created = client.post().uri("/api/v1/evaluation-runs")
                .contentType(MediaType.APPLICATION_JSON)
                .body(jsonMapper.writeValueAsString(runRequest))
                .retrieve()
                .body(JsonNode.class);
        UUID runId = UUID.fromString(created.at("/id").asString());
        client.post().uri("/api/v1/evaluation-runs/{id}/start", runId).retrieve().toBodilessEntity();
        return runId;
    }
}
