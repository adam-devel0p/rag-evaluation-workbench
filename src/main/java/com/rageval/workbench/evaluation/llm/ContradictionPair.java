package com.rageval.workbench.evaluation.llm;

/**
 * One explicit pair of conflicting statements (spec §13.8). {@code sourceA}/{@code sourceB} name
 * where each statement came from (a chunk ID, "answer", or "reference answer").
 */
public record ContradictionPair(
        ContradictionType type,
        String statementA,
        String sourceA,
        String statementB,
        String sourceB,
        String explanation) {
}
