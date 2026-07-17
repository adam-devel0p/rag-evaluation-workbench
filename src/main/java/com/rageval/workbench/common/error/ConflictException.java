package com.rageval.workbench.common.error;

/**
 * The requested change conflicts with the aggregate's current state (e.g. modifying a
 * {@code READY} dataset, deleting a target with dependent runs). Mapped to HTTP 409.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
