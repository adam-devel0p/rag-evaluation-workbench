package com.rageval.workbench.target.infrastructure;

import com.rageval.workbench.target.domain.TargetSystem;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TargetSystemRepository extends JpaRepository<TargetSystem, UUID> {
}
