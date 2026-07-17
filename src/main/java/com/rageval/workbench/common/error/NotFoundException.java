package com.rageval.workbench.common.error;

/** A referenced aggregate (target, dataset, test case, ...) does not exist. Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
