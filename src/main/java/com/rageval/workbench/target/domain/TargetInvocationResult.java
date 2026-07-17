package com.rageval.workbench.target.domain;

import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The normalized result of a {@link TargetSystemAdapter#invoke} call. Latency is measured by the
 * adapter itself around the call, never trusted from anything the target reports. {@code
 * attemptCount} and {@code lastError} let the caller persist retry history (or at minimum the
 * final attempt count and last error) without the adapter needing to know about persistence.
 *
 * <p>Reuses {@link Citation}/{@link RetrievedChunk} from the {@code run} module rather than
 * duplicating near-identical records: both are plain value types with one canonical shape (spec
 * §6.5/§8), and {@code target} depending on {@code run}'s domain value objects (not entities, not
 * the reverse) does not create a cycle — {@code run} only ever references a target by UUID.
 */
public record TargetInvocationResult(
        TargetInvocationOutcome outcome,
        JsonNode requestPayload,
        String answer,
        List<Citation> citations,
        List<RetrievedChunk> retrievedChunks,
        String targetModel,
        Integer inputTokens,
        Integer outputTokens,
        long latencyMilliseconds,
        JsonNode rawResponse,
        Integer httpStatus,
        String errorType,
        String errorMessage,
        int attemptCount) {
}
