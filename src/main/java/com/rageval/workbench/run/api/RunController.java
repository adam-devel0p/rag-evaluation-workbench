package com.rageval.workbench.run.api;

import com.rageval.workbench.common.api.PageResponse;
import com.rageval.workbench.run.api.dto.CreateRunRequest;
import com.rageval.workbench.run.api.dto.RunResponse;
import com.rageval.workbench.run.api.dto.TestExecutionResponse;
import com.rageval.workbench.run.application.RunService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * No business logic here — every decision is delegated to {@link RunService}. Creation, start,
 * and resume return HTTP 202: the work is accepted and processed asynchronously by the bounded
 * worker pool; the response body carries the current persisted state to poll via {@code GET}.
 */
@RestController
@RequestMapping("/api/v1/evaluation-runs")
public class RunController {

    private final RunService runService;

    public RunController(RunService runService) {
        this.runService = runService;
    }

    @PostMapping
    public ResponseEntity<RunResponse> create(@Valid @RequestBody CreateRunRequest request) {
        RunResponse response = RunResponse.from(runService.create(request));
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/evaluation-runs/" + response.id()))
                .body(response);
    }

    @GetMapping
    public PageResponse<RunResponse> list(Pageable pageable) {
        Page<RunResponse> page = runService.list(pageable).map(RunResponse::from);
        return PageResponse.from(page);
    }

    @GetMapping("/{id}")
    public RunResponse get(@PathVariable UUID id) {
        return RunResponse.from(runService.getOrThrow(id));
    }

    @PostMapping("/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse start(@PathVariable UUID id) {
        return RunResponse.from(runService.start(id));
    }

    @PostMapping("/{id}/cancel")
    public RunResponse cancel(@PathVariable UUID id) {
        return RunResponse.from(runService.cancel(id));
    }

    @PostMapping("/{id}/resume")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public RunResponse resume(@PathVariable UUID id) {
        return RunResponse.from(runService.resume(id));
    }

    @GetMapping("/{id}/executions")
    public PageResponse<TestExecutionResponse> listExecutions(@PathVariable UUID id, Pageable pageable) {
        Page<TestExecutionResponse> page =
                runService.listExecutions(id, pageable).map(TestExecutionResponse::from);
        return PageResponse.from(page);
    }
}
