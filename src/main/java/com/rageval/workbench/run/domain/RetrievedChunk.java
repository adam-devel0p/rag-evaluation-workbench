package com.rageval.workbench.run.domain;

import java.util.Map;

/** A retrieved context chunk returned by the target system, persisted as a JSONB array element. */
public record RetrievedChunk(
        String chunkId,
        String documentId,
        String documentTitle,
        String content,
        Integer rank,
        Double retrievalScore,
        Map<String, Object> metadata) {
}
