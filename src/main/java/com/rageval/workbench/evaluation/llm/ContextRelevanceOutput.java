package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * Structured judge output of the context-relevance evaluator (spec §13.6): one decision per
 * retrieved chunk. The derived ratios (relevant/retrieved, relevant-in-top-3, irrelevant
 * top-ranked) are computed deterministically in Java from these decisions, not by the model.
 */
public record ContextRelevanceOutput(
        List<ChunkRelevanceDecision> chunks,
        String explanation,
        Double confidence) {
}
