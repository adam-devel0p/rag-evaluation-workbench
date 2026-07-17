package com.rageval.workbench.target.api;

import com.rageval.workbench.common.api.PageResponse;
import com.rageval.workbench.target.api.dto.TargetSystemRequest;
import com.rageval.workbench.target.api.dto.TargetSystemResponse;
import com.rageval.workbench.target.api.dto.TestConnectionRequest;
import com.rageval.workbench.target.api.dto.TestConnectionResponse;
import com.rageval.workbench.target.application.TargetConnectionTestService;
import com.rageval.workbench.target.application.TargetSystemService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/** No business logic here — every decision is delegated to {@link TargetSystemService}/{@link TargetConnectionTestService}. */
@RestController
@RequestMapping("/api/v1/targets")
public class TargetSystemController {

    private final TargetSystemService targetSystemService;
    private final TargetConnectionTestService connectionTestService;

    public TargetSystemController(
            TargetSystemService targetSystemService, TargetConnectionTestService connectionTestService) {
        this.targetSystemService = targetSystemService;
        this.connectionTestService = connectionTestService;
    }

    @PostMapping
    public ResponseEntity<TargetSystemResponse> create(@Valid @RequestBody TargetSystemRequest request) {
        TargetSystemResponse response = TargetSystemResponse.from(targetSystemService.create(request));
        return ResponseEntity.created(URI.create("/api/v1/targets/" + response.id())).body(response);
    }

    @GetMapping
    public PageResponse<TargetSystemResponse> list(Pageable pageable) {
        Page<TargetSystemResponse> page = targetSystemService.list(pageable).map(TargetSystemResponse::from);
        return PageResponse.from(page);
    }

    @GetMapping("/{id}")
    public TargetSystemResponse get(@PathVariable UUID id) {
        return TargetSystemResponse.from(targetSystemService.getOrThrow(id));
    }

    @PutMapping("/{id}")
    public TargetSystemResponse update(@PathVariable UUID id, @Valid @RequestBody TargetSystemRequest request) {
        return TargetSystemResponse.from(targetSystemService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        targetSystemService.delete(id);
    }

    @PostMapping("/{id}/test-connection")
    public TestConnectionResponse testConnection(
            @PathVariable UUID id, @RequestBody(required = false) TestConnectionRequest request) {
        return connectionTestService.testConnection(id, request);
    }
}
