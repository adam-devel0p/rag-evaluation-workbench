package com.rageval.workbench.dataset.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.persistence.PostgresTestcontainersConfiguration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestcontainersConfiguration.class)
class DatasetControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private EvaluationDatasetRepository evaluationDatasetRepository;

    @Test
    void createAddTestCaseMarkReadyThenRejectsFurtherModification() throws Exception {
        ObjectNode createRequest = jsonMapper.createObjectNode();
        createRequest.put("name", "Dev Docs");
        createRequest.put("description", "desc");
        createRequest.put("datasetVersion", "1.0.0");

        String createResponse = mockMvc.perform(post("/api/v1/datasets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        String datasetId = jsonMapper.readTree(createResponse).at("/id").asString();

        ObjectNode testCase = jsonMapper.createObjectNode();
        testCase.put("externalId", "DEV-001");
        testCase.put("question", "Which Java version is required?");
        testCase.put("referenceAnswer", "Java 21");
        testCase.put("answerable", true);
        testCase.put("shouldRefuse", false);
        testCase.put("severity", "MEDIUM");

        mockMvc.perform(post("/api/v1/datasets/{id}/test-cases", datasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(testCase)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalId").value("DEV-001"));

        mockMvc.perform(get("/api/v1/datasets/{id}/test-cases", datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("DEV-001"));

        mockMvc.perform(post("/api/v1/datasets/{id}/mark-ready", datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY"));

        ObjectNode secondTestCase = testCase.deepCopy();
        secondTestCase.put("externalId", "DEV-002");
        mockMvc.perform(post("/api/v1/datasets/{id}/test-cases", datasetId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonMapper.writeValueAsString(secondTestCase)))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/v1/datasets/{id}/mark-ready", datasetId))
                .andExpect(status().isConflict());
    }

    @Test
    void jsonImportCreatesDatasetAndAllTestCasesTransactionally() throws Exception {
        String importJson = """
                {
                  "name": "Developer Documentation Evaluation",
                  "description": "Tests an internal developer knowledge assistant",
                  "datasetVersion": "1.0.0",
                  "testCases": [
                    {
                      "externalId": "DEV-001",
                      "question": "Which Java version does the payments service require?",
                      "referenceAnswer": "The payments service requires Java 21.",
                      "answerable": true,
                      "shouldRefuse": false,
                      "severity": "MEDIUM",
                      "requiredFacts": [
                        {"id": "fact-java-version", "value": "Java 21", "matchType": "CASE_INSENSITIVE_TEXT", "mandatory": true, "explanation": "current version"}
                      ],
                      "forbiddenClaims": [
                        {"id": "old-java-version", "value": "Java 17", "matchType": "CASE_INSENSITIVE_TEXT", "mandatory": true, "explanation": "obsolete"}
                      ],
                      "expectedDocumentIds": ["payments-setup-v4"],
                      "tags": ["developer-onboarding", "versioning"],
                      "userContext": {"role": "developer"}
                    }
                  ]
                }
                """;

        String response = mockMvc.perform(post("/api/v1/datasets/import/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Developer Documentation Evaluation"))
                .andReturn().getResponse().getContentAsString();
        String datasetId = jsonMapper.readTree(response).at("/id").asString();

        mockMvc.perform(get("/api/v1/datasets/{id}/test-cases", datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("DEV-001"))
                .andExpect(jsonPath("$[0].requiredFacts[0].value").value("Java 21"))
                .andExpect(jsonPath("$[0].userContext.role").value("developer"));
    }

    @Test
    void jsonImportWithInvalidRegexPersistsNothing() throws Exception {
        long datasetCountBefore = evaluationDatasetRepository.count();
        String importJson = """
                {
                  "name": "Broken Dataset",
                  "datasetVersion": "1.0.0",
                  "testCases": [
                    {
                      "externalId": "DEV-001",
                      "question": "q",
                      "answerable": true,
                      "shouldRefuse": false,
                      "severity": "LOW",
                      "requiredFacts": [
                        {"id": "f1", "value": "Java[", "matchType": "REGEX", "mandatory": true, "explanation": "broken"}
                      ]
                    }
                  ]
                }
                """;

        mockMvc.perform(post("/api/v1/datasets/import/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("testCases[0].requiredFacts[0].value"));

        assertThat(evaluationDatasetRepository.count()).isEqualTo(datasetCountBefore);
    }

    @Test
    void csvImportParsesBasicFieldsAndPersistsDataset() throws Exception {
        String csv = "externalId,question,referenceAnswer,answerable,shouldRefuse,severity,tags,expectedDocumentIds,notes\n"
                + "DEV-001,What version?,Java 21,true,false,MEDIUM,versioning,doc-1,\n";
        MockMultipartFile file = new MockMultipartFile("file", "dataset.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

        String response = mockMvc.perform(multipart("/api/v1/datasets/import/csv")
                        .file(file)
                        .param("name", "CSV Dataset")
                        .param("datasetVersion", "1.0.0"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String datasetId = jsonMapper.readTree(response).at("/id").asString();

        mockMvc.perform(get("/api/v1/datasets/{id}/test-cases", datasetId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].externalId").value("DEV-001"))
                .andExpect(jsonPath("$[0].tags[0]").value("versioning"));
    }
}
