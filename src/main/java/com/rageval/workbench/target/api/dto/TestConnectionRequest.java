package com.rageval.workbench.target.api.dto;

import java.util.Map;

/** Optional overrides for the connection-test call; any omitted field falls back to a sensible default. */
public record TestConnectionRequest(String sampleQuestion, String conversationId, Map<String, Object> userContext) {

    private static final String DEFAULT_SAMPLE_QUESTION = "Connection test question from RAG Evaluation Workbench.";

    public String effectiveSampleQuestion() {
        return (sampleQuestion != null && !sampleQuestion.isBlank()) ? sampleQuestion : DEFAULT_SAMPLE_QUESTION;
    }
}
