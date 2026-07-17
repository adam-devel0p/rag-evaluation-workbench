package com.rageval.workbench.dataset.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.dataset.api.dto.DatasetJsonImportRequest;
import com.rageval.workbench.dataset.api.dto.TestCaseRequest;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatasetImportServiceTest {

    @Mock
    private EvaluationDatasetRepository datasetRepository;

    @Mock
    private EvaluationTestCaseRepository testCaseRepository;

    private TestCaseRequest validTestCase(String externalId) {
        return new TestCaseRequest(
                externalId, "Which Java version is required?", "Java 21", true, false, Severity.MEDIUM,
                List.of(new ExpectedFact("f1", "Java 21", FactMatchType.CASE_INSENSITIVE_TEXT, true, "current version")),
                List.of(), List.of("doc-1"), List.of("versioning"), Map.of("role", "developer"), null);
    }

    @Test
    void importJsonPersistsDatasetAndAllTestCasesWhenValid() {
        DatasetImportService service = new DatasetImportService(datasetRepository, testCaseRepository, new ExpectedFactValidator());
        when(datasetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        DatasetJsonImportRequest request = new DatasetJsonImportRequest(
                "Dev Docs", "desc", "1.0.0", List.of(validTestCase("DEV-001"), validTestCase("DEV-002")));

        EvaluationDataset result = service.importJson(request);

        assertThat(result.getName()).isEqualTo("Dev Docs");
        verify(datasetRepository).save(any(EvaluationDataset.class));
        verify(testCaseRepository, org.mockito.Mockito.times(2)).save(any(EvaluationTestCase.class));
    }

    @Test
    void importJsonRejectsInvalidRegexWithoutPersistingAnything() {
        DatasetImportService service = new DatasetImportService(datasetRepository, testCaseRepository, new ExpectedFactValidator());
        TestCaseRequest badRegex = new TestCaseRequest(
                "DEV-001", "question", null, true, false, Severity.LOW,
                List.of(new ExpectedFact("f1", "Java[", FactMatchType.REGEX, true, "broken")),
                List.of(), List.of(), List.of(), Map.of(), null);
        DatasetJsonImportRequest request = new DatasetJsonImportRequest("Dev Docs", null, "1.0.0", List.of(badRegex));

        assertThatThrownBy(() -> service.importJson(request)).isInstanceOf(ValidationException.class);

        verify(datasetRepository, never()).save(any());
        verify(testCaseRepository, never()).save(any());
    }

    @Test
    void importJsonRejectsDuplicateExternalIdWithinPayload() {
        DatasetImportService service = new DatasetImportService(datasetRepository, testCaseRepository, new ExpectedFactValidator());
        DatasetJsonImportRequest request = new DatasetJsonImportRequest(
                "Dev Docs", null, "1.0.0", List.of(validTestCase("DEV-001"), validTestCase("DEV-001")));

        assertThatThrownBy(() -> service.importJson(request))
                .isInstanceOfSatisfying(ValidationException.class, ex -> assertThat(ex.getViolations())
                        .anyMatch(v -> v.message().contains("duplicate externalId")));
        verify(datasetRepository, never()).save(any());
    }

    @Test
    void importCsvParsesBasicFieldsCorrectly() {
        DatasetImportService service = new DatasetImportService(datasetRepository, testCaseRepository, new ExpectedFactValidator());
        when(datasetRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        String csv = "externalId,question,referenceAnswer,answerable,shouldRefuse,severity,tags,expectedDocumentIds,notes\n"
                + "DEV-001,What version?,Java 21,true,false,MEDIUM,versioning;onboarding,doc-1;doc-2,some note\n";

        service.importCsv("Dev Docs", "desc", "1.0.0", toStream(csv));

        org.mockito.ArgumentCaptor<EvaluationTestCase> captor = org.mockito.ArgumentCaptor.forClass(EvaluationTestCase.class);
        verify(testCaseRepository).save(captor.capture());
        EvaluationTestCase saved = captor.getValue();
        assertThat(saved.getExternalId()).isEqualTo("DEV-001");
        assertThat(saved.getTags()).containsExactly("versioning", "onboarding");
        assertThat(saved.getExpectedDocumentIds()).containsExactly("doc-1", "doc-2");
        assertThat(saved.isAnswerable()).isTrue();
        assertThat(saved.isShouldRefuse()).isFalse();
        assertThat(saved.getSeverity()).isEqualTo(Severity.MEDIUM);
    }

    @Test
    void importCsvRejectsWrongHeader() {
        DatasetImportService service = new DatasetImportService(datasetRepository, testCaseRepository, new ExpectedFactValidator());
        String csv = "wrongHeader\nvalue\n";

        assertThatThrownBy(() -> service.importCsv("Dev Docs", null, "1.0.0", toStream(csv)))
                .isInstanceOf(ValidationException.class);
        verify(datasetRepository, never()).save(any());
    }

    @Test
    void importCsvRejectsInvalidBooleanValue() {
        DatasetImportService service = new DatasetImportService(datasetRepository, testCaseRepository, new ExpectedFactValidator());
        String csv = "externalId,question,referenceAnswer,answerable,shouldRefuse,severity,tags,expectedDocumentIds,notes\n"
                + "DEV-001,What version?,Java 21,maybe,false,MEDIUM,,,\n";

        assertThatThrownBy(() -> service.importCsv("Dev Docs", null, "1.0.0", toStream(csv)))
                .isInstanceOfSatisfying(ValidationException.class, ex -> assertThat(ex.getViolations())
                        .anyMatch(v -> v.field().contains("answerable")));
    }

    private InputStream toStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
