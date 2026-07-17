package com.rageval.workbench.dataset.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/** The canonical dataset+test-cases JSON import payload (spec §9): one call creates the dataset and every test case, or nothing. */
public record DatasetJsonImportRequest(
        @NotBlank String name,
        String description,
        @NotBlank String datasetVersion,
        @NotEmpty List<TestCaseRequest> testCases) {
}
