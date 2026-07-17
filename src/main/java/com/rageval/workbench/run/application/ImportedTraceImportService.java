package com.rageval.workbench.run.application;

import com.rageval.workbench.common.csv.CsvParser;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.dataset.domain.DatasetStatus;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.run.api.dto.ImportedTraceImportRequest;
import com.rageval.workbench.run.api.dto.ImportedTraceRow;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ImportedTrace;
import com.rageval.workbench.run.domain.RunMode;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.ImportedTraceRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports previously captured chatbot interactions (spec §8.2) as a REPLAY-mode run: one pending
 * {@link TestExecution} plus one {@link ImportedTrace} per row, bound 1:1, all-or-nothing. Every
 * row is validated before anything is persisted, and violations are reported together with the
 * row index — the same behavior as dataset import. CSV carries only the spec's basic fields;
 * JSON is the canonical rich format (citations, chunks, metadata).
 */
@Service
public class ImportedTraceImportService {

    private static final Logger log = LoggerFactory.getLogger(ImportedTraceImportService.class);

    private static final List<String> CSV_COLUMNS = List.of(
            "externalTestCaseId", "repetitionNumber", "question", "answer", "targetModel", "latencyMilliseconds");

    private final TargetSystemRepository targetSystemRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final EvaluationRunRepository runRepository;
    private final TestExecutionRepository executionRepository;
    private final ImportedTraceRepository importedTraceRepository;

    public ImportedTraceImportService(
            TargetSystemRepository targetSystemRepository,
            EvaluationDatasetRepository datasetRepository,
            EvaluationTestCaseRepository testCaseRepository,
            EvaluationRunRepository runRepository,
            TestExecutionRepository executionRepository,
            ImportedTraceRepository importedTraceRepository) {
        this.targetSystemRepository = targetSystemRepository;
        this.datasetRepository = datasetRepository;
        this.testCaseRepository = testCaseRepository;
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.importedTraceRepository = importedTraceRepository;
    }

    public record ImportResult(EvaluationRun run, int importedTraceCount) {
    }

    @Transactional
    public ImportResult importJson(ImportedTraceImportRequest request) {
        return doImport(request.targetSystemId(), request.datasetId(), request.runName(), request.traces());
    }

    @Transactional
    public ImportResult importCsv(UUID targetSystemId, UUID datasetId, String runName, InputStream csvContent) {
        return doImport(targetSystemId, datasetId, runName, parseCsvTraces(csvContent));
    }

