package com.rageval.workbench.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.dataset.domain.DatasetStatus;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.review.domain.HumanReview;
import com.rageval.workbench.review.domain.ReviewDecision;
import com.rageval.workbench.review.infrastructure.HumanReviewRepository;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RetrievedChunk;
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
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves, against a real Postgres container, that:
 * <ul>
 *   <li>the Liquibase migrations under {@code db/changelog/} apply cleanly (implicit in context
 *       startup: {@code spring-boot-starter-liquibase} runs migrations before Hibernate
 *       validates),</li>
 *   <li>{@code spring.jpa.hibernate.ddl-auto=validate} passes against the migrated schema
 *       (implicit: the Spring context would fail to start otherwise), and</li>
 *   <li>every JSONB-mapped field round-trips correctly through Hibernate's Jackson 3 JSON format
 *       mapper.</li>
 * </ul>
 */
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
class PersistenceIntegrationTest {

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

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
    private HumanReviewRepository humanReviewRepository;

    @Autowired
    private EvaluationFindingRepository findingRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void contextLoadsAgainstMigratedSchema() {
        // Context startup already proves Liquibase migrated the schema and ddl-auto=validate
        // accepted it; this assertion just confirms the test wiring itself is sane.
        assertThat(targetSystemRepository).isNotNull();
    }

    @Test
    @Transactional
    void targetSystemJsonbFieldsRoundTrip() {
        ObjectNode template = JSON_MAPPER.createObjectNode();
        template.put("question", "{{question}}");
        template.putObject("userContext");

        TargetSystem target = new TargetSystem(
                "Acme Chatbot",
                "Demo target",
                AdapterType.STANDARD_HTTP,
                "https://acme.example.com/chat",
                TargetHttpMethod.POST,
                template,
                "/answer",
                "/citations",
                "/chunks",
                "/model",
                true,
                30_000,
                5_000,
                false,
                4,
                AuthenticationType.BEARER_TOKEN,
                "ACME_CHATBOT_TOKEN",
                null,
                null);

        UUID id = targetSystemRepository.save(target).getId();
        entityManager.flush();
        entityManager.clear();

        TargetSystem reloaded = targetSystemRepository.findById(id).orElseThrow();
        assertThat(reloaded.getRequestTemplate().get("question").asString()).isEqualTo("{{question}}");
        assertThat(reloaded.getRequestTemplate().has("userContext")).isTrue();
        assertThat(reloaded.getSecretEnvironmentVariable()).isEqualTo("ACME_CHATBOT_TOKEN");
        assertThat(reloaded.getAdapterType()).isEqualTo(AdapterType.STANDARD_HTTP);
    }

    @Test
    @Transactional
    void testCaseJsonbFieldsRoundTrip() {
        EvaluationDataset dataset = new EvaluationDataset("Dev Docs", "desc", "1.0.0");
        datasetRepository.save(dataset);

        List<ExpectedFact> requiredFacts = List.of(
                new ExpectedFact("fact-1", "Java 21", FactMatchType.CASE_INSENSITIVE_TEXT, true, "current version"));
        List<ExpectedFact> forbiddenClaims = List.of(
                new ExpectedFact("fact-2", "Java 17", FactMatchType.CASE_INSENSITIVE_TEXT, true, "obsolete"));

        EvaluationTestCase testCase = new EvaluationTestCase(
                dataset.getId(),
                "DEV-001",
                "Which Java version is required?",
                "Java 21",
                true,
                false,
                Severity.MEDIUM,
                requiredFacts,
                forbiddenClaims,
                List.of("payments-setup-v4"),
                List.of("developer-onboarding", "versioning"),
                Map.of("role", "developer"),
                null);
        UUID id = testCaseRepository.save(testCase).getId();
        entityManager.flush();
        entityManager.clear();

        EvaluationTestCase reloaded = testCaseRepository.findById(id).orElseThrow();
        assertThat(reloaded.getRequiredFacts()).containsExactly(requiredFacts.get(0));
        assertThat(reloaded.getForbiddenClaims()).containsExactly(forbiddenClaims.get(0));
        assertThat(reloaded.getExpectedDocumentIds()).containsExactly("payments-setup-v4");
        assertThat(reloaded.getTags()).containsExactlyInAnyOrder("developer-onboarding", "versioning");
        assertThat(reloaded.getUserContext()).containsEntry("role", "developer");
    }

