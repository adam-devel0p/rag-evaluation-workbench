package com.rageval.workbench.demo;

import java.util.Map;

/**
 * What the demo target accepts — deliberately the same placeholder set the standard HTTP
 * adapter's request template substitutes, so a real {@code TargetSystem} can point at it with the
 * default template.
 */
public record DemoChatRequest(
        String question, String conversationId, String testCaseId, Map<String, Object> userContext) {
}
