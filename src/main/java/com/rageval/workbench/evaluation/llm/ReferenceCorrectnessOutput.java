package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * Structured judge output of the reference-correctness evaluator (spec §13.3). {@code confidence}
 * is an additive field feeding the spec §14 low-confidence human-review trigger.
 */
public record ReferenceCorrectnessOutput(
        boolean passed,
        double score,
        List<String> correctFacts,
        List<String> missingFacts,
        List<String> incorrectClaims,
        String explanation,
        Double confidence) {
}
