package com.rageval.workbench.run.api.dto;

import java.util.List;
import java.util.Map;

/**
 * Optional creation payload for {@code POST /api/v1/evaluation-runs/{id}/replay}. Every field
 * falls back to the source run when absent: {@code name} defaults to the source run's name with a
 * "(replay)" suffix, and a {@code null} {@code enabledEvaluatorKeys} / {@code
 * evaluatorConfiguration} inherits the source run's value (an explicit empty list means "all
 * evaluators", exactly as on {@link CreateRunRequest}). Overriding these is the whole point of
 * replay: re-judging captured responses with updated or differently configured evaluators.
 */
public record ReplayRunRequest(
        String name,
        List<String> enabledEvaluatorKeys,
        Map<String, Object> evaluatorConfiguration) {
}
