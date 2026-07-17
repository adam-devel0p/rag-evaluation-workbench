package com.rageval.workbench.dataset.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Creates an empty {@code DRAFT} dataset; test cases are added afterward, or via a bulk import endpoint. */
public record CreateDatasetRequest(@NotBlank String name, String description, @NotBlank String datasetVersion) {
}
