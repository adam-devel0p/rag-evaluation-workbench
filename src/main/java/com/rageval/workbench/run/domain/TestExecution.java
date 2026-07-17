package com.rageval.workbench.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * A single repetition of a single test case within an {@link EvaluationRun}. For replay runs,
 * {@code sourceExecutionId} points back at the originally captured live execution.
 */
@Entity
@Table(name = "test_execution")
public class TestExecution {

    @Id
    private UUID id;

    @Column(name = "evaluation_run_id", nullable = false)
    private UUID evaluationRunId;

    @Column(name = "test_case_id", nullable = false)
    private UUID testCaseId;

    @Column(name = "source_execution_id")
    private UUID sourceExecutionId;

    @Column(name = "repetition_number", nullable = false)
    private int repetitionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExecutionStatus status;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload")
    private JsonNode requestPayload;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response")
    private JsonNode rawResponse;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<Citation> citations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_chunks")
    private List<RetrievedChunk> retrievedChunks;

    @Column(name = "target_model")
    private String targetModel;

    @Column(name = "latency_milliseconds")
    private Long latencyMilliseconds;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "error_type")
    private String errorType;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "attempt_count")
    private Integer attemptCount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TestExecution() {
        // JPA
    }

    public TestExecution(
            UUID evaluationRunId, UUID testCaseId, UUID sourceExecutionId, int repetitionNumber) {
        this.id = UUID.randomUUID();
        this.evaluationRunId = Objects.requireNonNull(evaluationRunId, "evaluationRunId must not be null");
        this.testCaseId = Objects.requireNonNull(testCaseId, "testCaseId must not be null");
        this.sourceExecutionId = sourceExecutionId;
        this.repetitionNumber = repetitionNumber;
        this.status = ExecutionStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public void markRunning() {
        this.status = ExecutionStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    /** A PENDING/RUNNING execution abandoned by cancellation or an interrupted process. */
    public void markCancelled(String reason) {
        this.status = ExecutionStatus.CANCELLED;
        this.errorType = "CANCELLED";
        this.errorMessage = reason;
        this.completedAt = Instant.now();
    }

    /** Puts a CANCELLED execution back to PENDING so a resumed run can schedule it again. */
    public void resetForResume() {
        this.status = ExecutionStatus.PENDING;
        this.errorType = null;
        this.errorMessage = null;
        this.startedAt = null;
        this.completedAt = null;
    }

    public void recordSuccess(
            JsonNode requestPayload,
            String answer,
            JsonNode rawResponse,
            List<Citation> citations,
            List<RetrievedChunk> retrievedChunks,
            String targetModel,
            long latencyMilliseconds,
            Integer inputTokens,
            Integer outputTokens,
            Integer httpStatus,
            Integer attemptCount,
            Instant startedAt,
            Instant completedAt) {
        this.status = ExecutionStatus.SUCCEEDED;
        this.requestPayload = requestPayload;
        this.answer = answer;
        this.rawResponse = rawResponse;
        this.citations = citations != null ? List.copyOf(citations) : List.of();
        this.retrievedChunks = retrievedChunks != null ? List.copyOf(retrievedChunks) : List.of();
        this.targetModel = targetModel;
        this.latencyMilliseconds = latencyMilliseconds;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.httpStatus = httpStatus;
        this.attemptCount = attemptCount;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public void recordFailure(
            ExecutionStatus failureStatus,
            JsonNode requestPayload,
            JsonNode rawResponse,
            String errorType,
            String errorMessage,
            Integer httpStatus,
            Integer attemptCount,
            Instant startedAt,
            Instant completedAt) {
        this.status = Objects.requireNonNull(failureStatus, "failureStatus must not be null");
        this.requestPayload = requestPayload;
        this.rawResponse = rawResponse;
        this.errorType = errorType;
        this.errorMessage = errorMessage;
        this.httpStatus = httpStatus;
        this.attemptCount = attemptCount;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getEvaluationRunId() {
        return evaluationRunId;
    }

    public UUID getTestCaseId() {
        return testCaseId;
    }

    public UUID getSourceExecutionId() {
        return sourceExecutionId;
    }

    public int getRepetitionNumber() {
        return repetitionNumber;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public JsonNode getRequestPayload() {
        return requestPayload;
    }

    public String getAnswer() {
        return answer;
    }

    public JsonNode getRawResponse() {
        return rawResponse;
    }

    public List<Citation> getCitations() {
        return citations;
    }

    public List<RetrievedChunk> getRetrievedChunks() {
        return retrievedChunks;
    }

    public String getTargetModel() {
        return targetModel;
    }

    public Long getLatencyMilliseconds() {
        return latencyMilliseconds;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public String getErrorType() {
        return errorType;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
