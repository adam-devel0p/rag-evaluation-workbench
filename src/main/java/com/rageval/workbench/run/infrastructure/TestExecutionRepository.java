package com.rageval.workbench.run.infrastructure;

import com.rageval.workbench.run.domain.TestExecution;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestExecutionRepository extends JpaRepository<TestExecution, UUID> {

    List<TestExecution> findByEvaluationRunId(UUID evaluationRunId);

    List<TestExecution> findByEvaluationRunIdAndTestCaseId(UUID evaluationRunId, UUID testCaseId);
}
