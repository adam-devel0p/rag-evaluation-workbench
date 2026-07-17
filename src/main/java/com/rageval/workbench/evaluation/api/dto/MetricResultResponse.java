package com.rageval.workbench.evaluation.api.dto;

import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API view of one {@code EvaluationMetricResult}; never the JPA entity itself. */
public record MetricResultResponse(
        UUID id,
        UUID testExecutionId,
        String evaluatorKey,
        EvaluatorType evaluatorType,
        String evaluatorVersion,
        String evaluatorModel,
        String promptVersion,
        MetricResultStatus status,
        Boolean passed,
        Double score,
        Double confidence,
        String explanation,
        List<EvaluationEvidence> evidence,
        boolean requiresHumanReview,
        Long durationMilliseconds,
        String errorMessage,
        Instant createdAt) {

    public static MetricResultResponse from(EvaluationMetricResult result) {
        return new MetricResultResponse(
                result.getId(),
                result.getTestExecutionId(),
                result.getEvaluatorKey(),
                result.getEvaluatorType(),
                result.getEvaluatorVersion(),
                result.getEvaluatorModel(),
                result.getPromptVersion(),
                result.getStatus(),
                result.getPassed(),
                result.getScore(),
                result.getConfidence(),
                result.getExplanation(),
                result.getEvidence(),
                result.isRequiresHumanReview(),
                result.getDurationMilliseconds(),
                result.getErrorMessage(),
                result.getCreatedAt());
    }
}
