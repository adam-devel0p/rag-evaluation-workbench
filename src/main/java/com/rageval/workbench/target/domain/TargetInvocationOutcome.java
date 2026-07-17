package com.rageval.workbench.target.domain;

/**
 * The terminal outcome of a single {@link TargetSystemAdapter#invoke} call. Deliberately a subset
 * of {@link com.rageval.workbench.run.domain.ExecutionStatus}'s terminal values — {@code target}
 * has no dependency on {@code run} (run orchestration depends on target, not the reverse), so this
 * is its own small enum. Phase 3's run orchestrator maps this onto {@code ExecutionStatus} when
 * persisting a {@code TestExecution}.
 */
public enum TargetInvocationOutcome {
    SUCCEEDED,
    TIMED_OUT,
    TARGET_ERROR,
    INVALID_RESPONSE
}
