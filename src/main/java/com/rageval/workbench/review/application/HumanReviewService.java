package com.rageval.workbench.review.application;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.review.domain.HumanReview;
import com.rageval.workbench.review.domain.ReviewDecision;
import com.rageval.workbench.review.infrastructure.HumanReviewRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records human decisions on metric results (spec §20). A review is strictly additive: the
 * automated result is never mutated or deleted — overrides live on the review row, and the report
 * shows both verdicts side by side. Reviews are append-only; a metric result can accumulate more
 * than one (e.g. UNCERTAIN first, then a domain expert's CONFIRMED), with the latest decision
 * treated as the effective one by consumers.
 *
 * <p>A {@link ReviewDecision#CONFIRMED} decision also confirms any finding on the same execution
 * that lists this metric result as supporting evidence and awaits human confirmation — the
 * reviewer has looked at exactly the evidence the finding rests on.
 */
@Service
public class HumanReviewService {

    private static final Logger log = LoggerFactory.getLogger(HumanReviewService.class);

    private final EvaluationMetricResultRepository metricResultRepository;
    private final HumanReviewRepository reviewRepository;
    private final EvaluationFindingRepository findingRepository;

    public HumanReviewService(
            EvaluationMetricResultRepository metricResultRepository,
            HumanReviewRepository reviewRepository,
            EvaluationFindingRepository findingRepository) {
        this.metricResultRepository = metricResultRepository;
        this.reviewRepository = reviewRepository;
        this.findingRepository = findingRepository;
    }

    @Transactional
    public HumanReview submit(
            UUID metricResultId,
            ReviewDecision decision,
            String reviewerName,
            String comment,
            Boolean overridePassed,
            Double overrideScore) {
        EvaluationMetricResult metricResult = metricResultRepository
                .findById(metricResultId)
                .orElseThrow(() -> new NotFoundException("Metric result not found: " + metricResultId));
        HumanReview review = reviewRepository.save(
                new HumanReview(metricResult.getId(), decision, reviewerName, comment, overridePassed, overrideScore));
        if (decision == ReviewDecision.CONFIRMED) {
            confirmSupportedFindings(metricResult);
        }
        log.info(
                "Metric result {} ({}): human review {} recorded",
                metricResult.getId(),
                metricResult.getEvaluatorKey(),
                decision);
        return review;
    }

    private void confirmSupportedFindings(EvaluationMetricResult metricResult) {
        for (EvaluationFinding finding : findingRepository.findByTestExecutionId(metricResult.getTestExecutionId())) {
            if (finding.isRequiresHumanConfirmation()
                    && !finding.isHumanConfirmed()
                    && finding.getSupportingMetricResultIds().contains(metricResult.getId())) {
                finding.confirmByHuman();
                findingRepository.save(finding);
            }
        }
    }
}
