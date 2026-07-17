package com.rageval.workbench.run.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Creation payload for a LIVE evaluation run. {@code runMode} is deliberately not exposed:
 * imported-trace (REPLAY) runs are created by {@code POST /api/v1/imported-traces}, and replaying
 * a previous run gets its own endpoint in a later phase. {@code repetitionsPerTestCase} falls back
 * to {@code rag-evaluation.execution.default-repetitions} when omitted.
 */
public record CreateRunRequest(
        @NotNull UUID targetSystemId,
        @NotNull UUID datasetId,
        @NotBlank String name,
        Integer repetitionsPerTestCase,
        List<String> enabledEvaluatorKeys,
        Map<String, Object> evaluatorConfiguration) {
}
