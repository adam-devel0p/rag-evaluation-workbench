package com.rageval.workbench.run.application;

import com.rageval.workbench.common.error.ConflictException;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import com.rageval.workbench.target.domain.TargetInvocationOutcome;
import com.rageval.workbench.target.domain.TargetInvocationResult;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every persisted state change of the run/execution state machine lives here, one short
 * transaction per step, so there is never in-memory-only job state: whatever the worker knows is
 * in the database the moment it knows it, and a crash at any point leaves a consistent,
 * recoverable picture. Entities are always reloaded by id inside each transaction — the worker
 * never holds a stale {@code EvaluationRun} across steps, which also keeps {@code @Version}
 * conflicts with the cancel endpoint out of the picture.
 */
@Service
public class RunStateService {

    private static final Logger log = LoggerFactory.getLogger(RunStateService.class);

    private final EvaluationRunRepository runRepository;
    private final TestExecutionRepository executionRepository;
    private final EvaluationTestCaseRepository testCaseRepository;

    public RunStateService(
            EvaluationRunRepository runRepository,
            TestExecutionRepository executionRepository,
            EvaluationTestCaseRepository testCaseRepository) {
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.testCaseRepository = testCaseRepository;
    }

    /** A read-only snapshot of one pending execution plus the test-case data needed to invoke the target. */
    public record PendingExecution(
            UUID executionId,
            UUID testCaseId,
            String externalTestCaseId,
            String question,
            Map<String, Object> userContext) {
    }

    @Transactional
    public void queueForStart(UUID runId) {
        EvaluationRun run = loadRun(runId);
        if (run.getStatus() != RunStatus.CREATED) {
            throw new ConflictException(
                    "Run " + runId + " is " + run.getStatus() + "; only a CREATED run can be started — use resume for "
                            + "an interrupted, cancelled, failed, or partially completed run");
        }
        run.markQueued();
    }

    /** Re-queues a resumable run and resets its CANCELLED executions to PENDING. Never touches SUCCEEDED or completed-with-error executions. */
    @Transactional
    public void queueForResume(UUID runId) {
        EvaluationRun run = loadRun(runId);
        if (!run.isResumable()) {
            throw new ConflictException(
                    "Run " + runId + " is " + run.getStatus()
                            + " and cannot be resumed (only INTERRUPTED, CANCELLED, FAILED, or PARTIALLY_COMPLETED runs can)");
        }
        List<TestExecution> cancelled =
                executionRepository.findByEvaluationRunIdAndStatus(runId, ExecutionStatus.CANCELLED);
        cancelled.forEach(TestExecution::resetForResume);
        long pending = executionRepository.countByEvaluationRunIdAndStatus(runId, ExecutionStatus.PENDING);
        long total = executionRepository.countByEvaluationRunId(runId);
        if (total > 0 && pending == 0) {
            throw new ConflictException(
                    "Run " + runId + " has no incomplete executions to resume; successful and completed-with-error "
                            + "executions are never repeated");
        }
        run.markQueued();
    }

    /** Compensation when the bounded executor rejects a freshly queued run: it was accepted but never executed. */
    @Transactional
    public void markInterruptedAfterQueueRejection(UUID runId) {
        loadRun(runId).markInterrupted();
    }

    /**
     * Worker entry: QUEUED → RUNNING. Returns false when the run was cancelled (or otherwise moved
     * on) between enqueue and pickup, telling the worker to drop the task without touching anything.
     */
    @Transactional
    public boolean markRunning(UUID runId) {
        EvaluationRun run = loadRun(runId);
        if (run.getStatus() != RunStatus.QUEUED) {
            log.info("Run {} is {} at worker pickup; skipping execution", runId, run.getStatus());
            return false;
        }
        run.markStarted();
        return true;
    }

    /** Creates the pending execution grid (every repetition of every test case) unless executions already exist (resume / imported traces). */
    @Transactional
    public void createPendingExecutionsIfAbsent(UUID runId) {
        if (executionRepository.countByEvaluationRunId(runId) > 0) {
            return;
        }
        EvaluationRun run = loadRun(runId);
        List<EvaluationTestCase> testCases = testCaseRepository.findByDatasetId(run.getDatasetId());
        for (EvaluationTestCase testCase : testCases) {
            for (int repetition = 1; repetition <= run.getRepetitionsPerTestCase(); repetition++) {
                executionRepository.save(new TestExecution(runId, testCase.getId(), null, repetition));
            }
        }
        log.info(
                "Run {}: created {} pending executions ({} test cases x {} repetitions)",
                runId,
                testCases.size() * run.getRepetitionsPerTestCase(),
                testCases.size(),
                run.getRepetitionsPerTestCase());
    }

