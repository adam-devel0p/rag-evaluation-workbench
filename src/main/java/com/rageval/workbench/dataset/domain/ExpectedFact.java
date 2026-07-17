package com.rageval.workbench.dataset.domain;

/**
 * A single required or forbidden fact within an {@link EvaluationTestCase}, persisted as an
 * element of a JSONB array.
 */
public record ExpectedFact(
        String id, String value, FactMatchType matchType, boolean mandatory, String explanation) {
}
