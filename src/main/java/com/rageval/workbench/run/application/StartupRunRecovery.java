package com.rageval.workbench.run.application;

import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup recovery (spec §15): a run found QUEUED or RUNNING at startup belonged to a previous
 * process — its executor queue and worker threads are gone — so it is marked INTERRUPTED, never
 * silently resumed. Its stale RUNNING executions are marked CANCELLED (their in-flight invocation
 * died with the process; the resume endpoint schedules them again), while PENDING ones stay
 * PENDING for resume to pick up.
 */
@Component
public class StartupRunRecovery {

    private static final Logger log = LoggerFactory.getLogger(StartupRunRecovery.class);

    private final EvaluationRunRepository runRepository;
    private final TestExecutionRepository executionRepository;

    public StartupRunRecovery(EvaluationRunRepository runRepository, TestExecutionRepository executionRepository) {
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverStaleRuns() {
        List<EvaluationRun> stale = new ArrayList<>();
        stale.addAll(runRepository.findByStatus(RunStatus.RUNNING));
        stale.addAll(runRepository.findByStatus(RunStatus.QUEUED));
        for (EvaluationRun run : stale) {
            List<TestExecution> staleExecutions =
                    executionRepository.findByEvaluationRunIdAndStatus(run.getId(), ExecutionStatus.RUNNING);
            staleExecutions.forEach(execution -> execution.markCancelled("interrupted by application restart"));
            RunStatus previousStatus = run.getStatus();
            run.markInterrupted();
            log.warn(
                    "Run {} was {} at startup; marked INTERRUPTED ({} stale in-flight executions cancelled). "
                            + "Use POST /api/v1/evaluation-runs/{}/resume to continue it.",
                    run.getId(),
                    previousStatus,
                    staleExecutions.size(),
                    run.getId());
        }
        if (!stale.isEmpty()) {
            log.warn("Startup recovery marked {} stale run(s) INTERRUPTED", stale.size());
        }
    }
}
