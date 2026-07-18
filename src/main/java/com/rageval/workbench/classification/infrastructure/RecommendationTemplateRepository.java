package com.rageval.workbench.classification.infrastructure;

import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.domain.RecommendationTemplate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationTemplateRepository extends JpaRepository<RecommendationTemplate, UUID> {

    Optional<RecommendationTemplate> findByCategory(FailureCategory category);
}
