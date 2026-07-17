package com.rageval.workbench.run.domain;

public enum RunStatus {
    CREATED,
    QUEUED,
    RUNNING,
    PARTIALLY_COMPLETED,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED
}
