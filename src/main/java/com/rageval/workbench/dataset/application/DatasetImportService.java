package com.rageval.workbench.dataset.application;

import com.rageval.workbench.common.csv.CsvParser;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.dataset.api.dto.DatasetJsonImportRequest;
import com.rageval.workbench.dataset.api.dto.TestCaseRequest;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports a whole dataset (metadata + test cases) in one transactional, all-or-nothing operation.
 * Every row/test case is validated up front and reported together — nothing is persisted, not
 * even the dataset row itself, if any validation fails.
 *
 * <p>CSV import only carries the "basic fields" subset (spec §8.2 applies the same basic/rich
 * split to imported traces): {@code requiredFacts}, {@code forbiddenClaims}, and {@code
 * userContext} are structured data that cannot be reasonably flattened into CSV columns, so a CSV
 * import always produces test cases with those fields empty. Use JSON import for the full shape.
 */
@Service
public class DatasetImportService {

    private static final List<String> CSV_COLUMNS = List.of(
            "externalId", "question", "referenceAnswer", "answerable", "shouldRefuse", "severity", "tags",
            "expectedDocumentIds", "notes");

    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final ExpectedFactValidator expectedFactValidator;

    public DatasetImportService(
            EvaluationDatasetRepository datasetRepository,
            EvaluationTestCaseRepository testCaseRepository,
            ExpectedFactValidator expectedFactValidator) {
        this.datasetRepository = datasetRepository;
        this.testCaseRepository = testCaseRepository;
        this.expectedFactValidator = expectedFactValidator;
    }

    @Transactional
    public EvaluationDataset importJson(DatasetJsonImportRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        validateTestCases(request.testCases(), violations);
        if (!violations.isEmpty()) {
            throw new ValidationException("Dataset import failed validation", violations);
        }
        return persist(request.name(), request.description(), request.datasetVersion(), request.testCases());
    }

    @Transactional
    public EvaluationDataset importCsv(String name, String description, String datasetVersion, InputStream csvContent) {
        List<List<String>> rows = CsvParser.parse(csvContent);
        if (rows.isEmpty()) {
            throw new ValidationException(
                    "CSV file is empty", new FieldViolation("file", "must contain a header row and at least one data row"));
        }
        List<String> header = rows.get(0);
        List<FieldViolation> violations = new ArrayList<>();
        if (!header.equals(CSV_COLUMNS)) {
            violations.add(new FieldViolation(
                    "file", "header must be exactly: " + String.join(",", CSV_COLUMNS) + " but was: " + String.join(",", header)));
            throw new ValidationException("Dataset CSV import failed validation", violations);
        }

        List<TestCaseRequest> testCases = new ArrayList<>();
        for (int rowIndex = 1; rowIndex < rows.size(); rowIndex++) {
            List<String> row = rows.get(rowIndex);
            if (row.size() == 1 && row.get(0).isBlank()) {
                continue;
            }
            testCases.add(parseCsvRow(rowIndex, row, header, violations));
        }
        validateTestCases(testCases, violations);
        if (!violations.isEmpty()) {
            throw new ValidationException("Dataset CSV import failed validation", violations);
        }
        return persist(name, description, datasetVersion, testCases);
    }

    private EvaluationDataset persist(
            String name, String description, String datasetVersion, List<TestCaseRequest> testCases) {
        EvaluationDataset dataset = new EvaluationDataset(name, description, datasetVersion);
        datasetRepository.save(dataset);
        for (TestCaseRequest testCase : testCases) {
            testCaseRepository.save(new EvaluationTestCase(
                    dataset.getId(),
                    testCase.externalId(),
                    testCase.question(),
                    testCase.referenceAnswer(),
                    testCase.answerable(),
                    testCase.shouldRefuse(),
                    testCase.severity(),
                    testCase.requiredFacts(),
                    testCase.forbiddenClaims(),
                    testCase.expectedDocumentIds(),
                    testCase.tags(),
                    testCase.userContext(),
                    testCase.notes()));
        }
        return dataset;
    }

    private void validateTestCases(List<TestCaseRequest> testCases, List<FieldViolation> violations) {
        Set<String> seenExternalIds = new HashSet<>();
        for (int i = 0; i < testCases.size(); i++) {
            TestCaseRequest testCase = testCases.get(i);
            String prefix = "testCases[" + i + "].";
            if (testCase.externalId() == null || testCase.externalId().isBlank()) {
                violations.add(new FieldViolation(prefix + "externalId", "must not be blank"));
            } else if (!seenExternalIds.add(testCase.externalId())) {
                violations.add(new FieldViolation(prefix + "externalId", "duplicate externalId in this import: " + testCase.externalId()));
            }
            if (testCase.question() == null || testCase.question().isBlank()) {
                violations.add(new FieldViolation(prefix + "question", "must not be blank"));
            }
            if (testCase.severity() == null) {
                violations.add(new FieldViolation(prefix + "severity", "must not be null"));
            }
            violations.addAll(expectedFactValidator.validate(prefix + "requiredFacts", testCase.requiredFacts()));
            violations.addAll(expectedFactValidator.validate(prefix + "forbiddenClaims", testCase.forbiddenClaims()));
        }
    }

    private TestCaseRequest parseCsvRow(int rowIndex, List<String> row, List<String> header, List<FieldViolation> violations) {
        if (row.size() != header.size()) {
            violations.add(new FieldViolation(
                    "row[" + rowIndex + "]", "expected " + header.size() + " columns but found " + row.size()));
            return new TestCaseRequest(
                    "row-" + rowIndex, "invalid row", null, false, false, Severity.LOW, List.of(), List.of(), List.of(),
                    List.of(), Map.of(), null);
        }
        Map<String, String> cells = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            cells.put(header.get(i), row.get(i));
        }
        String rowPrefix = "row[" + rowIndex + "].";

        boolean answerable = parseBoolean(rowPrefix, "answerable", cells.get("answerable"), violations);
        boolean shouldRefuse = cells.get("shouldRefuse").isBlank()
                ? false
                : parseBoolean(rowPrefix, "shouldRefuse", cells.get("shouldRefuse"), violations);
        Severity severity = parseSeverity(rowPrefix, cells.get("severity"), violations);
        List<String> tags = splitList(cells.get("tags"));
        List<String> expectedDocumentIds = splitList(cells.get("expectedDocumentIds"));

        return new TestCaseRequest(
                cells.get("externalId"),
                cells.get("question"),
                blankToNull(cells.get("referenceAnswer")),
                answerable,
                shouldRefuse,
                severity,
                List.of(),
                List.of(),
                expectedDocumentIds,
                tags,
                Map.of(),
                blankToNull(cells.get("notes")));
    }

    private boolean parseBoolean(String rowPrefix, String field, String value, List<FieldViolation> violations) {
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)) {
            return false;
        }
        violations.add(new FieldViolation(rowPrefix + field, "must be true or false, got: " + value));
        return false;
    }

    private Severity parseSeverity(String rowPrefix, String value, List<FieldViolation> violations) {
        try {
            return Severity.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ex) {
            violations.add(new FieldViolation(rowPrefix + "severity", "must be one of LOW, MEDIUM, HIGH, CRITICAL, got: " + value));
            return null;
        }
    }

    private List<String> splitList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(";")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
