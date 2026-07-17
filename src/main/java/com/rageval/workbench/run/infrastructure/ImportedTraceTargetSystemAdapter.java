package com.rageval.workbench.run.infrastructure;

import com.rageval.workbench.run.domain.ImportedTrace;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.TargetInvocationOutcome;
import com.rageval.workbench.target.domain.TargetInvocationRequest;
import com.rageval.workbench.target.domain.TargetInvocationResult;
import com.rageval.workbench.target.domain.TargetSystemAdapter;
import com.rageval.workbench.target.domain.TargetSystemConfiguration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serves previously imported traces (spec §8.2) through the same {@link TargetSystemAdapter}
 * contract as a live HTTP target, so run orchestration, cancellation, resume, and later evaluation
 * phases treat imported runs identically to live ones. Lives in the {@code run} module (not
 * {@code target}) because it reads run-owned data; the registry discovers it by type like any
 * other adapter. The reported latency is the captured one from the trace — for imported data the
 * recorded latency <em>is</em> the measurement, there is no live call to time.
 */
@Component
public class ImportedTraceTargetSystemAdapter implements TargetSystemAdapter {

    private final ImportedTraceRepository importedTraceRepository;
    private final JsonMapper jsonMapper;

    public ImportedTraceTargetSystemAdapter(ImportedTraceRepository importedTraceRepository, JsonMapper jsonMapper) {
        this.importedTraceRepository = importedTraceRepository;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public AdapterType adapterType() {
        return AdapterType.IMPORTED_TRACE;
    }

    @Override
    public TargetInvocationResult invoke(TargetSystemConfiguration configuration, TargetInvocationRequest request) {
        if (request.executionId() == null) {
            return traceNotFound("Imported-trace invocation without an execution id (e.g. connection test) is not supported");
        }
        Optional<ImportedTrace> trace = importedTraceRepository.findByTestExecutionId(request.executionId());
        if (trace.isEmpty()) {
            return traceNotFound("No imported trace found for execution " + request.executionId());
        }
        ImportedTrace importedTrace = trace.get();
        JsonNode rawResponse = canonicalTraceJson(importedTrace);
        return new TargetInvocationResult(
                TargetInvocationOutcome.SUCCEEDED,
                null,
                importedTrace.getAnswer(),
                importedTrace.getCitations(),
                importedTrace.getRetrievedChunks(),
                importedTrace.getTargetModel(),
                null,
                null,
                importedTrace.getLatencyMilliseconds() != null ? importedTrace.getLatencyMilliseconds() : 0L,
                rawResponse,
                null,
                null,
                null,
                1);
    }

    /** Rebuilds the canonical §8.2 trace shape as the execution's raw response, mirroring what a live target would have returned. */
    private JsonNode canonicalTraceJson(ImportedTrace trace) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.put("externalTestCaseId", trace.getExternalTestCaseId());
        node.put("repetitionNumber", trace.getRepetitionNumber());
        node.put("question", trace.getQuestion());
        node.put("answer", trace.getAnswer());
        node.set("citations", jsonMapper.valueToTree(trace.getCitations()));
        node.set("retrievedChunks", jsonMapper.valueToTree(trace.getRetrievedChunks()));
        node.put("targetModel", trace.getTargetModel());
        if (trace.getLatencyMilliseconds() != null) {
            node.put("latencyMilliseconds", trace.getLatencyMilliseconds());
        }
        node.set("metadata", jsonMapper.valueToTree(trace.getMetadata() != null ? trace.getMetadata() : Map.of()));
        return node;
    }

    private TargetInvocationResult traceNotFound(String message) {
        return new TargetInvocationResult(
                TargetInvocationOutcome.INVALID_RESPONSE,
                null,
                null,
                List.of(),
                List.of(),
                null,
                null,
                null,
                0L,
                null,
                null,
                "TRACE_NOT_FOUND",
                message,
                1);
    }
}
