package com.rageval.workbench.common.json;

import java.util.Map;

/** The values a {@link RequestTemplateSubstitutor} splices into a request template's placeholders. */
public record PlaceholderValues(
        String question, String conversationId, String testCaseId, Map<String, Object> userContext) {
}
