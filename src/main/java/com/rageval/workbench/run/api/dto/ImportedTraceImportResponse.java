package com.rageval.workbench.run.api.dto;

/** Result of an imported-trace import: the created REPLAY run plus how many traces were stored. */
public record ImportedTraceImportResponse(RunResponse run, int importedTraceCount) {
}
