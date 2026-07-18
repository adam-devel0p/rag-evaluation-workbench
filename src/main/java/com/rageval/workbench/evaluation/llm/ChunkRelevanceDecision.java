package com.rageval.workbench.evaluation.llm;

/** One retrieved chunk's relevance decision against the question (spec §13.6). */
public record ChunkRelevanceDecision(String chunkId, boolean relevant, String explanation) {
}
