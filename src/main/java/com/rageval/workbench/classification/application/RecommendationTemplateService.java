package com.rageval.workbench.classification.application;

import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.domain.RecommendationTemplate;
import com.rageval.workbench.classification.infrastructure.RecommendationTemplateRepository;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.ValidationException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and edits the per-category recommendation templates (spec §22). Editing replaces the
 * text of an existing category's template; a category whose seeded row is somehow absent gets a
 * new row rather than an error, so every {@link FailureCategory} is always editable.
 */
@Service
public class RecommendationTemplateService {

    private final RecommendationTemplateRepository repository;

    public RecommendationTemplateService(RecommendationTemplateRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<RecommendationTemplate> findAll() {
        return repository.findAll().stream()
                .sorted(Comparator.comparing(template -> template.getCategory().name()))
                .toList();
    }

    /** Current template text per category, for report rendering; categories without a row are absent. */
    @Transactional(readOnly = true)
    public Map<FailureCategory, String> currentTexts() {
        Map<FailureCategory, String> texts = new EnumMap<>(FailureCategory.class);
        repository.findAll().forEach(template -> texts.put(template.getCategory(), template.getRecommendation()));
        return texts;
    }

    @Transactional
    public RecommendationTemplate update(FailureCategory category, String recommendation) {
        if (recommendation == null || recommendation.isBlank()) {
            throw new ValidationException(
                    "Recommendation template validation failed",
                    new FieldViolation("recommendation", "must not be blank"));
        }
        RecommendationTemplate template = repository
                .findByCategory(category)
                .orElseGet(() -> new RecommendationTemplate(category, recommendation));
        template.updateRecommendation(recommendation);
        return repository.save(template);
    }
}
