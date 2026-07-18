package com.rageval.workbench.evaluation.llm;

/** Semantic refusal classification (spec §13.7). */
public enum RefusalClassification {
    CORRECT_REFUSAL,
    PARTIAL_REFUSAL_WITH_DISCLOSURE,
    INCORRECT_REFUSAL,
    NO_REFUSAL
}
