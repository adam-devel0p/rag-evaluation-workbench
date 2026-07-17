package com.rageval.workbench.target.api.dto;

import java.util.List;

/**
 * Reports whether the configured pointer mappings actually work against a real call, so the
 * caller can fix a wrong pointer before creating a run. {@code warnings} flags mappings that were
 * configured but did not resolve (as opposed to intentionally-unconfigured optional fields).
 */
public record TestConnectionResponse(
        boolean success,
        Integer httpStatus,
        long latencyMilliseconds,
        String answerPreview,
        int citationsExtracted,
        int retrievedChunksExtracted,
        String targetModel,
        Integer inputTokens,
        Integer outputTokens,
        String errorType,
        String errorMessage,
        int attemptCount,
        List<String> warnings) {
}
