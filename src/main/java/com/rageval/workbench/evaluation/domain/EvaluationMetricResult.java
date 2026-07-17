package com.rageval.workbench.evaluation.domain;

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

/**
 * The result of running one evaluator against one {@code TestExecution}. Distinguishes a chatbot
 * failing the metric from the evaluator itself failing or being not applicable — never conflate
 * the two.
 */
@Entity
@Table(name = "evaluation_metric_result")
public class EvaluationMetricResult {

    @Id
    private UUID id;

    @Column(name = "test_execution_id", nullable = false)
    private UUID testExecutionId;

    @Column(name = "evaluator_key", nullable = false)
    private String evaluatorKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator_type", nullable = false, length = 20)
    private EvaluatorType evaluatorType;

    @Column(name = "evaluator_version", nullable = false)
    private String evaluatorVersion;

    @Column(name = "evaluator_model")
    private String evaluatorModel;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MetricResultStatus status;

    private Boolean passed;

    private Double score;

    private Double confidence;

    @Column(columnDefinition = "TEXT")
    private String explanation;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<EvaluationEvidence> evidence;

    @Column(name = "requires_human_review", nullable = false)
    private boolean requiresHumanReview;

    @Column(name = "duration_milliseconds")
    private Long durationMilliseconds;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    protected EvaluationMetricResult() {
        // JPA
    }

    public EvaluationMetricResult(
            UUID testExecutionId,
            String evaluatorKey,
            EvaluatorType evaluatorType,
            String evaluatorVersion,
            String evaluatorModel,
            String promptVersion,
            MetricResultStatus status,
            Boolean passed,
            Double score,
            Double confidence,
            String explanation,
            List<EvaluationEvidence> evidence,
            boolean requiresHumanReview,
            Long durationMilliseconds,
            String errorMessage) {
        this.id = UUID.randomUUID();
        this.testExecutionId = Objects.requireNonNull(testExecutionId, "testExecutionId must not be null");
        this.evaluatorKey = Objects.requireNonNull(evaluatorKey, "evaluatorKey must not be null");
        this.evaluatorType = Objects.requireNonNull(evaluatorType, "evaluatorType must not be null");
        this.evaluatorVersion = Objects.requireNonNull(evaluatorVersion, "evaluatorVersion must not be null");
        this.evaluatorModel = evaluatorModel;
        this.promptVersion = promptVersion;
        this.status = Objects.requireNonNull(status, "status must not be null");
        this.passed = passed;
        this.score = score;
        this.confidence = confidence;
        this.explanation = explanation;
        this.evidence = evidence != null ? List.copyOf(evidence) : List.of();
        this.requiresHumanReview = requiresHumanReview;
        this.durationMilliseconds = durationMilliseconds;
        this.errorMessage = errorMessage;
        this.createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTestExecutionId() {
        return testExecutionId;
    }

    public String getEvaluatorKey() {
        return evaluatorKey;
    }

    public EvaluatorType getEvaluatorType() {
        return evaluatorType;
    }

    public String getEvaluatorVersion() {
        return evaluatorVersion;
    }

    public String getEvaluatorModel() {
        return evaluatorModel;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public MetricResultStatus getStatus() {
        return status;
    }

    public Boolean getPassed() {
        return passed;
    }

    public Double getScore() {
        return score;
    }

    public Double getConfidence() {
        return confidence;
    }

    public String getExplanation() {
        return explanation;
    }

    public List<EvaluationEvidence> getEvidence() {
        return evidence;
    }

    public boolean isRequiresHumanReview() {
        return requiresHumanReview;
    }

    public Long getDurationMilliseconds() {
        return durationMilliseconds;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
