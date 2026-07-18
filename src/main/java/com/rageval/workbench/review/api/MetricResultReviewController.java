package com.rageval.workbench.review.api;

import com.rageval.workbench.review.api.dto.HumanReviewResponse;
import com.rageval.workbench.review.api.dto.MetricResultReviewContextResponse;
import com.rageval.workbench.review.api.dto.SubmitReviewRequest;
import com.rageval.workbench.review.application.HumanReviewService;
import com.rageval.workbench.review.application.ReviewQueryService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-metric-result review operations (spec §20): read the full reviewable context, list recorded
 * decisions, and submit a decision. Submitting never deletes or mutates the automated result.
 */
@RestController
@RequestMapping("/api/v1/metric-results/{metricResultId}")
public class MetricResultReviewController {

    private final ReviewQueryService queryService;
    private final HumanReviewService reviewService;

    public MetricResultReviewController(ReviewQueryService queryService, HumanReviewService reviewService) {
        this.queryService = queryService;
        this.reviewService = reviewService;
    }

    @GetMapping("/review-context")
    public MetricResultReviewContextResponse reviewContext(@PathVariable UUID metricResultId) {
        return queryService.reviewContext(metricResultId);
    }

    @GetMapping("/reviews")
    public List<HumanReviewResponse> reviews(@PathVariable UUID metricResultId) {
        return queryService.reviewsOf(metricResultId);
    }

    @PostMapping("/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    public HumanReviewResponse submit(
            @PathVariable UUID metricResultId, @Valid @RequestBody SubmitReviewRequest request) {
        return HumanReviewResponse.from(reviewService.submit(
                metricResultId,
                request.decision(),
                request.reviewerName(),
                request.comment(),
                request.overridePassed(),
                request.overrideScore()));
    }
}
