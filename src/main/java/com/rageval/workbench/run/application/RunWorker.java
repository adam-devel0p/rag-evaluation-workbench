package com.rageval.workbench.run.application;

import com.rageval.workbench.run.application.RunStateService.PendingExecution;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.target.application.TargetSystemConfigurationFactory;
import com.rageval.workbench.target.domain.TargetInvocationOutcome;
import com.rageval.workbench.target.domain.TargetInvocationRequest;
import com.rageval.workbench.target.domain.TargetInvocationResult;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.domain.TargetSystemAdapter;
import com.rageval.workbench.target.domain.TargetSystemConfiguration;
import com.rageval.workbench.target.infrastructure.TargetSystemAdapterRegistry;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.stereotype.Component;

/**
 * The task body executed on a bounded run-worker thread: one invocation of {@link #process}
 * drives one whole run, execution by execution, persisting every step through
 * {@link RunStateService}. Cancellation is observed between executions (the cancel endpoint flips
 * the persisted run status; the worker checks it before each invocation). On graceful shutdown a
 * volatile flag makes the worker stop between executions, leaving the run RUNNING in the database
 * — startup recovery then marks it INTERRUPTED, which is exactly the semantics spec §15 asks for.
 */
@Component
public class RunWorker implements ApplicationListener<ContextClosedEvent> {

    private static final Logger log = LoggerFactory.getLogger(RunWorker.class);

    private final RunStateService stateService;
    private final TargetSystemRepository targetSystemRepository;
    private final TargetSystemConfigurationFactory configurationFactory;
    private final TargetSystemAdapterRegistry adapterRegistry;
    private final TargetConcurrencyLimiter concurrencyLimiter;
    private final EvaluationPipeline evaluationPipeline;

    private volatile boolean shuttingDown;

    public RunWorker(
            RunStateService stateService,
            TargetSystemRepository targetSystemRepository,
            TargetSystemConfigurationFactory configurationFactory,
            TargetSystemAdapterRegistry adapterRegistry,
            TargetConcurrencyLimiter concurrencyLimiter,
            EvaluationPipeline evaluationPipeline) {
        this.stateService = stateService;
        this.targetSystemRepository = targetSystemRepository;
        this.configurationFactory = configurationFactory;
        this.adapterRegistry = adapterRegistry;
        this.concurrencyLimiter = concurrencyLimiter;
        this.evaluationPipeline = evaluationPipeline;
    }

    @Override
    public void onApplicationEvent(ContextClosedEvent event) {
        shuttingDown = true;
    }

    public void process(UUID runId) {
        try {
            if (!stateService.markRunning(runId)) {
                return;
            }
            stateService.createPendingExecutionsIfAbsent(runId);

            List<PendingExecution> pending = stateService.pendingExecutionSnapshots(runId);
            // Replay executions (sourceExecutionId set) never contact the target, so the target's
            // configuration — including its secret env var — is only resolved when at least one
            // execution actually needs a live invocation. A pure replay run must work even if the
            // target's credentials are no longer available.
            TargetSystem target = null;
            TargetSystemConfiguration configuration = null;
            TargetSystemAdapter adapter = null;
            if (pending.stream().anyMatch(execution -> execution.sourceExecutionId() == null)) {
                target = targetSystemRepository
                        .findById(loadTargetSystemId(runId))
                        .orElseThrow(() -> new IllegalStateException("Target system vanished for run " + runId));
                configuration = configurationFactory.create(target);
                adapter = adapterRegistry.resolve(target.getAdapterType());
            }

            for (PendingExecution execution : pending) {
                if (shuttingDown) {
                    log.info("Run {}: shutdown in progress, stopping before execution {}", runId, execution.executionId());
                    return;
                }
                if (stateService.currentRunStatus(runId) == RunStatus.CANCELLED) {
                    stateService.cancelRemainingExecutions(runId, "run cancelled");
                    return;
                }
                if (execution.sourceExecutionId() != null) {
                    replayOne(execution);
                } else {
                    invokeOne(runId, target, configuration, adapter, execution);
                }
            }
            // Spec §15 orchestration: evaluate → classify → aggregate before the run goes
            // terminal. Aggregation itself is computed on demand by the summary endpoint; what
            // must exist beforehand are metric results and findings.
            evaluationPipeline.completeRunEvaluation(runId);
            stateService.finalizeRun(runId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("Run {}: worker interrupted, leaving run for startup recovery", runId);
        } catch (RuntimeException ex) {
            log.error("Run {}: worker failed unexpectedly", runId, ex);
            stateService.markRunFailedIfRunning(runId, "Worker failed unexpectedly: " + ex.getMessage());
        }
    }

    /** Replay execution: copy the captured source result and re-run the evaluator pipeline — no target contact. */
    private void replayOne(PendingExecution execution) {
        stateService.markExecutionRunning(execution.executionId());
        stateService.copyFromSourceExecution(execution.executionId());
        evaluationPipeline.evaluateExecution(execution.executionId());
    }

    private void invokeOne(
            UUID runId,
            TargetSystem target,
            TargetSystemConfiguration configuration,
            TargetSystemAdapter adapter,
            PendingExecution execution)
            throws InterruptedException {
        stateService.markExecutionRunning(execution.executionId());
        TargetInvocationRequest request = new TargetInvocationRequest(
                execution.executionId(),
                execution.externalTestCaseId(),
                execution.question(),
                execution.executionId().toString(),
                execution.userContext());
        concurrencyLimiter.acquire(target.getId(), target.getMaximumConcurrency());
        TargetInvocationResult result;
        try {
            result = adapter.invoke(configuration, request);
        } catch (RuntimeException ex) {
            // Adapters are expected to return failure results, not throw; this is a defensive net
            // so one broken invocation cannot kill the whole run.
            log.error("Run {}: adapter threw for execution {}", runId, execution.executionId(), ex);
            result = new TargetInvocationResult(
                    TargetInvocationOutcome.INVALID_RESPONSE,
                    null,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    null,
                    null,
                    0L,
                    null,
                    null,
                    "ADAPTER_ERROR",
                    ex.getMessage(),
                    1);
        } finally {
            concurrencyLimiter.release(target.getId(), target.getMaximumConcurrency());
        }
        stateService.recordInvocationResult(execution.executionId(), result);
        evaluationPipeline.evaluateExecution(execution.executionId());
    }

    private UUID loadTargetSystemId(UUID runId) {
        return stateService.targetSystemIdOf(runId);
    }
}
