package com.rageval.workbench.target.domain;

import java.util.Map;

/**
 * The normalized request handed to a {@link TargetSystemAdapter}, independent of how the
 * underlying target is actually reached.
 */
public record TargetInvocationRequest(
        String testCaseId, String question, String conversationId, Map<String, Object> userContext) {
}
