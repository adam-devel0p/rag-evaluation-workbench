package com.rageval.workbench.dataset.domain;

import com.rageval.workbench.common.error.ConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A named, versioned collection of {@link EvaluationTestCase}s. Once {@link DatasetStatus#READY},
 * a dataset must not be silently modified — create a new dataset version instead.
 */
@Entity
@Table(name = "evaluation_dataset")
public class EvaluationDataset {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(name = "dataset_version", nullable = false)
    private String datasetVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DatasetStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected EvaluationDataset() {
        // JPA
    }

    public EvaluationDataset(String name, String description, String datasetVersion) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.datasetVersion = Objects.requireNonNull(datasetVersion, "datasetVersion must not be null");
        this.status = DatasetStatus.DRAFT;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void markReady() {
        if (status != DatasetStatus.DRAFT) {
            throw new ConflictException(
                    "Dataset " + id + " is " + status + " and can only be marked READY from DRAFT");
        }
        this.status = DatasetStatus.READY;
        this.updatedAt = Instant.now();
    }

    public void archive() {
        this.status = DatasetStatus.ARCHIVED;
        this.updatedAt = Instant.now();
    }

    public boolean isModifiable() {
        return status == DatasetStatus.DRAFT;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getDatasetVersion() {
        return datasetVersion;
    }

    public DatasetStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
