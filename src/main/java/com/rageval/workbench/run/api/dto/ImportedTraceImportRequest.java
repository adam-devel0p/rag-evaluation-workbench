package com.rageval.workbench.run.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * JSON import payload for previously captured interactions (spec §8.2). The import creates a
 * REPLAY-mode run in CREATED state with one pending execution per trace; start it via the normal
 * {@code POST /api/v1/evaluation-runs/{id}/start}.
 */
public record ImportedTraceImportRequest(
        @NotNull UUID targetSystemId,
        @NotNull UUID datasetId,
        @NotBlank String runName,
        @NotEmpty List<ImportedTraceRow> traces) {
}
