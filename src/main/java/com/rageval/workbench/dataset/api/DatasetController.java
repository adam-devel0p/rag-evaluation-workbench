package com.rageval.workbench.dataset.api;

import com.rageval.workbench.common.api.PageResponse;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.dataset.api.dto.CreateDatasetRequest;
import com.rageval.workbench.dataset.api.dto.DatasetJsonImportRequest;
import com.rageval.workbench.dataset.api.dto.DatasetResponse;
import com.rageval.workbench.dataset.api.dto.TestCaseRequest;
import com.rageval.workbench.dataset.api.dto.TestCaseResponse;
import com.rageval.workbench.dataset.application.DatasetImportService;
import com.rageval.workbench.dataset.application.DatasetService;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** No business logic here — every decision is delegated to {@link DatasetService}/{@link DatasetImportService}. */
@RestController
@RequestMapping("/api/v1/datasets")
public class DatasetController {

    private final DatasetService datasetService;
    private final DatasetImportService datasetImportService;

    public DatasetController(DatasetService datasetService, DatasetImportService datasetImportService) {
        this.datasetService = datasetService;
        this.datasetImportService = datasetImportService;
    }

    @PostMapping
    public ResponseEntity<DatasetResponse> create(@Valid @RequestBody CreateDatasetRequest request) {
        DatasetResponse response = DatasetResponse.from(datasetService.create(request));
        return ResponseEntity.created(URI.create("/api/v1/datasets/" + response.id())).body(response);
    }

    @GetMapping
    public PageResponse<DatasetResponse> list(Pageable pageable) {
        Page<DatasetResponse> page = datasetService.list(pageable).map(DatasetResponse::from);
        return PageResponse.from(page);
    }

    @GetMapping("/{id}")
    public DatasetResponse get(@PathVariable UUID id) {
        return DatasetResponse.from(datasetService.getOrThrow(id));
    }

    @PostMapping("/{id}/mark-ready")
    public DatasetResponse markReady(@PathVariable UUID id) {
        return DatasetResponse.from(datasetService.markReady(id));
    }

    @GetMapping("/{id}/test-cases")
    public List<TestCaseResponse> listTestCases(@PathVariable UUID id) {
        return datasetService.listTestCases(id).stream().map(TestCaseResponse::from).toList();
    }

    @PostMapping("/{id}/test-cases")
    public ResponseEntity<TestCaseResponse> addTestCase(@PathVariable UUID id, @Valid @RequestBody TestCaseRequest request) {
        TestCaseResponse response = TestCaseResponse.from(datasetService.addTestCase(id, request));
        return ResponseEntity.created(URI.create("/api/v1/datasets/" + id + "/test-cases/" + response.id()))
                .body(response);
    }

    @PutMapping("/{id}/test-cases/{testCaseId}")
    public TestCaseResponse updateTestCase(
            @PathVariable UUID id, @PathVariable UUID testCaseId, @Valid @RequestBody TestCaseRequest request) {
        return TestCaseResponse.from(datasetService.updateTestCase(id, testCaseId, request));
    }

    @PostMapping("/import/json")
    public ResponseEntity<DatasetResponse> importJson(@Valid @RequestBody DatasetJsonImportRequest request) {
        DatasetResponse response = DatasetResponse.from(datasetImportService.importJson(request));
        return ResponseEntity.created(URI.create("/api/v1/datasets/" + response.id())).body(response);
    }

    @PostMapping("/import/csv")
    public ResponseEntity<DatasetResponse> importCsv(
            @RequestParam String name,
            @RequestParam(required = false) String description,
            @RequestParam String datasetVersion,
            @RequestParam("file") MultipartFile file) {
        try {
            DatasetResponse response = DatasetResponse.from(
                    datasetImportService.importCsv(name, description, datasetVersion, file.getInputStream()));
            return ResponseEntity.created(URI.create("/api/v1/datasets/" + response.id())).body(response);
        } catch (IOException ex) {
            throw new ValidationException("Could not read uploaded CSV file", new FieldViolation("file", ex.getMessage()));
        }
    }
}
