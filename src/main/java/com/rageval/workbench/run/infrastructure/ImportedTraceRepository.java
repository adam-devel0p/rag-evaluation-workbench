package com.rageval.workbench.run.infrastructure;

import com.rageval.workbench.run.domain.ImportedTrace;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportedTraceRepository extends JpaRepository<ImportedTrace, UUID> {

    Optional<ImportedTrace> findByTestExecutionId(UUID testExecutionId);
}
