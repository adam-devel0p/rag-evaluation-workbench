package com.rageval.workbench.review.api.dto;

import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.api.dto.MetricResultResponse;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.List;
import java.util.UUID;

/**
 * The complete context a reviewer needs for one flagged metric result (spec §20): question,
 * answer, retrieved context, reference material, evidence, the evaluator's explanation, and any
 * reviews already recorded. The automated result is embedded untouched — a review is layered on
 * top of it, never a replacement.
 */
public record MetricResultReviewContextResponse(
        MetricResultResponse metricResult,
        ExecutionContext execution,
        TestCaseContext testCase,
        List<HumanReviewResponse> reviews) {

    /** What the target system actually did for this repetition. */
    public record ExecutionContext(
            UUID testExecutionId,
            UUID evaluationRunId,
            int repetitionNumber,
            ExecutionStatus status,
            String answer,
            List<Citation> citations,
            List<RetrievedChunk> retrievedChunks,
            Long latencyMilliseconds,
            Integer httpStatus,
            String errorType,
            String errorMessage) {
    }

    /** What the test case expected. */
    public record TestCaseContext(
            UUID testCaseId,
            String externalId,
            String question,
            String referenceAnswer,
            boolean answerable,
            boolean shouldRefuse,
            Severity severity,
            List<ExpectedFact> requiredFacts,
            List<ExpectedFact> forbiddenClaims,
            List<String> expectedDocumentIds,
            List<String> tags,
            String notes) {
    }
}
