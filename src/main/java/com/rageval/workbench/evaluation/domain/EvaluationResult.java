package com.rageval.workbench.evaluation.domain;

import java.util.List;
import java.util.Objects;

/**
 * One evaluator's verdict on one execution (spec §10). {@code score} and {@code confidence} are
 * nullable — present only when they carry meaning — but an explanation is always required: no
 * evaluator may return an unexplained score.
 */
public record EvaluationResult(
        boolean passed,
        Double score,
        Double confidence,
        String explanation,
        List<EvaluationEvidence> evidence,
        boolean requiresHumanReview) {

    public EvaluationResult {
        Objects.requireNonNull(explanation, "explanation must not be null — no unexplained results");
        if (explanation.isBlank()) {
            throw new IllegalArgumentException("explanation must not be blank — no unexplained results");
        }
        requireUnitRange(score, "score");
        requireUnitRange(confidence, "confidence");
        evidence = evidence != null ? List.copyOf(evidence) : List.of();
    }

    private static void requireUnitRange(Double value, String name) {
        if (value != null && (value < 0.0 || value > 1.0)) {
            throw new IllegalArgumentException(name + " must be within [0.0, 1.0], got " + value);
        }
    }
}
