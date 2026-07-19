package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of a run's metric results, keeping repository access out of the controller (spec §28.5). */
@Service
public class MetricResultQueryService {

    private final EvaluationRunRepository runRepository;
    private final EvaluationMetricResultRepository metricResultRepository;

    public MetricResultQueryService(
            EvaluationRunRepository runRepository, EvaluationMetricResultRepository metricResultRepository) {
        this.runRepository = runRepository;
        this.metricResultRepository = metricResultRepository;
    }

    @Transactional(readOnly = true)
    public Page<EvaluationMetricResult> findByRun(UUID runId, Pageable pageable) {
        if (!runRepository.existsById(runId)) {
            throw new NotFoundException("Evaluation run not found: " + runId);
        }
        return metricResultRepository.findByRunId(runId, pageable);
    }
}
