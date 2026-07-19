package com.rageval.workbench.report.api;

import com.rageval.workbench.comparison.application.RunComparisonService;
import com.rageval.workbench.report.application.RunReportService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Serves the server-rendered HTML reports (spec §21/§22): the single-run reliability report and
 * the spec §19 baseline-vs-candidate comparison report. Plain Thymeleaf views — no JavaScript
 * required, no business logic here; the models are assembled by {@link RunReportService} and
 * {@link RunComparisonService}.
 */
@Controller
public class ReportController {

    private final RunReportService reportService;
    private final RunComparisonService comparisonService;

    public ReportController(RunReportService reportService, RunComparisonService comparisonService) {
        this.reportService = reportService;
        this.comparisonService = comparisonService;
    }

    @GetMapping(value = "/reports/runs/{runId}", produces = MediaType.TEXT_HTML_VALUE)
    public String runReport(@PathVariable UUID runId, Model model) {
        model.addAttribute("report", reportService.build(runId));
        return "run-report";
    }

    @GetMapping(value = "/reports/comparisons", produces = MediaType.TEXT_HTML_VALUE)
    public String comparisonReport(
            @RequestParam UUID baselineRunId,
            @RequestParam UUID candidateRunId,
            @RequestParam(defaultValue = "false") boolean allowDatasetMismatch,
            Model model) {
        model.addAttribute(
                "comparison", comparisonService.compare(baselineRunId, candidateRunId, allowDatasetMismatch));
        return "comparison-report";
    }
}
