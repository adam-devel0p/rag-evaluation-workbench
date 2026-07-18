package com.rageval.workbench.evaluation.application;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * The evaluation module's view of the human-review workflow: which metric results have already
 * received a human decision. Owned here and implemented by the review module so the package
 * dependency direction stays review → evaluation (same inversion pattern as
 * {@code run.application.EvaluationPipeline}) — aggregation needs the reviewed-set for the
 * human-review backlog, nothing more.
 */
public interface HumanReviewLookup {

    Set<UUID> reviewedMetricResultIds(Collection<UUID> metricResultIds);
}
