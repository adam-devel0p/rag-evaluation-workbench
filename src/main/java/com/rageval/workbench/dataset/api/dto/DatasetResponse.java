package com.rageval.workbench.dataset.api.dto;

import com.rageval.workbench.dataset.domain.DatasetStatus;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import java.time.Instant;
import java.util.UUID;

public record DatasetResponse(
        UUID id,
        String name,
        String description,
        String datasetVersion,
        DatasetStatus status,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static DatasetResponse from(EvaluationDataset dataset) {
        return new DatasetResponse(
                dataset.getId(),
                dataset.getName(),
                dataset.getDescription(),
                dataset.getDatasetVersion(),
                dataset.getStatus(),
                dataset.getCreatedAt(),
                dataset.getUpdatedAt(),
                dataset.getVersion());
    }
}
