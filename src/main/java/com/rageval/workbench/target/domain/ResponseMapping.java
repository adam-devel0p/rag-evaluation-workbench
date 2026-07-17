package com.rageval.workbench.target.domain;

/**
 * Optional adapter response-extraction configuration beyond the mandatory answer pointer:
 * input/output token pointers and non-standard per-item citation/chunk field mappings. Persisted
 * as JSONB on {@link TargetSystem}; {@code null} (or a {@code null} nested mapping) means "use the
 * standard normalized shape, no token extraction".
 */
public record ResponseMapping(
        String inputTokensJsonPointer,
        String outputTokensJsonPointer,
        CitationFieldMapping citationFieldMapping,
        RetrievedChunkFieldMapping retrievedChunkFieldMapping) {
}
