package com.rageval.workbench.evaluation;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.CitationPolicy;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluatorSettings;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RetrievedChunk;
import com.rageval.workbench.run.domain.RunMode;
import com.rageval.workbench.run.domain.TestExecution;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Shared builders for evaluator/classification unit tests — plain objects, no Spring, no database. */
public final class EvaluationFixtures {

    private EvaluationFixtures() {
    }

    public static EvaluatorSettings defaultSettings() {
        return settings(2000, CitationPolicy.OPTIONAL, Map.of());
    }

    public static EvaluatorSettings settings(
            long maximumLatencyMillis, CitationPolicy citationPolicy, Map<String, CitationPolicy> byTag) {
        return new EvaluatorSettings(
                maximumLatencyMillis,
                citationPolicy,
                byTag,
                List.of("i cannot", "i can't", "i am unable", "cannot answer", "cannot share", "unable to provide"),
                List.of("i don't know", "could not find", "no information"),
                160,
                0.70,
                List.of("transport", "required-facts", "forbidden-claims"),
                List.of("security", "permission"),
                30000,
                false);
    }

    public static EvaluationRun run() {
        return new EvaluationRun(
                UUID.randomUUID(), UUID.randomUUID(), "unit-test run", RunMode.LIVE, 1, List.of(), null);
    }

    public static EvaluationTestCase testCase(
            boolean answerable,
            boolean shouldRefuse,
            Severity severity,
            List<ExpectedFact> requiredFacts,
            List<ExpectedFact> forbiddenClaims,
            List<String> expectedDocumentIds,
            List<String> tags) {
        return new EvaluationTestCase(
                UUID.randomUUID(),
                "TC-1",
                "What is the answer?",
                "The reference answer.",
                answerable,
                shouldRefuse,
                severity,
                requiredFacts,
                forbiddenClaims,
                expectedDocumentIds,
                tags,
                null,
                null);
    }

    public static EvaluationTestCase answerableTestCase(
            List<ExpectedFact> requiredFacts, List<ExpectedFact> forbiddenClaims, List<String> expectedDocumentIds) {
        return testCase(true, false, Severity.MEDIUM, requiredFacts, forbiddenClaims, expectedDocumentIds, List.of());
    }

    public static TestExecution succeededExecution(
            String answer, List<Citation> citations, List<RetrievedChunk> chunks, Long latencyMillis) {
        TestExecution execution = new TestExecution(UUID.randomUUID(), UUID.randomUUID(), null, 1);
        execution.markRunning();
        execution.recordSuccess(
                null,
                answer,
                null,
                citations,
                chunks,
                "test-model",
                latencyMillis != null ? latencyMillis : 100L,
                10,
                20,
                200,
                1,
                Instant.now(),
                Instant.now());
        return execution;
    }

    public static TestExecution failedExecution(ExecutionStatus status, Integer httpStatus) {
        TestExecution execution = new TestExecution(UUID.randomUUID(), UUID.randomUUID(), null, 1);
        execution.markRunning();
        execution.recordFailure(
                status, null, null, status.name(), "deliberate test failure", httpStatus, 1, Instant.now(),
                Instant.now());
        return execution;
    }

    public static EvaluationContext context(EvaluationTestCase testCase, TestExecution execution) {
        return new EvaluationContext(run(), testCase, execution, defaultSettings(), List.of());
    }

    public static EvaluationContext context(
            EvaluationTestCase testCase, TestExecution execution, EvaluatorSettings settings) {
        return new EvaluationContext(run(), testCase, execution, settings, List.of());
    }

    public static ExpectedFact fact(String id, String value, FactMatchType type, boolean mandatory) {
        return new ExpectedFact(id, value, type, mandatory, null);
    }

    public static Citation citation(String documentId) {
        return new Citation(documentId, documentId + " title", null, null, null, Map.of());
    }

    public static RetrievedChunk chunk(String documentId, int rank) {
        return new RetrievedChunk("chunk-" + documentId + "-" + rank, documentId, documentId + " title",
                "content of " + documentId, rank, 0.9, Map.of());
    }
}
