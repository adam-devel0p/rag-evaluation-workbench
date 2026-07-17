package com.rageval.workbench.evaluation.infrastructure;

import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EvaluationMetricResultRepository extends JpaRepository<EvaluationMetricResult, UUID> {

    List<EvaluationMetricResult> findByTestExecutionId(UUID testExecutionId);

    List<EvaluationMetricResult> findByTestExecutionIdAndEvaluatorKey(
            UUID testExecutionId, String evaluatorKey);

    @Query("select m from EvaluationMetricResult m where m.testExecutionId in "
            + "(select e.id from TestExecution e where e.evaluationRunId = :runId)")
    List<EvaluationMetricResult> findByRunId(@Param("runId") UUID runId);

    @Query("select m from EvaluationMetricResult m where m.testExecutionId in "
            + "(select e.id from TestExecution e where e.evaluationRunId = :runId)")
    Page<EvaluationMetricResult> findByRunId(@Param("runId") UUID runId, Pageable pageable);
}
