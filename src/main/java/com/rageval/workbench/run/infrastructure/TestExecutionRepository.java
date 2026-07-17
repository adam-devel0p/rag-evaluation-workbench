package com.rageval.workbench.run.infrastructure;

import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {

    List<TestExecution> findByEvaluationRunId(UUID evaluationRunId);

    Page<TestExecution> findByEvaluationRunId(UUID evaluationRunId, Pageable pageable);

    List<TestExecution> findByEvaluationRunIdAndTestCaseId(UUID evaluationRunId, UUID testCaseId);

    List<TestExecution> findByEvaluationRunIdAndStatus(UUID evaluationRunId, ExecutionStatus status);

    List<TestExecution> findByEvaluationRunIdAndStatusIn(UUID evaluationRunId, Collection<ExecutionStatus> statuses);

    long countByEvaluationRunId(UUID evaluationRunId);

    long countByEvaluationRunIdAndStatus(UUID evaluationRunId, ExecutionStatus status);
}
