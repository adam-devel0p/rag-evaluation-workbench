package com.rageval.workbench.review.api.dto;

import com.rageval.workbench.review.domain.ReviewDecision;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A human decision on one metric result (spec §20). The override fields adjust the effective
 * pass/score without ever touching the automated result — both are persisted and both are shown
 * in the report.
 */
public record SubmitReviewRequest(
        @NotNull(message = "decision is required") ReviewDecision decision,
        @NotBlank(message = "reviewerName is required") String reviewerName,
        String comment,
        Boolean overridePassed,
        @DecimalMin(value = "0.0", message = "overrideScore must be within [0, 1]")
                @DecimalMax(value = "1.0", message = "overrideScore must be within [0, 1]")
                Double overrideScore) {
}
