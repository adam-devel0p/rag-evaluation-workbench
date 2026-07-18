package com.rageval.workbench.evaluation.llm;

/** Per-claim groundedness verdict (spec §13.2). */
public enum GroundingVerdict {
    SUPPORTED,
    PARTIALLY_SUPPORTED,
    CONTRADICTED,
    NOT_IN_CONTEXT
}