    @Transactional(readOnly = true)
    public List<PendingExecution> pendingExecutionSnapshots(UUID runId) {
        EvaluationRun run = loadRun(runId);
        Map<UUID, EvaluationTestCase> testCasesById = testCaseRepository.findByDatasetId(run.getDatasetId()).stream()
                .collect(java.util.stream.Collectors.toMap(EvaluationTestCase::getId, testCase -> testCase));
        return executionRepository.findByEvaluationRunIdAndStatus(runId, ExecutionStatus.PENDING).stream()
                .sorted(Comparator.comparing((TestExecution execution) -> {
                            EvaluationTestCase testCase = testCasesById.get(execution.getTestCaseId());
                            return testCase != null ? testCase.getExternalId() : execution.getTestCaseId().toString();
                        })
                        .thenComparingInt(TestExecution::getRepetitionNumber))
                .map(execution -> {
                    EvaluationTestCase testCase = testCasesById.get(execution.getTestCaseId());
                    return new PendingExecution(
                            execution.getId(),
                            execution.getTestCaseId(),
                            testCase != null ? testCase.getExternalId() : null,
                            testCase != null ? testCase.getQuestion() : null,
                            testCase != null ? testCase.getUserContext() : null);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public RunStatus currentRunStatus(UUID runId) {
        return loadRun(runId).getStatus();
    }

    @Transactional(readOnly = true)
    public UUID targetSystemIdOf(UUID runId) {
        return loadRun(runId).getTargetSystemId();
    }

    @Transactional
    public void markExecutionRunning(UUID executionId) {
        loadExecution(executionId).markRunning();
    }

    @Transactional
    public void recordInvocationResult(UUID executionId, TargetInvocationResult result) {
        TestExecution execution = loadExecution(executionId);
        Instant completedAt = Instant.now();
        if (result.outcome() == TargetInvocationOutcome.SUCCEEDED) {
            execution.recordSuccess(
                    result.requestPayload(),
                    result.answer(),
                    result.rawResponse(),
                    result.citations(),
                    result.retrievedChunks(),
                    result.targetModel(),
                    result.latencyMilliseconds(),
                    result.inputTokens(),
                    result.outputTokens(),
                    result.httpStatus(),
                    result.attemptCount(),
                    execution.getStartedAt(),
                    completedAt);
        } else {
            execution.recordFailure(
                    toExecutionStatus(result.outcome()),
                    result.requestPayload(),
                    result.rawResponse(),
                    result.errorType(),
                    result.errorMessage(),
                    result.httpStatus(),
                    result.attemptCount(),
                    execution.getStartedAt(),
                    completedAt);
        }
    }

    @Transactional
    public void cancelRemainingExecutions(UUID runId, String reason) {
        List<TestExecution> remaining = executionRepository.findByEvaluationRunIdAndStatusIn(
                runId, List.of(ExecutionStatus.PENDING, ExecutionStatus.RUNNING));
        remaining.forEach(execution -> execution.markCancelled(reason));
        if (!remaining.isEmpty()) {
            log.info("Run {}: cancelled {} remaining executions ({})", runId, remaining.size(), reason);
        }
    }

    /**
     * Terminal bookkeeping after the worker has processed every execution it was going to process.
     * COMPLETED when every execution succeeded, PARTIALLY_COMPLETED when at least one succeeded
     * (spec §15: enough results exist to produce a report), FAILED when none did. A run found
     * CANCELLED here was cancelled mid-flight — remaining executions are cancelled and the run's
     * CANCELLED status is left untouched.
     */
    @Transactional
    public void finalizeRun(UUID runId) {
        EvaluationRun run = loadRun(runId);
        if (run.getStatus() == RunStatus.CANCELLED) {
            cancelRemainingExecutions(runId, "run cancelled");
            return;
        }
        if (run.getStatus() != RunStatus.RUNNING) {
            log.warn("Run {} is {} at finalization; leaving as-is", runId, run.getStatus());
            return;
        }
        long total = executionRepository.countByEvaluationRunId(runId);
        long succeeded = executionRepository.countByEvaluationRunIdAndStatus(runId, ExecutionStatus.SUCCEEDED);
        if (total > 0 && succeeded == total) {
            run.markCompleted(RunStatus.COMPLETED);
        } else if (succeeded > 0) {
            run.markCompleted(RunStatus.PARTIALLY_COMPLETED);
        } else {
            run.markFailed(total == 0 ? "Dataset produced no executions" : "No execution succeeded");
        }
        log.info("Run {} finalized as {} ({}/{} executions succeeded)", runId, run.getStatus(), succeeded, total);
    }

    /** Last-resort guard for an unexpected worker crash; only demotes a run that is actually still RUNNING. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunFailedIfRunning(UUID runId, String reason) {
        EvaluationRun run = loadRun(runId);
        if (run.getStatus() == RunStatus.RUNNING) {
            run.markFailed(reason);
        }
    }

    private ExecutionStatus toExecutionStatus(TargetInvocationOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> ExecutionStatus.SUCCEEDED;
            case TIMED_OUT -> ExecutionStatus.TIMED_OUT;
            case TARGET_ERROR -> ExecutionStatus.TARGET_ERROR;
            case INVALID_RESPONSE -> ExecutionStatus.INVALID_RESPONSE;
        };
    }

    private EvaluationRun loadRun(UUID runId) {
        return runRepository
                .findById(runId)
                .orElseThrow(() -> new NotFoundException("Evaluation run not found: " + runId));
    }

    private TestExecution loadExecution(UUID executionId) {
        return executionRepository
                .findById(executionId)
                .orElseThrow(() -> new NotFoundException("Test execution not found: " + executionId));
    }
}
