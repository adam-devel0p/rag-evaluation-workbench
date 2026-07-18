package com.rageval.workbench.review.application;

import com.rageval.workbench.evaluation.application.HumanReviewLookup;
import com.rageval.workbench.review.domain.HumanReview;
import com.rageval.workbench.review.infrastructure.HumanReviewRepository;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/** Implements the evaluation module's {@link HumanReviewLookup} over the review store. */
@Component
public class HumanReviewLookupAdapter implements HumanReviewLookup {

    private final HumanReviewRepository repository;

    public HumanReviewLookupAdapter(HumanReviewRepository repository) {
        this.repository = repository;
    }

    @Override
    public Set<UUID> reviewedMetricResultIds(Collection<UUID> metricResultIds) {
        if (metricResultIds.isEmpty()) {
            return Set.of();
        }
        return repository.findByMetricResultIdIn(metricResultIds).stream()
                .map(HumanReview::getMetricResultId)
                .collect(Collectors.toSet());
    }
}
