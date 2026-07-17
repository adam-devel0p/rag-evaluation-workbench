package com.rageval.workbench.evaluation.api;

import com.rageval.workbench.common.api.PageResponse;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.evaluation.api.dto.MetricResultResponse;
import com.rageval.workbench.evaluation.application.RunSummary;
import com.rageval.workbench.evaluation.application.RunSummaryService;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only evaluation results of a run: the per-evaluator metric results and the aggregated
 * spec §18 summary. Lives in the evaluation module (not {@code run.api}) so the package dependency
 * direction stays evaluation → run.
 */
@RestController
@RequestMapping("/api/v1/evaluation-runs/{runId}")
public class RunEvaluationController {

    private final EvaluationRunRepository runRepository;
    private final EvaluationMetricResultRepository metricResultRepository;
    private final RunSummaryService summaryService;

    public RunEvaluationController(
            EvaluationRunRepository runRepository,
            EvaluationMetricResultRepository metricResultRepository,
            RunSummaryService summaryService) {
        this.runRepository = runRepository;
        this.metricResultRepository = metricResultRepository;
        this.summaryService = summaryService;
    }

    @GetMapping("/metrics")
    public PageResponse<MetricResultResponse> metrics(@PathVariable UUID runId, Pageable pageable) {
        requireRun(runId);
        Page<MetricResultResponse> page =
                metricResultRepository.findByRunId(runId, pageable).map(MetricResultResponse::from);
        return PageResponse.from(page);
    }

    @GetMapping("/summary")
    public RunSummary summary(@PathVariable UUID runId) {
        return summaryService.summarize(runId);
    }

    private void requireRun(UUID runId) {
        if (!runRepository.existsById(runId)) {
            throw new NotFoundException("Evaluation run not found: " + runId);
        }
    }
}