    @Test
    @Transactional
    void runExecutionMetricReviewAndFindingRoundTripThroughFullGraph() {
        TargetSystem target = new TargetSystem(
                "Demo Target",
                null,
                AdapterType.STANDARD_HTTP,
                "http://localhost:8080/demo-target/chat",
                TargetHttpMethod.POST,
                null,
                "/answer",
                "/citations",
                "/retrievedChunks",
                "/model",
                true,
                30_000,
                5_000,
                false,
                2,
                AuthenticationType.NONE,
                null,
                null,
                null);
        targetSystemRepository.save(target);

        EvaluationDataset dataset = new EvaluationDataset("Dataset", null, "1.0.0");
        dataset.markReady();
        datasetRepository.save(dataset);

        EvaluationTestCase testCase = new EvaluationTestCase(
                dataset.getId(),
                "DEV-002",
                "What is the timeout?",
                "30 seconds",
                true,
                false,
                Severity.HIGH,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null);
        testCaseRepository.save(testCase);

        EvaluationRun run = new EvaluationRun(
                target.getId(),
                dataset.getId(),
                "Baseline run",
                RunMode.LIVE,
                3,
                List.of("transport", "required-facts"),
                Map.of("latencyThresholdMs", 5000));
        // Phase 3 guards the run state machine: RUNNING is only reachable via QUEUED.
        run.markQueued();
        run.markStarted();
        runRepository.save(run);

        ObjectNode rawResponse = JSON_MAPPER.createObjectNode();
        rawResponse.put("answer", "30 seconds");
        JsonNode requestPayload = JSON_MAPPER.createObjectNode().put("question", "What is the timeout?");

        TestExecution execution = new TestExecution(run.getId(), testCase.getId(), null, 1);
        execution.recordSuccess(
                requestPayload,
                "30 seconds",
                rawResponse,
                List.of(new Citation("doc-1", "Config Guide", "Timeouts", null, "30 seconds", Map.of("status", "current"))),
                List.of(new RetrievedChunk("chunk-1", "doc-1", "Config Guide", "The timeout is 30 seconds.", 1, 0.92, Map.of())),
                "demo-model",
                842L,
                12,
                5,
                200,
                1,
                Instant.now(),
                Instant.now());
        executionRepository.save(execution);

        EvaluationMetricResult metricResult = new EvaluationMetricResult(
                execution.getId(),
                "required-facts",
                EvaluatorType.DETERMINISTIC,
                "1.0.0",
                null,
                null,
                MetricResultStatus.COMPLETED,
                true,
                1.0,
                1.0,
                "All mandatory facts matched",
                List.of(new EvaluationEvidence(
                        "REQUIRED_FACT_MATCH", "matched timeout value", "doc-1", "chunk-1", 1, "30 seconds", Map.of())),
                false,
                15L,
                null);
        metricResultRepository.save(metricResult);

        HumanReview review = new HumanReview(
                metricResult.getId(), ReviewDecision.CONFIRMED, "reviewer@example.com", "Looks correct", true, 1.0);
        humanReviewRepository.save(review);

        EvaluationFinding finding = new EvaluationFinding(
                run.getId(),
                execution.getId(),
                FailureCategory.UNKNOWN,
                Severity.LOW,
                "No issues found",
                "The execution passed all mandatory evaluators.",
                null,
                null,
                null,
                List.of(new EvaluationEvidence(
                        "SUMMARY", "informational only", null, null, null, null, Map.of())));
        findingRepository.save(finding);

        entityManager.flush();
        entityManager.clear();

        TestExecution reloadedExecution = executionRepository.findById(execution.getId()).orElseThrow();
        assertThat(reloadedExecution.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(reloadedExecution.getCitations()).containsExactly(
                new Citation("doc-1", "Config Guide", "Timeouts", null, "30 seconds", Map.of("status", "current")));
        assertThat(reloadedExecution.getRetrievedChunks()).containsExactly(
                new RetrievedChunk("chunk-1", "doc-1", "Config Guide", "The timeout is 30 seconds.", 1, 0.92, Map.of()));
        assertThat(reloadedExecution.getRawResponse().get("answer").asString()).isEqualTo("30 seconds");
        assertThat(reloadedExecution.getRequestPayload().get("question").asString())
                .isEqualTo("What is the timeout?");

        EvaluationRun reloadedRun = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reloadedRun.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(reloadedRun.getEnabledEvaluatorKeys()).containsExactly("transport", "required-facts");
        assertThat(reloadedRun.getEvaluatorConfiguration()).containsEntry("latencyThresholdMs", 5000);

        EvaluationMetricResult reloadedMetricResult =
                metricResultRepository.findById(metricResult.getId()).orElseThrow();
        assertThat(reloadedMetricResult.getEvidence()).hasSize(1);
        assertThat(reloadedMetricResult.getEvidence().get(0).documentId()).isEqualTo("doc-1");

        HumanReview reloadedReview = humanReviewRepository.findById(review.getId()).orElseThrow();
        assertThat(reloadedReview.getDecision()).isEqualTo(ReviewDecision.CONFIRMED);
        assertThat(reloadedReview.getOverridePassed()).isTrue();

        EvaluationFinding reloadedFinding = findingRepository.findById(finding.getId()).orElseThrow();
        assertThat(reloadedFinding.getEvidence()).hasSize(1);
        assertThat(reloadedFinding.getCategory()).isEqualTo(FailureCategory.UNKNOWN);

        assertThat(dataset.getStatus()).isEqualTo(DatasetStatus.READY);
    }
}
