package com.rageval.workbench.dataset.api.dto;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record TestCaseResponse(
        UUID id,
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
        String notes,
        Instant createdAt,
        Instant updatedAt) {

    public static TestCaseResponse from(EvaluationTestCase testCase) {
        return new TestCaseResponse(
                testCase.getId(),
                testCase.getDatasetId(),
                testCase.getExternalId(),
                testCase.getQuestion(),
                testCase.getReferenceAnswer(),
                testCase.isAnswerable(),
                testCase.isShouldRefuse(),
                testCase.getSeverity(),
                testCase.getRequiredFacts(),
                testCase.getForbiddenClaims(),
                testCase.getExpectedDocumentIds(),
                testCase.getTags(),
                testCase.getUserContext(),
                testCase.getNotes(),
                testCase.getCreatedAt(),
                testCase.getUpdatedAt());
    }
}
