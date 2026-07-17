package com.rageval.workbench.run.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One evaluation execution against a {@code TargetSystem} using an {@code EvaluationDataset}.
 * Owns the {@link ExecutionStatus} state machine transitions enforced by the orchestration layer
 * (Phase 3).
 */
@Entity
@Table(name = "evaluation_run")
public class EvaluationRun {

    @Id
    private UUID id;

    @Column(name = "target_system_id", nullable = false)
    private UUID targetSystemId;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RunStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 20)
    private RunMode runMode;

    @Column(name = "repetitions_per_test_case", nullable = false)
    private int repetitionsPerTestCase;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enabled_evaluator_keys", nullable = false)
    private List<String> enabledEvaluatorKeys;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evaluator_configuration")
    private Map<String, Object> evaluatorConfiguration;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    @Version
    private long version;

    protected EvaluationRun() {
        // JPA
    }

    public EvaluationRun(
            UUID targetSystemId,
            UUID datasetId,
            String name,
            RunMode runMode,
            int repetitionsPerTestCase,
            List<String> enabledEvaluatorKeys,
            Map<String, Object> evaluatorConfiguration) {
        this.id = UUID.randomUUID();
        this.targetSystemId = Objects.requireNonNull(targetSystemId, "targetSystemId must not be null");
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.status = RunStatus.CREATED;
        this.runMode = Objects.requireNonNull(runMode, "runMode must not be null");
        this.repetitionsPerTestCase = repetitionsPerTestCase;
        this.enabledEvaluatorKeys =
                enabledEvaluatorKeys != null ? List.copyOf(enabledEvaluatorKeys) : List.of();
        this.evaluatorConfiguration = evaluatorConfiguration;
        this.createdAt = Instant.now();
    }

    public void transitionTo(RunStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
    }

    public void markStarted() {
        this.status = RunStatus.RUNNING;
        this.startedAt = Instant.now();
    }

    public void markCompleted(RunStatus terminalStatus) {
        this.status = Objects.requireNonNull(terminalStatus, "terminalStatus must not be null");
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        this.status = RunStatus.FAILED;
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTargetSystemId() {
        return targetSystemId;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public String getName() {
        return name;
    }

    public RunStatus getStatus() {
        return status;
    }

    public RunMode getRunMode() {
        return runMode;
    }

    public int getRepetitionsPerTestCase() {
        return repetitionsPerTestCase;
    }

    public List<String> getEnabledEvaluatorKeys() {
        return enabledEvaluatorKeys;
    }

    public Map<String, Object> getEvaluatorConfiguration() {
        return evaluatorConfiguration;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public long getVersion() {
        return version;
    }
}
