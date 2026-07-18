package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * Structured judge output of the answer-relevance evaluator (spec §13.5). Relevance never implies
 * correctness — the metric only judges whether the answer addresses the question.
 */
public record AnswerRelevanceOutput(
        boolean relevant,
        double score,
        List<String> unaddressedParts,
        String explanation,
        Double confidence) {
}
