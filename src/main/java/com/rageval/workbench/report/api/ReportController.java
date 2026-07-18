package com.rageval.workbench.report.api;

import com.rageval.workbench.report.application.RunReportService;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Serves the server-rendered HTML reliability report (spec §21/§22). A plain Thymeleaf view —
 * no JavaScript required, no business logic here; the whole model is assembled by
 * {@link RunReportService}.
 */
@Controller
public class ReportController {

    private final RunReportService reportService;

    public ReportController(RunReportService reportService) {
        this.reportService = reportService;
    }

    @GetMapping(value = "/reports/runs/{runId}", produces = MediaType.TEXT_HTML_VALUE)
    public String runReport(@PathVariable UUID runId, Model model) {
        model.addAttribute("report", reportService.build(runId));
        return "run-report";
    }
}
