package com.rageval.workbench.classification.infrastructure;

import com.rageval.workbench.classification.domain.EvaluationFinding;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationFindingRepository extends JpaRepository<EvaluationFinding, UUID> {

    List<EvaluationFinding> findByEvaluationRunId(UUID evaluationRunId);
}
