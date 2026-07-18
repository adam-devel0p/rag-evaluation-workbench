package com.rageval.workbench.evaluation.llm;

/**
 * One independently verifiable factual claim extracted from the chatbot answer (spec §13.1).
 * {@code claimType} is a free label; spec-suggested values are FACT, INSTRUCTION, NUMBER,
 * VERSION, OWNERSHIP, POLICY, OTHER.
 */
public record ExtractedClaim(String claimId, String text, String claimType) {
}
