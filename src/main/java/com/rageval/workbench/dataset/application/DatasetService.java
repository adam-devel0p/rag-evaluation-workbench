package com.rageval.workbench.dataset.application;

import com.rageval.workbench.common.error.ConflictException;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.dataset.api.dto.CreateDatasetRequest;
import com.rageval.workbench.dataset.api.dto.TestCaseRequest;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD and status-transition orchestration for {@link EvaluationDataset} and its test cases. */
@Service
public class DatasetService {

    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final ExpectedFactValidator expectedFactValidator;

    public DatasetService(
            EvaluationDatasetRepository datasetRepository,
            EvaluationTestCaseRepository testCaseRepository,
            ExpectedFactValidator expectedFactValidator) {
        this.datasetRepository = datasetRepository;
        this.testCaseRepository = testCaseRepository;
        this.expectedFactValidator = expectedFactValidator;
    }

    @Transactional
    public EvaluationDataset create(CreateDatasetRequest request) {
        EvaluationDataset dataset = new EvaluationDataset(request.name(), request.description(), request.datasetVersion());
        return datasetRepository.save(dataset);
    }

    @Transactional(readOnly = true)
    public EvaluationDataset getOrThrow(UUID id) {
        return datasetRepository.findById(id).orElseThrow(() -> new NotFoundException("Dataset not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<EvaluationDataset> list(Pageable pageable) {
        return datasetRepository.findAll(pageable);
    }

    @Transactional
    public EvaluationDataset markReady(UUID id) {
        EvaluationDataset dataset = getOrThrow(id);
        dataset.markReady();
        return dataset;
    }

    @Transactional(readOnly = true)
    public List<EvaluationTestCase> listTestCases(UUID datasetId) {
        getOrThrow(datasetId);
        return testCaseRepository.findByDatasetId(datasetId);
    }

    @Transactional
    public EvaluationTestCase addTestCase(UUID datasetId, TestCaseRequest request) {
        EvaluationDataset dataset = getOrThrow(datasetId);
        requireModifiable(dataset);
        validateTestCaseRequest(request);
        if (testCaseRepository.existsByDatasetIdAndExternalId(datasetId, request.externalId())) {
            throw new ValidationException(
                    "externalId already exists in this dataset",
                    new FieldViolation("externalId", "must be unique within the dataset"));
        }
        EvaluationTestCase testCase = new EvaluationTestCase(
                datasetId,
                request.externalId(),
                request.question(),
                request.referenceAnswer(),
                request.answerable(),
                request.shouldRefuse(),
                request.severity(),
                request.requiredFacts(),
                request.forbiddenClaims(),
                request.expectedDocumentIds(),
                request.tags(),
                request.userContext(),
                request.notes());
        return testCaseRepository.save(testCase);
    }

    @Transactional
    public EvaluationTestCase updateTestCase(UUID datasetId, UUID testCaseId, TestCaseRequest request) {
        EvaluationDataset dataset = getOrThrow(datasetId);
        requireModifiable(dataset);
        EvaluationTestCase testCase = testCaseRepository
                .findById(testCaseId)
                .filter(candidate -> candidate.getDatasetId().equals(datasetId))
                .orElseThrow(() -> new NotFoundException(
                        "Test case " + testCaseId + " not found in dataset " + datasetId));
        validateTestCaseRequest(request);
        if (testCaseRepository.existsByDatasetIdAndExternalIdAndIdNot(datasetId, request.externalId(), testCaseId)) {
            throw new ValidationException(
                    "externalId already exists in this dataset",
                    new FieldViolation("externalId", "must be unique within the dataset"));
        }
        testCase.update(
                request.externalId(),
                request.question(),
                request.referenceAnswer(),
                request.answerable(),
                request.shouldRefuse(),
                request.severity(),
                request.requiredFacts(),
                request.forbiddenClaims(),
                request.expectedDocumentIds(),
                request.tags(),
                request.userContext(),
                request.notes());
        return testCase;
    }

    private void requireModifiable(EvaluationDataset dataset) {
        if (!dataset.isModifiable()) {
            throw new ConflictException(
                    "Dataset " + dataset.getId() + " is " + dataset.getStatus() + " and cannot be modified");
        }
    }

    private void validateTestCaseRequest(TestCaseRequest request) {
        List<FieldViolation> violations = new ArrayList<>();
        violations.addAll(expectedFactValidator.validate("requiredFacts", request.requiredFacts()));
        violations.addAll(expectedFactValidator.validate("forbiddenClaims", request.forbiddenClaims()));
        if (!violations.isEmpty()) {
            throw new ValidationException("Test case failed validation", violations);
        }
    }
}
