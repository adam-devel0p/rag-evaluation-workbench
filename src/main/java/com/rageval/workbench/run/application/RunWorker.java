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

    private volatile boolean shuttingDown;

    public RunWorker(
            RunStateService stateService,
            TargetSystemRepository targetSystemRepository,
            TargetSystemConfigurationFactory configurationFactory,
            TargetSystemAdapterRegistry adapterRegistry,
            TargetConcurrencyLimiter concurrencyLimiter) {
        this.stateService = stateService;
        this.targetSystemRepository = targetSystemRepository;
        this.configurationFactory = configurationFactory;
        this.adapterRegistry = adapterRegistry;
        this.concurrencyLimiter = concurrencyLimiter;
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

            TargetSystem target = targetSystemRepository
                    .findById(loadTargetSystemId(runId))
                    .orElseThrow(() -> new IllegalStateException("Target system vanished for run " + runId));
            TargetSystemConfiguration configuration = configurationFactory.create(target);
            TargetSystemAdapter adapter = adapterRegistry.resolve(target.getAdapterType());

            List<PendingExecution> pending = stateService.pendingExecutionSnapshots(runId);
            for (PendingExecution execution : pending) {
                if (shuttingDown) {
                    log.info("Run {}: shutdown in progress, stopping before execution {}", runId, execution.executionId());
                    return;
                }
                if (stateService.currentRunStatus(runId) == RunStatus.CANCELLED) {
                    stateService.cancelRemainingExecutions(runId, "run cancelled");
                    return;
                }
                invokeOne(runId, target, configuration, adapter, execution);
            }
            stateService.finalizeRun(runId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.info("Run {}: worker interrupted, leaving run for startup recovery", runId);
        } catch (RuntimeException ex) {
            log.error("Run {}: worker failed unexpectedly", runId, ex);
            stateService.markRunFailedIfRunning(runId, "Worker failed unexpectedly: " + ex.getMessage());
        }
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
    }

    private UUID loadTargetSystemId(UUID runId) {
        return stateService.targetSystemIdOf(runId);
    }
}
