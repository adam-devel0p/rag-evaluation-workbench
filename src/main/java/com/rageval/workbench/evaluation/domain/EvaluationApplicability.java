package com.rageval.workbench.evaluation.domain;

import java.util.Objects;

/**
 * Whether an evaluator can meaningfully run against one execution — and, when it cannot, a real
 * reason rather than a bare boolean (spec §10). {@code NOT_APPLICABLE} means the test case does
 * not call for this metric at all; {@code MISSING_REQUIRED_DATA} means the metric would apply but
 * the execution lacks the data to compute it (for example no successful answer, or no retrieved
 * chunks exposed by the target).
 */
public record EvaluationApplicability(Status status, String reason) {

    public enum Status {
        APPLICABLE,
        NOT_APPLICABLE,
        MISSING_REQUIRED_DATA
    }

    public EvaluationApplicability {
        Objects.requireNonNull(status, "status must not be null");
        if (status != Status.APPLICABLE && (reason == null || reason.isBlank())) {
            throw new IllegalArgumentException(status + " requires a non-blank reason");
        }
    }

    public static EvaluationApplicability applicable() {
        return new EvaluationApplicability(Status.APPLICABLE, null);
    }

    public static EvaluationApplicability notApplicable(String reason) {
        return new EvaluationApplicability(Status.NOT_APPLICABLE, reason);
    }

    public static EvaluationApplicability missingRequiredData(String reason) {
        return new EvaluationApplicability(Status.MISSING_REQUIRED_DATA, reason);
    }

    public boolean isApplicable() {
        return status == Status.APPLICABLE;
    }
}
