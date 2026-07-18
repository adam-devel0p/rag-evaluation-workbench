package com.rageval.workbench.review.api.dto;

import com.rageval.workbench.review.domain.HumanReview;
import com.rageval.workbench.review.domain.ReviewDecision;
import java.time.Instant;
import java.util.UUID;

/** API view of one {@code HumanReview}; never the JPA entity itself. */
public record HumanReviewResponse(
        UUID id,
        UUID metricResultId,
        ReviewDecision decision,
        String reviewerName,
        String comment,
        Boolean overridePassed,
        Double overrideScore,
        Instant reviewedAt,
        Instant createdAt) {

    public static HumanReviewResponse from(HumanReview review) {
        return new HumanReviewResponse(
                review.getId(),
                review.getMetricResultId(),
                review.getDecision(),
                review.getReviewerName(),
                review.getComment(),
                review.getOverridePassed(),
                review.getOverrideScore(),
                review.getReviewedAt(),
                review.getCreatedAt());
    }
}
