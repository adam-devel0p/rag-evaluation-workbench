package com.rageval.workbench.review.application;

import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.api.dto.MetricResultResponse;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.review.api.dto.HumanReviewResponse;
import com.rageval.workbench.review.api.dto.MetricResultReviewContextResponse;
import com.rageval.workbench.review.api.dto.PendingReviewResponse;
import com.rageval.workbench.review.infrastructure.HumanReviewRepository;
import com.rageval.workbench.review.infrastructure.ReviewQueueRepository;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the human-review workflow (spec §20): the filterable pending queue and the
 * complete per-result review context.
 */
@Service
public class ReviewQueryService {

    private final ReviewQueueRepository queueRepository;
    private final EvaluationMetricResultRepository metricResultRepository;
    private final TestExecutionRepository executionRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final HumanReviewRepository reviewRepository;

    public ReviewQueryService(
            ReviewQueueRepository queueRepository,
            EvaluationMetricResultRepository metricResultRepository,
            TestExecutionRepository executionRepository,
            EvaluationTestCaseRepository testCaseRepository,
            HumanReviewRepository reviewRepository) {
        this.queueRepository = queueRepository;
        this.metricResultRepository = metricResultRepository;
        this.executionRepository = executionRepository;
        this.testCaseRepository = testCaseRepository;
        this.reviewRepository = reviewRepository;
    }

    @Transactional(readOnly = true)
    public Page<PendingReviewResponse> pending(
            UUID runId, Severity severity, String evaluatorKey, MetricResultStatus status, Pageable pageable) {
        return queueRepository
                .findPending(runId, severity, evaluatorKey, status, pageable)
                .map(ReviewQueryService::toPendingResponse);
    }

    @Transactional(readOnly = true)
    public MetricResultReviewContextResponse reviewContext(UUID metricResultId) {
        EvaluationMetricResult metricResult = metricResultRepository
                .findById(metricResultId)
                .orElseThrow(() -> new NotFoundException("Metric result not found: " + metricResultId));
        TestExecution execution = executionRepository
                .findById(metricResult.getTestExecutionId())
                .orElseThrow(() -> new NotFoundException(
                        "Test execution not found: " + metricResult.getTestExecutionId()));
        EvaluationTestCase testCase = testCaseRepository
                .findById(execution.getTestCaseId())
                .orElseThrow(() -> new NotFoundException("Test case not found: " + execution.getTestCaseId()));
        return new MetricResultReviewContextResponse(
                MetricResultResponse.from(metricResult),
                new MetricResultReviewContextResponse.ExecutionContext(
                        execution.getId(),
                        execution.getEvaluationRunId(),
                        execution.getRepetitionNumber(),
                        execution.getStatus(),
                        execution.getAnswer(),
                        execution.getCitations(),
                        execution.getRetrievedChunks(),
                        execution.getLatencyMilliseconds(),
                        execution.getHttpStatus(),
                        execution.getErrorType(),
                        execution.getErrorMessage()),
                new MetricResultReviewContextResponse.TestCaseContext(
                        testCase.getId(),
                        testCase.getExternalId(),
                        testCase.getQuestion(),
                        testCase.getReferenceAnswer(),
                        testCase.isAnswerable(),
                        testCase.isShouldRefuse(),
                        testCase.getSeverity(),
                        testCase.getRequiredFacts(),
                        testCase.getForbiddenClaims(),
                        testCase.getExpectedDocumentIds(),
                        testCase.getTags(),
                        testCase.getNotes()),
                reviewsOf(metricResultId));
    }

    @Transactional(readOnly = true)
    public List<HumanReviewResponse> reviewsOf(UUID metricResultId) {
        return reviewRepository.findByMetricResultId(metricResultId).stream()
                .sorted(Comparator.comparing(review -> review.getCreatedAt()))
                .map(HumanReviewResponse::from)
                .toList();
    }

    private static PendingReviewResponse toPendingResponse(ReviewQueueRepository.PendingReviewRow row) {
        return new PendingReviewResponse(
                row.metricResult().getId(),
                row.execution().getEvaluationRunId(),
                row.execution().getId(),
                row.execution().getRepetitionNumber(),
                row.testCase().getExternalId(),
                row.testCase().getQuestion(),
                row.testCase().getSeverity(),
                row.testCase().getTags(),
                row.metricResult().getEvaluatorKey(),
                row.metricResult().getEvaluatorType(),
                row.metricResult().getStatus(),
                row.metricResult().getPassed(),
                row.metricResult().getScore(),
                row.metricResult().getConfidence(),
                row.metricResult().getExplanation(),
                row.metricResult().getErrorMessage(),
                row.metricResult().getCreatedAt());
    }
}
