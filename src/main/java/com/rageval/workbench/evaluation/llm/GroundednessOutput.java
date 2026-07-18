package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * Structured judge output of the groundedness evaluator (spec §13.2). {@code confidence} is an
 * additive field feeding the spec §14 low-confidence human-review trigger.
 */
public record GroundednessOutput(
        List<ClaimGroundingDecision> claims,
        double overallScore,
        boolean passed,
        String explanation,
        Double confidence) {
}
