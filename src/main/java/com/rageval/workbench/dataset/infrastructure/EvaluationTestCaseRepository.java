package com.rageval.workbench.dataset.infrastructure;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationTestCaseRepository extends JpaRepository<EvaluationTestCase, UUID> {

    List<EvaluationTestCase> findByDatasetId(UUID datasetId);

    boolean existsByDatasetIdAndExternalId(UUID datasetId, String externalId);

    boolean existsByDatasetIdAndExternalIdAndIdNot(UUID datasetId, String externalId, UUID id);
}
