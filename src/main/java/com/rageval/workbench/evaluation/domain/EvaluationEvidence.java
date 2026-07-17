package com.rageval.workbench.evaluation.domain;

import java.util.Map;

/**
 * Structured evidence backing an evaluator's verdict. No evaluator may return an unexplained
 * score — this is how the "why" is carried alongside the score.
 */
public record EvaluationEvidence(
        String type,
        String description,
        String documentId,
        String chunkId,
        Integer chunkRank,
        String quotedText,
        Map<String, Object> metadata) {
}
