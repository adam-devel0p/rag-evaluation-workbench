package com.rageval.workbench.review.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.domain.FailureCategory;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.review.domain.HumanReview;
import com.rageval.workbench.review.domain.ReviewDecision;
import com.rageval.workbench.review.infrastructure.HumanReviewRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Spec §20's core invariant — a human override never deletes or mutates the automated result —
 * plus the finding-confirmation side effect of a CONFIRMED decision.
 */
class HumanReviewServiceTest {

    private EvaluationMetricResultRepository metricResultRepository;
    private HumanReviewRepository reviewRepository;
    private EvaluationFindingRepository findingRepository;
    private HumanReviewService service;

    private EvaluationMetricResult metricResult;

    @BeforeEach
    void setUp() {
        metricResultRepository = mock(EvaluationMetricResultRepository.class);
        reviewRepository = mock(HumanReviewRepository.class);
        findingRepository = mock(EvaluationFindingRepository.class);
        service = new HumanReviewService(metricResultRepository, reviewRepository, findingRepository);
        metricResult = new EvaluationMetricResult(
                UUID.randomUUID(),
                "required-facts",
                EvaluatorType.DETERMINISTIC,
                "1",
                null,
                null,
                MetricResultStatus.COMPLETED,
                false,
                0.0,
                1.0,
                "missing fact",
                List.of(),
                true,
                1L,
                null);
        when(metricResultRepository.findById(metricResult.getId())).thenReturn(Optional.of(metricResult));
        when(reviewRepository.save(any(HumanReview.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(findingRepository.findByTestExecutionId(any())).thenReturn(List.of());
    }

    @Test
    void unknownMetricResultAnswersNotFound() {
        UUID unknown = UUID.randomUUID();
        when(metricResultRepository.findById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.submit(unknown, ReviewDecision.CONFIRMED, "reviewer", null, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void submittingAReviewNeverTouchesTheAutomatedResult() {
        HumanReview review = service.submit(
                metricResult.getId(), ReviewDecision.REJECTED, "reviewer", "wrong verdict", true, 0.9);
        // The override lives on the review row; the automated result is never saved (= mutated).
        verify(metricResultRepository, never()).save(any());
        assertThat(review.getOverridePassed()).isTrue();
        assertThat(review.getOverrideScore()).isEqualTo(0.9);
        assertThat(metricResult.getPassed()).isFalse();
        assertThat(metricResult.getScore()).isEqualTo(0.0);
    }

    @Test
    void confirmedDecisionConfirmsFindingsSupportedByTheResult() {
        EvaluationFinding supported = finding(true, List.of(metricResult.getId()));
        EvaluationFinding unrelated = finding(true, List.of(UUID.randomUUID()));
        EvaluationFinding noConfirmationNeeded = finding(false, List.of(metricResult.getId()));
        when(findingRepository.findByTestExecutionId(metricResult.getTestExecutionId()))
                .thenReturn(List.of(supported, unrelated, noConfirmationNeeded));

        service.submit(metricResult.getId(), ReviewDecision.CONFIRMED, "reviewer", null, null, null);

        assertThat(supported.isHumanConfirmed()).isTrue();
        assertThat(unrelated.isHumanConfirmed()).isFalse();
        assertThat(noConfirmationNeeded.isHumanConfirmed()).isFalse();
        verify(findingRepository).save(supported);
        verify(findingRepository, never()).save(unrelated);
    }

    @Test
    void nonConfirmedDecisionsConfirmNothing() {
        EvaluationFinding supported = finding(true, List.of(metricResult.getId()));
        when(findingRepository.findByTestExecutionId(metricResult.getTestExecutionId()))
                .thenReturn(List.of(supported));

        service.submit(metricResult.getId(), ReviewDecision.UNCERTAIN, "reviewer", null, null, null);
        service.submit(metricResult.getId(), ReviewDecision.NEEDS_DOMAIN_EXPERT, "reviewer", null, null, null);

        assertThat(supported.isHumanConfirmed()).isFalse();
        verify(findingRepository, never()).save(any());
    }

    private EvaluationFinding finding(boolean requiresConfirmation, List<UUID> supportingIds) {
        return new EvaluationFinding(
                UUID.randomUUID(),
                metricResult.getTestExecutionId(),
                FailureCategory.INCORRECT_ANSWER,
                Severity.HIGH,
                "title",
                "summary",
                null,
                null,
                null,
                List.of(),
                0.5,
                requiresConfirmation,
                supportingIds);
    }
}
