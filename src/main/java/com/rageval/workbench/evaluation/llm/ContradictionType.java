package com.rageval.workbench.evaluation.llm;

/** The four contradiction relations spec §13.8 requires the evaluator to detect. */
public enum ContradictionType {
    ANSWER_VS_REFERENCE,
    ANSWER_VS_CONTEXT,
    CONTEXT_VS_CONTEXT,
    ANSWER_VS_SELF
}
