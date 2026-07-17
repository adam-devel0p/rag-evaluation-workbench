package com.rageval.workbench.evaluation.domain;

/** Whether citations are required, merely allowed, or forbidden for a test case (spec §11.6). */
public enum CitationPolicy {
    REQUIRED,
    OPTIONAL,
    FORBIDDEN
}
