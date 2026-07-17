package com.rageval.workbench.common.error;

import java.util.List;

/**
 * Request-level validation failure with one or more field-level violations (e.g. dataset import
 * payload validation, an invalid regex in a required/forbidden fact). Mapped to HTTP 400 with a
 * structured {@code errors} list by {@link GlobalExceptionHandler}.
 */
public class ValidationException extends RuntimeException {

    private final List<FieldViolation> violations;

    public ValidationException(String message, List<FieldViolation> violations) {
        super(message);
        this.violations = List.copyOf(violations);
    }

    public ValidationException(String message, FieldViolation violation) {
        this(message, List.of(violation));
    }

    public List<FieldViolation> getViolations() {
        return violations;
    }
}
