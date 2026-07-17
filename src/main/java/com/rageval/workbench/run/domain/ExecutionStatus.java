package com.rageval.workbench.run.domain;

public enum ExecutionStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    TIMED_OUT,
    TARGET_ERROR,
    INVALID_RESPONSE,
    CANCELLED
}
