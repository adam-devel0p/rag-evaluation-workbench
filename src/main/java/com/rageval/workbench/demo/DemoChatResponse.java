package com.rageval.workbench.demo;

import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.List;

/**
 * The normalized target schema of spec §27, reusing the canonical {@link Citation}/
 * {@link RetrievedChunk} value records so the standard adapter's default JSON pointers extract it
 * without any per-field mapping. {@code usage} is an additive extra so token-count extraction can
 * be exercised against the demo target too.
 */
public record DemoChatResponse(
        String answer,
        List<Citation> citations,
        List<RetrievedChunk> retrievedChunks,
        String model,
        Usage usage) {

    public record Usage(int inputTokens, int outputTokens) {
    }
}
