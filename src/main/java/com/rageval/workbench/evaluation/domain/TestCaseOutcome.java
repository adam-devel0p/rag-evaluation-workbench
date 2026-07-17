package com.rageval.workbench.evaluation.domain;

/**
 * The transparent overall verdict for a test case or single execution (spec §18): PASSED only if
 * all enabled mandatory evaluators that completed successfully pass; an evaluator infrastructure
 * failure makes it INCONCLUSIVE, never FAILED.
 */
public enum TestCaseOutcome {
    PASSED,
    FAILED,
    INCONCLUSIVE,
    NOT_EVALUATED
}
