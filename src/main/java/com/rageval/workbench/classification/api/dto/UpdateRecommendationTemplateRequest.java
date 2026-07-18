package com.rageval.workbench.classification.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Full-replace edit of one failure category's recommendation text. */
public record UpdateRecommendationTemplateRequest(
        @NotBlank(message = "recommendation must not be blank") String recommendation) {
}
