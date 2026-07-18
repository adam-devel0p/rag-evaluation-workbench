package com.rageval.workbench.review.api;

import com.rageval.workbench.common.api.PageResponse;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.review.api.dto.PendingReviewResponse;
import com.rageval.workbench.review.application.ReviewQueryService;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The pending human-review queue (spec §20/§21): every metric result flagged for review that has
 * no recorded decision yet, most severe first, filterable by run, severity, evaluator, and metric
 * status ({@code COMPLETED} = the chatbot's verdict needs review, {@code FAILED} = the evaluator
 * itself failed and a human must fill the gap).
 */
@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewQueryService queryService;

    public ReviewController(ReviewQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/pending")
    public PageResponse<PendingReviewResponse> pending(
            @RequestParam(required = false) UUID runId,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String evaluatorKey,
            @RequestParam(required = false) MetricResultStatus status,
            Pageable pageable) {
        return PageResponse.from(queryService.pending(runId, severity, evaluatorKey, status, pageable));
    }
}
