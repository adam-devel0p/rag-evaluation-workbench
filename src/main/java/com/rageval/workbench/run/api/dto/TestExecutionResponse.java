package com.rageval.workbench.run.api.dto;

import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RetrievedChunk;
import com.rageval.workbench.run.domain.TestExecution;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read model for one execution. Deliberately omits {@code requestPayload}/{@code rawResponse} —
 * those can carry client content and are not needed by the list view; a dedicated detail endpoint
 * can expose them later if a real need appears.
 */
public record TestExecutionResponse(
        UUID id,
        UUID evaluationRunId,
        UUID testCaseId,
        UUID sourceExecutionId,
        int repetitionNumber,
        ExecutionStatus status,
        String answer,
        List<Citation> citations,
        List<RetrievedChunk> retrievedChunks,
        String targetModel,
        Long latencyMilliseconds,
        Integer inputTokens,
        Integer outputTokens,
        Integer httpStatus,
        Integer attemptCount,
        String errorType,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Instant createdAt) {

    public static TestExecutionResponse from(TestExecution execution) {
        return new TestExecutionResponse(
                execution.getId(),
                execution.getEvaluationRunId(),
                execution.getTestCaseId(),
                execution.getSourceExecutionId(),
                execution.getRepetitionNumber(),
                execution.getStatus(),
                execution.getAnswer(),
                execution.getCitations(),
                execution.getRetrievedChunks(),
                execution.getTargetModel(),
                execution.getLatencyMilliseconds(),
                execution.getInputTokens(),
                execution.getOutputTokens(),
                execution.getHttpStatus(),
                execution.getAttemptCount(),
                execution.getErrorType(),
                execution.getErrorMessage(),
                execution.getStartedAt(),
                execution.getCompletedAt(),
                execution.getCreatedAt());
    }
}
