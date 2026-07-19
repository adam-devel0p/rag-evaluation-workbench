package com.rageval.workbench.comparison.api;

import com.rageval.workbench.comparison.application.RunComparison;
import com.rageval.workbench.comparison.application.RunComparisonService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only spec §19 comparison endpoint. No business logic here — matching, classification, and
 * every warning rule live in {@link RunComparisonService}.
 */
@RestController
public class RunComparisonController {

    private final RunComparisonService comparisonService;

    public RunComparisonController(RunComparisonService comparisonService) {
        this.comparisonService = comparisonService;
    }

    @GetMapping("/api/v1/run-comparisons")
    public RunComparison compare(
            @RequestParam UUID baselineRunId,
            @RequestParam UUID candidateRunId,
            @RequestParam(defaultValue = "false") boolean allowDatasetMismatch) {
        return comparisonService.compare(baselineRunId, candidateRunId, allowDatasetMismatch);
    }
}
