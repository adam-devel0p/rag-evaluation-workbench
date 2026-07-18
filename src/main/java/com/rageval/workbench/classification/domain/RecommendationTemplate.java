package com.rageval.workbench.classification.domain;

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
 * The rule-based recommended change for one {@link FailureCategory} (spec §22): recommendations
 * come from templates tied to failure categories — never from an unconstrained LLM — and are
 * manually editable. Defaults are seeded by Liquibase (migration 013); the report reads the
 * current text at render time, so an edited template is reflected without re-running anything.
 */
@Entity
@Table(name = "recommendation_template")
public class RecommendationTemplate {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50, unique = true)
    private FailureCategory category;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RecommendationTemplate() {
        // JPA
    }

    public RecommendationTemplate(FailureCategory category, String recommendation) {
        this.id = UUID.randomUUID();
        this.category = Objects.requireNonNull(category, "category must not be null");
        this.recommendation = requireText(recommendation);
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void updateRecommendation(String recommendation) {
        this.recommendation = requireText(recommendation);
        this.updatedAt = Instant.now();
    }

    private static String requireText(String recommendation) {
        if (recommendation == null || recommendation.isBlank()) {
            throw new IllegalArgumentException("recommendation must not be blank");
        }
        return recommendation;
    }

    public UUID getId() {
        return id;
    }

    public FailureCategory getCategory() {
        return category;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
