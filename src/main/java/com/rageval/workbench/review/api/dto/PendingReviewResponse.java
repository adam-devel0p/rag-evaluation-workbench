package com.rageval.workbench.review.api.dto;

import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One entry in the pending-review queue. Deliberately a triage view — question and verdict, not
 * the full answer/context payload; the complete reviewable context is served per result by
 * {@code GET /api/v1/metric-results/{id}/review-context}.
 */
public record PendingReviewResponse(
        UUID metricResultId,
        UUID evaluationRunId,
        UUID testExecutionId,
        int repetitionNumber,
        String testCaseExternalId,
        String question,
        Severity severity,
        List<String> tags,
        String evaluatorKey,
        EvaluatorType evaluatorType,
        MetricResultStatus status,
        Boolean passed,
        Double score,
        Double confidence,
        String explanation,
        String errorMessage,
        Instant flaggedAt) {
}
