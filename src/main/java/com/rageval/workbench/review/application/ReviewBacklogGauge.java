package com.rageval.workbench.review.application;

import com.rageval.workbench.common.observability.WorkbenchMetrics;
import com.rageval.workbench.review.infrastructure.ReviewQueueRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Registers the human-review backlog gauge (spec §24). Lives in the review module because the
 * backlog count query does; the metric name and tag policy live with every other metric in
 * {@link WorkbenchMetrics}. The gauge runs one count query per metrics scrape — fine for a
 * local-first workbench with an occasional Prometheus poll.
 */
@Component
public class ReviewBacklogGauge {

    private final WorkbenchMetrics metrics;
    private final ReviewQueueRepository reviewQueueRepository;

    public ReviewBacklogGauge(WorkbenchMetrics metrics, ReviewQueueRepository reviewQueueRepository) {
        this.metrics = metrics;
        this.reviewQueueRepository = reviewQueueRepository;
    }

    @PostConstruct
    void register() {
        metrics.registerReviewBacklogGauge(reviewQueueRepository::countPending);
    }
}
