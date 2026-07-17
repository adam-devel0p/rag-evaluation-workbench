package com.rageval.workbench.dataset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * A single question, with its expected behavior, belonging to an {@link EvaluationDataset}.
 * Concurrency control for edits is governed by the owning dataset's version, not an independent
 * version on the test case itself (the dataset is the aggregate root; a {@code READY} dataset
 * rejects test-case modification rather than relying on optimistic locking here).
 */
@Entity
@Table(name = "evaluation_test_case")
public class EvaluationTestCase {

    @Id
    private UUID id;

    @Column(name = "dataset_id", nullable = false)
    private UUID datasetId;

    @Column(name = "external_id", nullable = false)
    private String externalId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String question;

    @Column(name = "reference_answer", columnDefinition = "TEXT")
    private String referenceAnswer;

    @Column(nullable = false)
    private boolean answerable;

    @Column(name = "should_refuse", nullable = false)
    private boolean shouldRefuse;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "required_facts", nullable = false)
    private List<ExpectedFact> requiredFacts;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "forbidden_claims", nullable = false)
    private List<ExpectedFact> forbiddenClaims;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expected_document_ids", nullable = false)
    private List<String> expectedDocumentIds;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<String> tags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "user_context")
    private Map<String, Object> userContext;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected EvaluationTestCase() {
        // JPA
    }

    public EvaluationTestCase(
            UUID datasetId,
            String externalId,
            String question,
            String referenceAnswer,
            boolean answerable,
            boolean shouldRefuse,
            Severity severity,
            List<ExpectedFact> requiredFacts,
            List<ExpectedFact> forbiddenClaims,
            List<String> expectedDocumentIds,
            List<String> tags,
            Map<String, Object> userContext,
            String notes) {
        this.id = UUID.randomUUID();
        this.datasetId = Objects.requireNonNull(datasetId, "datasetId must not be null");
        this.externalId = Objects.requireNonNull(externalId, "externalId must not be null");
        this.question = Objects.requireNonNull(question, "question must not be null");
        this.referenceAnswer = referenceAnswer;
        this.answerable = answerable;
        this.shouldRefuse = shouldRefuse;
        this.severity = Objects.requireNonNull(severity, "severity must not be null");
        this.requiredFacts = requiredFacts != null ? List.copyOf(requiredFacts) : List.of();
        this.forbiddenClaims = forbiddenClaims != null ? List.copyOf(forbiddenClaims) : List.of();
        this.expectedDocumentIds = expectedDocumentIds != null ? List.copyOf(expectedDocumentIds) : List.of();
        this.tags = tags != null ? List.copyOf(tags) : List.of();
        this.userContext = userContext;
        this.notes = notes;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Full replace, mirroring {@link EvaluationDataset}'s all-or-nothing modification model. Callers must have already checked {@link EvaluationDataset#isModifiable()}. */
    public void update(
            String externalId,
            String question,
            String referenceAnswer,
            boolean answerable,
            boolean shouldRefuse,
            Severity severity,
            List<ExpectedFact> requiredFacts,
            List<ExpectedFact> forbiddenClaims,
            List<String> expectedDocumentIds,
            List<String> tags,
            Map<String, Object> userContext,
            String notes) {
        this.externalId = Objects.requireNonNull(externalId, "externalId must not be null");
        this.question = Objects.requireNonNull(question, "question must not be null");
        this.referenceAnswer = referenceAnswer;
        this.answerable = answerable;
        this.shouldRefuse = shouldRefuse;
        this.severity = Objects.requireNonNull(severity, "severity must not be null");
        this.requiredFacts = requiredFacts != null ? List.copyOf(requiredFacts) : List.of();
        this.forbiddenClaims = forbiddenClaims != null ? List.copyOf(forbiddenClaims) : List.of();
        this.expectedDocumentIds = expectedDocumentIds != null ? List.copyOf(expectedDocumentIds) : List.of();
        this.tags = tags != null ? List.copyOf(tags) : List.of();
        this.userContext = userContext;
        this.notes = notes;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

    public String getExternalId() {
        return externalId;
    }

    public String getQuestion() {
        return question;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public boolean isAnswerable() {
        return answerable;
    }

    public boolean isShouldRefuse() {
        return shouldRefuse;
    }

    public Severity getSeverity() {
        return severity;
    }

    public List<ExpectedFact> getRequiredFacts() {
        return requiredFacts;
    }

    public List<ExpectedFact> getForbiddenClaims() {
        return forbiddenClaims;
    }

    public List<String> getExpectedDocumentIds() {
        return expectedDocumentIds;
    }

    public List<String> getTags() {
        return tags;
    }

    public Map<String, Object> getUserContext() {
        return userContext;
    }

    public String getNotes() {
        return notes;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
