package com.rageval.workbench.review.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A human decision recorded against an {@code EvaluationMetricResult}. A review is additive —
 * it never deletes or mutates the automated result it evaluates.
 */
@Entity
@Table(name = "human_review")
public class HumanReview {

    @Id
    private UUID id;

    @Column(name = "metric_result_id", nullable = false)
    private UUID metricResultId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewDecision decision;

    @Column(name = "reviewer_name", nullable = false)
    private String reviewerName;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "override_passed")
    private Boolean overridePassed;

    @Column(name = "override_score")
    private Double overrideScore;

    @Column(name = "reviewed_at", nullable = false)
    private Instant reviewedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected HumanReview() {
        // JPA
    }

    public HumanReview(
            UUID metricResultId,
            ReviewDecision decision,
            String reviewerName,
            String comment,
            Boolean overridePassed,
            Double overrideScore) {
        this.id = UUID.randomUUID();
        this.metricResultId = Objects.requireNonNull(metricResultId, "metricResultId must not be null");
        this.decision = Objects.requireNonNull(decision, "decision must not be null");
        this.reviewerName = Objects.requireNonNull(reviewerName, "reviewerName must not be null");
        this.comment = comment;
        this.overridePassed = overridePassed;
        this.overrideScore = overrideScore;
        Instant now = Instant.now();
        this.reviewedAt = now;
        this.createdAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getMetricResultId() {
        return metricResultId;
    }

    public ReviewDecision getDecision() {
        return decision;
    }

    public String getReviewerName() {
        return reviewerName;
    }

    public String getComment() {
        return comment;
    }

    public Boolean getOverridePassed() {
        return overridePassed;
    }

    public Double getOverrideScore() {
        return overrideScore;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
