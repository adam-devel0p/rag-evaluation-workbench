package com.rageval.workbench.evaluation.llm;

import java.util.List;

/** Structured judge output of the claim-extraction evaluator (spec §13.1). */
public record ClaimExtractionOutput(List<ExtractedClaim> claims) {
}
