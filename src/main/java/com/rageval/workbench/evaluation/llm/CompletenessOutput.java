package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * Structured judge output of the completeness evaluator (spec §13.4): whether all material parts
 * of the expected answer are covered, without penalizing harmless wording differences.
 */
public record CompletenessOutput(
        boolean passed,
        double score,
        List<String> coveredPoints,
        List<String> omittedRequiredContent,
        String explanation,
        Double confidence) {
}
