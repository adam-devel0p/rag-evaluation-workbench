package com.rageval.workbench.run.api;

import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.run.api.dto.ImportedTraceImportRequest;
import com.rageval.workbench.run.api.dto.ImportedTraceImportResponse;
import com.rageval.workbench.run.api.dto.RunResponse;
import com.rageval.workbench.run.application.ImportedTraceImportService;
import com.rageval.workbench.run.application.ImportedTraceImportService.ImportResult;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** No business logic here — every decision is delegated to {@link ImportedTraceImportService}. */
@RestController
@RequestMapping("/api/v1/imported-traces")
public class ImportedTraceController {

    private final ImportedTraceImportService importService;

    public ImportedTraceController(ImportedTraceImportService importService) {
        this.importService = importService;
    }

    @PostMapping
    public ResponseEntity<ImportedTraceImportResponse> importJson(
            @Valid @RequestBody ImportedTraceImportRequest request) {
        return created(importService.importJson(request));
    }

    @PostMapping("/csv")
    public ResponseEntity<ImportedTraceImportResponse> importCsv(
            @RequestParam UUID targetSystemId,
            @RequestParam UUID datasetId,
            @RequestParam String runName,
            @RequestParam("file") MultipartFile file) {
        try {
            return created(importService.importCsv(targetSystemId, datasetId, runName, file.getInputStream()));
        } catch (IOException ex) {
            throw new ValidationException(
                    "Could not read uploaded CSV file", new FieldViolation("file", ex.getMessage()));
        }
    }

    private ResponseEntity<ImportedTraceImportResponse> created(ImportResult result) {
        RunResponse run = RunResponse.from(result.run());
        return ResponseEntity.created(URI.create("/api/v1/evaluation-runs/" + run.id()))
                .body(new ImportedTraceImportResponse(run, result.importedTraceCount()));
    }
}
