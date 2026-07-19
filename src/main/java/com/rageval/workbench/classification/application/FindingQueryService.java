package com.rageval.workbench.classification.application;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read side of a run's findings, keeping repository access out of the controller (spec §28.5). */
@Service
public class FindingQueryService {

    private final EvaluationRunRepository runRepository;
    private final EvaluationFindingRepository findingRepository;

    public FindingQueryService(
            EvaluationRunRepository runRepository, EvaluationFindingRepository findingRepository) {
        this.runRepository = runRepository;
        this.findingRepository = findingRepository;
    }

    @Transactional(readOnly = true)
    public Page<EvaluationFinding> findByRun(UUID runId, Pageable pageable) {
        if (!runRepository.existsById(runId)) {
            throw new NotFoundException("Evaluation run not found: " + runId);
        }
        return findingRepository.findByEvaluationRunId(runId, pageable);
    }
}