    private ImportResult doImport(UUID targetSystemId, UUID datasetId, String runName, List<ImportedTraceRow> traces) {
        TargetSystem target = targetSystemRepository
                .findById(targetSystemId)
                .orElseThrow(() -> new NotFoundException("Target system not found: " + targetSystemId));
        if (target.getAdapterType() != AdapterType.IMPORTED_TRACE) {
            throw new ValidationException(
                    "Imported traces require a target with adapterType IMPORTED_TRACE",
                    new FieldViolation("targetSystemId", "adapterType must be IMPORTED_TRACE, was " + target.getAdapterType()));
        }
        EvaluationDataset dataset = datasetRepository
                .findById(datasetId)
                .orElseThrow(() -> new NotFoundException("Dataset not found: " + datasetId));
        if (dataset.getStatus() != DatasetStatus.READY) {
            throw new ValidationException(
                    "Dataset " + datasetId + " is " + dataset.getStatus() + "; imports require a READY dataset",
                    new FieldViolation("datasetId", "dataset must be READY"));
        }

        Map<String, EvaluationTestCase> testCasesByExternalId = new HashMap<>();
        testCaseRepository.findByDatasetId(datasetId)
                .forEach(testCase -> testCasesByExternalId.put(testCase.getExternalId(), testCase));

        List<FieldViolation> violations = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        int maxRepetition = 1;
        for (int i = 0; i < traces.size(); i++) {
            ImportedTraceRow row = traces.get(i);
            String prefix = "traces[" + i + "].";
            if (row.externalTestCaseId() == null || row.externalTestCaseId().isBlank()) {
                violations.add(new FieldViolation(prefix + "externalTestCaseId", "must not be blank"));
            } else if (!testCasesByExternalId.containsKey(row.externalTestCaseId())) {
                violations.add(new FieldViolation(
                        prefix + "externalTestCaseId",
                        "does not map to any test case in dataset " + datasetId + ": " + row.externalTestCaseId()));
            }
            if (row.repetitionNumber() == null || row.repetitionNumber() < 1) {
                violations.add(new FieldViolation(prefix + "repetitionNumber", "must be a positive integer"));
            } else {
                maxRepetition = Math.max(maxRepetition, row.repetitionNumber());
            }
            if (row.question() == null || row.question().isBlank()) {
                violations.add(new FieldViolation(prefix + "question", "must not be blank"));
            }
            if (row.externalTestCaseId() != null && row.repetitionNumber() != null
                    && !seenKeys.add(row.externalTestCaseId() + "#" + row.repetitionNumber())) {
                violations.add(new FieldViolation(
                        prefix + "repetitionNumber",
                        "duplicate trace for test case " + row.externalTestCaseId() + " repetition " + row.repetitionNumber()));
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("Imported-trace import failed validation", violations);
        }

        EvaluationRun run = new EvaluationRun(
                targetSystemId, datasetId, runName, RunMode.REPLAY, maxRepetition, List.of(), null);
        runRepository.save(run);
        for (ImportedTraceRow row : traces) {
            EvaluationTestCase testCase = testCasesByExternalId.get(row.externalTestCaseId());
            TestExecution execution =
                    new TestExecution(run.getId(), testCase.getId(), null, row.repetitionNumber());
            executionRepository.save(execution);
            importedTraceRepository.save(new ImportedTrace(
                    execution.getId(),
                    row.externalTestCaseId(),
                    row.repetitionNumber(),
                    row.question(),
                    row.answer(),
                    row.citations(),
                    row.retrievedChunks(),
                    row.targetModel(),
                    row.latencyMilliseconds(),
                    row.metadata()));
        }
        log.info("Imported {} traces into run {} (dataset {}, target {})", traces.size(), run.getId(), datasetId, targetSystemId);
        return new ImportResult(run, traces.size());
    }

    /** CSV carries the spec's basic fields only; citations/chunks/metadata require the JSON format. */
    private List<ImportedTraceRow> parseCsvTraces(InputStream csvContent) {
        List<List<String>> rows = CsvParser.parse(csvContent);
        if (rows.isEmpty()) {
            throw new ValidationException(
                    "CSV file is empty",
                    new FieldViolation("file", "must contain a header row and at least one data row"));
        }
        List<String> header = rows.get(0);
        if (!header.equals(CSV_COLUMNS)) {
            throw new ValidationException(
                    "Imported-trace CSV import failed validation",
                    new FieldViolation(
                            "file",
                            "header must be exactly: " + String.join(",", CSV_COLUMNS) + " but was: " + String.join(",", header)));
        }
        List<FieldViolation> violations = new ArrayList<>();
        List<ImportedTraceRow> traces = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.size() == 1 && row.get(0).isBlank()) {
                continue;
            }
            if (row.size() != header.size()) {
                violations.add(new FieldViolation(
                        "row[" + rowIndex + "]", "expected " + header.size() + " columns but found " + row.size()));
                continue;
            }
            String rowPrefix = "row[" + rowIndex + "].";
            Integer repetitionNumber = parseInteger(rowPrefix, "repetitionNumber", row.get(1), violations);
            Long latencyMilliseconds = parseLong(rowPrefix, "latencyMilliseconds", row.get(5), violations);
            traces.add(new ImportedTraceRow(
                    row.get(0),
                    repetitionNumber,
                    row.get(2),
                    blankToNull(row.get(3)),
                    List.of(),
                    List.of(),
                    blankToNull(row.get(4)),
                    latencyMilliseconds,
                    Map.of()));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException("Imported-trace CSV import failed validation", violations);
        }
        return traces;
    }

    private Integer parseInteger(String rowPrefix, String field, String value, List<FieldViolation> violations) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            violations.add(new FieldViolation(rowPrefix + field, "must be an integer, got: " + value));
            return null;
        }
    }

    private Long parseLong(String rowPrefix, String field, String value, List<FieldViolation> violations) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException ex) {
            violations.add(new FieldViolation(rowPrefix + field, "must be an integer, got: " + value));
            return null;
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
