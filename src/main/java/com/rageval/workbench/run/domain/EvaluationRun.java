package com.rageval.workbench.run.domain;

import com.rageval.workbench.common.error.ConflictException;
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
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One evaluation execution against a {@code TargetSystem} using an {@code EvaluationDataset}.
 * Owns its {@link RunStatus} state machine: every transition goes through {@link #transitionTo},
 * which rejects anything not in the allowed-transition table, so an illegal transition can never
 * be persisted regardless of which service attempts it.
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

    private static final Map<RunStatus, Set<RunStatus>> ALLOWED_TRANSITIONS = Map.of(
            RunStatus.CREATED, Set.of(RunStatus.QUEUED, RunStatus.CANCELLED),
            RunStatus.QUEUED, Set.of(RunStatus.RUNNING, RunStatus.CANCELLED, RunStatus.INTERRUPTED),
            RunStatus.RUNNING,
                    Set.of(
                            RunStatus.COMPLETED,
                            RunStatus.PARTIALLY_COMPLETED,
                            RunStatus.FAILED,
                            RunStatus.CANCELLED,
                            RunStatus.INTERRUPTED),
            RunStatus.PARTIALLY_COMPLETED, Set.of(RunStatus.QUEUED),
            RunStatus.FAILED, Set.of(RunStatus.QUEUED),
            RunStatus.CANCELLED, Set.of(RunStatus.QUEUED),
            RunStatus.INTERRUPTED, Set.of(RunStatus.QUEUED),
            RunStatus.COMPLETED, Set.of());

    public void transitionTo(RunStatus newStatus) {
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        if (!ALLOWED_TRANSITIONS.get(status).contains(newStatus)) {
            throw new ConflictException(
                    "Run " + id + " cannot transition from " + status + " to " + newStatus);
        }
        this.status = newStatus;
    }

    /** CREATED → QUEUED on first start; also re-queues a resumable run, clearing its previous outcome. */
    public void markQueued() {
        transitionTo(RunStatus.QUEUED);
        this.completedAt = null;
        this.failureReason = null;
    }

    public void markStarted() {
        transitionTo(RunStatus.RUNNING);
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }

    public void markCompleted(RunStatus terminalStatus) {
        if (terminalStatus != RunStatus.COMPLETED && terminalStatus != RunStatus.PARTIALLY_COMPLETED) {
            throw new IllegalArgumentException(
                    "markCompleted only accepts COMPLETED or PARTIALLY_COMPLETED, got " + terminalStatus);
        }
        transitionTo(terminalStatus);
        this.completedAt = Instant.now();
    }

    public void markFailed(String reason) {
        transitionTo(RunStatus.FAILED);
        this.failureReason = reason;
        this.completedAt = Instant.now();
    }

    public void markCancelled() {
        transitionTo(RunStatus.CANCELLED);
        this.completedAt = Instant.now();
    }

    /** Startup recovery for runs left QUEUED/RUNNING by a dead process; deliberately keeps {@code completedAt} null. */
    public void markInterrupted() {
        transitionTo(RunStatus.INTERRUPTED);
    }

    public boolean isResumable() {
        return status == RunStatus.PARTIALLY_COMPLETED
                || status == RunStatus.FAILED
                || status == RunStatus.CANCELLED
                || status == RunStatus.INTERRUPTED;
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
