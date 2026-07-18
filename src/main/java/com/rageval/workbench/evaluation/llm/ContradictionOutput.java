package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * Structured judge output of the contradiction evaluator (spec §13.8): explicit pairs of
 * conflicting statements, empty when none were found. The evaluator never decides which
 * conflicting document is current — that requires document metadata black-box evaluation does
 * not have.
 */
public record ContradictionOutput(
        List<ContradictionPair> contradictions,
        String explanation,
        Double confidence) {
}
