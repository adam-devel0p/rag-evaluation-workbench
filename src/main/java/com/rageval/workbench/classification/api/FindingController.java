package com.rageval.workbench.classification.api;

import com.rageval.workbench.classification.api.dto.FindingResponse;
import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.common.api.PageResponse;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only access to a run's classification findings (spec §21). */
@RestController
@RequestMapping("/api/v1/evaluation-runs/{runId}")
public class FindingController {

    private final EvaluationRunRepository runRepository;
    private final EvaluationFindingRepository findingRepository;

    public FindingController(
            EvaluationRunRepository runRepository, EvaluationFindingRepository findingRepository) {
        this.runRepository = runRepository;
        this.findingRepository = findingRepository;
    }

    @GetMapping("/findings")
    public PageResponse<FindingResponse> findings(@PathVariable UUID runId, Pageable pageable) {
        if (!runRepository.existsById(runId)) {
            throw new NotFoundException("Evaluation run not found: " + runId);
        }
        Page<FindingResponse> page =
                findingRepository.findByEvaluationRunId(runId, pageable).map(FindingResponse::from);
        return PageResponse.from(page);
    }
}
