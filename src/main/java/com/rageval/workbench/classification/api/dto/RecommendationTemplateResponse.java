package com.rageval.workbench.classification.api.dto;

import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.domain.RecommendationTemplate;
import java.time.Instant;
import java.util.UUID;

/** API view of one {@code RecommendationTemplate}; never the JPA entity itself. */
public record RecommendationTemplateResponse(
        UUID id, FailureCategory category, String recommendation, Instant createdAt, Instant updatedAt) {

    public static RecommendationTemplateResponse from(RecommendationTemplate template) {
        return new RecommendationTemplateResponse(
                template.getId(),
                template.getCategory(),
                template.getRecommendation(),
                template.getCreatedAt(),
                template.getUpdatedAt());
    }
}
