package com.rageval.workbench.common.error;

/** A single field-level validation failure, surfaced in a {@code ProblemDetail}'s {@code errors} extension. */
public record FieldViolation(String field, String message) {
}
