package com.rageval.workbench.classification.api;

import com.rageval.workbench.classification.api.dto.RecommendationTemplateResponse;
import com.rageval.workbench.classification.api.dto.UpdateRecommendationTemplateRequest;
import com.rageval.workbench.classification.application.RecommendationTemplateService;
import com.rageval.workbench.classification.domain.FailureCategory;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The manually editable rule-based recommendation templates (spec §22). One row per failure
 * category — the list is small and fixed by the enum, so no pagination.
 */
@RestController
@RequestMapping("/api/v1/recommendation-templates")
public class RecommendationTemplateController {

    private final RecommendationTemplateService service;

    public RecommendationTemplateController(RecommendationTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public List<RecommendationTemplateResponse> list() {
        return service.findAll().stream().map(RecommendationTemplateResponse::from).toList();
    }

    @PutMapping("/{category}")
    public RecommendationTemplateResponse update(
            @PathVariable FailureCategory category,
            @Valid @RequestBody UpdateRecommendationTemplateRequest request) {
        return RecommendationTemplateResponse.from(service.update(category, request.recommendation()));
    }
}
