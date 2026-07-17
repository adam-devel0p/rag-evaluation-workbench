package com.rageval.workbench.run.infrastructure;

import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.RunStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRunRepository extends JpaRepository<EvaluationRun, UUID> {

    List<EvaluationRun> findByStatus(RunStatus status);

    boolean existsByTargetSystemId(UUID targetSystemId);
}
