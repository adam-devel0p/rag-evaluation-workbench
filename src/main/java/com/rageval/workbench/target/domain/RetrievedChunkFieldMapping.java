package com.rageval.workbench.target.domain;

/**
 * Per-item JSON Pointers (relative to each retrieved-chunk array element) used when a target's
 * chunk shape does not match the standard normalized {@code chunkId}/{@code documentId}/
 * {@code documentTitle}/{@code content}/{@code rank}/{@code retrievalScore} fields. Any
 * {@code null} field falls back to the standard field name.
 */
public record RetrievedChunkFieldMapping(
        String chunkIdPointer,
        String documentIdPointer,
        String documentTitlePointer,
        String contentPointer,
        String rankPointer,
        String scorePointer) {
}
