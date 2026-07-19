package com.rageval.workbench.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * What captured target content is persisted (spec §23/§25). Both default to {@code true} because
 * replay and the evidence-first report depend on stored content; an engagement with stricter data
 * constraints turns them off and accepts the documented consequences:
 *
 * <ul>
 *   <li>{@code store-raw-responses=false} — the target's raw JSON body is never written to the
 *       database. The normalized fields (answer, citations, chunks, tokens, latency) are still
 *       persisted; what is lost is the ability to re-extract anything from the original payload
 *       later.
 *   <li>{@code store-retrieved-chunk-content=false} — retrieved chunks are persisted without their
 *       {@code content} text (IDs, titles, ranks, and scores are kept). Retrieval metrics still
 *       work; content-dependent LLM evaluators (groundedness, context relevance) will report
 *       missing required data instead of judging.
 * </ul>
 *
 * <p>The settings apply at capture time. Already-persisted executions are unaffected, and a replay
 * copies whatever its source execution stored.
 */
@ConfigurationProperties(prefix = "rag-evaluation.storage")
public record StorageProperties(
        @DefaultValue("true") boolean storeRawResponses,
        @DefaultValue("true") boolean storeRetrievedChunkContent) {
}
