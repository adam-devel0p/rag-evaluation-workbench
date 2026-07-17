package com.rageval.workbench.evaluation.infrastructure;

import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationMetricResultRepository extends JpaRepository<EvaluationMetricResult, UUID> {

    List<EvaluationMetricResult> findByTestExecutionId(UUID testExecutionId);

    List<EvaluationMetricResult> findByTestExecutionIdAndEvaluatorKey(
            UUID testExecutionId, String evaluatorKey);
}
