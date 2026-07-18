package com.rageval.workbench.evaluation.llm;

import java.util.List;

/**
 * One claim's grounding decision (spec §13.2), identifying supporting or contradicting chunk IDs
 * where possible.
 */
public record ClaimGroundingDecision(
        String claimId,
        String claimText,
        GroundingVerdict verdict,
        List<String> supportingChunkIds,
        List<String> contradictingChunkIds,
        String explanation) {
}
