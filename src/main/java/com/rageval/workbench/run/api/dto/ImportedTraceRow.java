package com.rageval.workbench.run.api.dto;

import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.List;
import java.util.Map;

/** One captured interaction in the canonical JSON import format of spec §8.2. */
public record ImportedTraceRow(
        String externalTestCaseId,
        Integer repetitionNumber,
        String question,
        String answer,
        List<Citation> citations,
        List<RetrievedChunk> retrievedChunks,
        String targetModel,
        Long latencyMilliseconds,
        Map<String, Object> metadata) {
}
