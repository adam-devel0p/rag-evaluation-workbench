package com.rageval.workbench.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One previously captured chatbot interaction imported via {@code POST /api/v1/imported-traces}
 * (spec §8.2). Bound 1:1 at import time to the pending {@link TestExecution} created for it, so
 * the IMPORTED_TRACE adapter can resolve exactly this repetition's data when the run executes.
 * Append-only: created by the import, read by the adapter, never updated.
 */
@Entity
@Table(name = "imported_trace")
public class ImportedTrace {

    @Id
    private UUID id;

    @Column(name = "test_execution_id", nullable = false, unique = true)
    private UUID testExecutionId;

    @Column(name = "external_test_case_id", nullable = false)
    private String externalTestCaseId;

    @Column(name = "repetition_number", nullable = false)
    private int repetitionNumber;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(columnDefinition = "TEXT")
    private String answer;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<Citation> citations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "retrieved_chunks", nullable = false)
    private List<RetrievedChunk> retrievedChunks;

    @Column(name = "target_model")
    private String targetModel;

    @Column(name = "latency_milliseconds")
    private Long latencyMilliseconds;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> metadata;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ImportedTrace() {
        // JPA
    }

    public ImportedTrace(
            UUID testExecutionId,
            String externalTestCaseId,
            int repetitionNumber,
            String question,
            String answer,
            List<Citation> citations,
            List<RetrievedChunk> retrievedChunks,
            String targetModel,
            Long latencyMilliseconds,
            Map<String, Object> metadata) {
        this.id = UUID.randomUUID();
        this.testExecutionId = Objects.requireNonNull(testExecutionId, "testExecutionId must not be null");
        this.externalTestCaseId = Objects.requireNonNull(externalTestCaseId, "externalTestCaseId must not be null");
        this.repetitionNumber = repetitionNumber;
        this.question = Objects.requireNonNull(question, "question must not be null");
        this.answer = answer;
        this.citations = citations != null ? List.copyOf(citations) : List.of();
        this.retrievedChunks = retrievedChunks != null ? List.copyOf(retrievedChunks) : List.of();
        this.targetModel = targetModel;
        this.latencyMilliseconds = latencyMilliseconds;
        this.metadata = metadata;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTestExecutionId() {
        return testExecutionId;
    }

    public String getExternalTestCaseId() {
        return externalTestCaseId;
    }

    public int getRepetitionNumber() {
        return repetitionNumber;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
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

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
