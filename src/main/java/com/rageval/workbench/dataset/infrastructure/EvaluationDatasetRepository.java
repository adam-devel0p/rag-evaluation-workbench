package com.rageval.workbench.dataset.infrastructure;

import com.rageval.workbench.dataset.domain.EvaluationDataset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationDatasetRepository extends JpaRepository<EvaluationDataset, UUID> {
}
