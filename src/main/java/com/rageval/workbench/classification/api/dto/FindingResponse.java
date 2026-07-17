package com.rageval.workbench.classification.api.dto;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** API view of one {@code EvaluationFinding}; never the JPA entity itself. */
public record FindingResponse(
        UUID id,
        UUID evaluationRunId,
        UUID testExecutionId,
        FailureCategory category,
        Severity severity,
        String title,
        String summary,
        String likelyCause,
        String recommendation,
        String acceptanceCriteria,
        List<EvaluationEvidence> evidence,
        Double confidence,
        boolean requiresHumanConfirmation,
        List<UUID> supportingMetricResultIds,
        boolean humanConfirmed,
        Instant createdAt) {

    public static FindingResponse from(EvaluationFinding finding) {
        return new FindingResponse(
                finding.getId(),
                finding.getEvaluationRunId(),
                finding.getTestExecutionId(),
                finding.getCategory(),
                finding.getSeverity(),
                finding.getTitle(),
                finding.getSummary(),
                finding.getLikelyCause(),
                finding.getRecommendation(),
                finding.getAcceptanceCriteria(),
                finding.getEvidence(),
                finding.getConfidence(),
                finding.isRequiresHumanConfirmation(),
                finding.getSupportingMetricResultIds(),
                finding.isHumanConfirmed(),
                finding.getCreatedAt());
    }
}
