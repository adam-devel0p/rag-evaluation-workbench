package com.rageval.workbench.classification.api;

import com.rageval.workbench.classification.api.dto.FindingResponse;
import com.rageval.workbench.classification.application.FindingQueryService;
import com.rageval.workbench.common.api.PageResponse;
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

    private final FindingQueryService findingQueryService;

    public FindingController(FindingQueryService findingQueryService) {
        this.findingQueryService = findingQueryService;
    }

    @GetMapping("/findings")
    public PageResponse<FindingResponse> findings(@PathVariable UUID runId, Pageable pageable) {
        Page<FindingResponse> page =
                findingQueryService.findByRun(runId, pageable).map(FindingResponse::from);
        return PageResponse.from(page);
    }
}
