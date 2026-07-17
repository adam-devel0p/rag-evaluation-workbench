package com.rageval.workbench.target.domain;

import java.util.Map;
import java.util.UUID;

/**
 * The normalized request handed to a {@link TargetSystemAdapter}, independent of how the
 * underlying target is actually reached. {@code executionId} identifies the {@code TestExecution}
 * this invocation belongs to (null for one-off calls like the connection test); the imported-trace
 * adapter uses it to look up the captured trace for exactly this repetition, while the standard
 * HTTP adapter ignores it.
 */
public record TargetInvocationRequest(
        UUID executionId,
        String testCaseId,
        String question,
        String conversationId,
        Map<String, Object> userContext) {
}
