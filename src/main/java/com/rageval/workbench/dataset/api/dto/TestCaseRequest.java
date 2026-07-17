package com.rageval.workbench.dataset.api.dto;

import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/** Full desired state of a test case, used for both add and update — no partial-field patching. */
public record TestCaseRequest(
        @NotBlank String externalId,
        @NotBlank String question,
        String referenceAnswer,
        boolean answerable,
        boolean shouldRefuse,
        @NotNull Severity severity,
        List<ExpectedFact> requiredFacts,
        List<ExpectedFact> forbiddenClaims,
        List<String> expectedDocumentIds,
        List<String> tags,
        Map<String, Object> userContext,
        String notes) {
}
