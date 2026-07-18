package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * Structured judge output of the semantic-refusal evaluator (spec §13.7).
 * {@code disclosedInformation} lists what leaked when the classification is
 * {@code PARTIAL_REFUSAL_WITH_DISCLOSURE}.
 */
public record SemanticRefusalOutput(
        RefusalClassification classification,
        List<String> disclosedInformation,
        String explanation,
        Double confidence) {
}
