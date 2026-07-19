package com.rageval.workbench.run.application;

import com.rageval.workbench.common.error.ConflictException;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.configuration.ExecutionProperties;
import com.rageval.workbench.dataset.domain.DatasetStatus;
import com.rageval.workbench.dataset.domain.EvaluationDataset;
import com.rageval.workbench.dataset.infrastructure.EvaluationDatasetRepository;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.run.api.dto.CreateRunRequest;
import com.rageval.workbench.run.api.dto.ReplayRunRequest;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.RunMode;
import com.rageval.workbench.run.domain.RunStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * API-facing run lifecycle: create (validate fast, return immediately), start/resume (persist
 * QUEUED, then hand the run to the bounded executor), cancel, and reads. Deliberately thin — the
 * per-execution work happens in {@link RunWorker} on a worker thread, and every persisted state
 * change goes through {@link RunStateService}. Submission happens <em>after</em> the QUEUED
 * transaction has committed so a fast worker can never observe a stale CREATED status.
 */
@Service
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final EvaluationRunRepository runRepository;
    private final TestExecutionRepository executionRepository;
    private final TargetSystemRepository targetSystemRepository;
    private final EvaluationDatasetRepository datasetRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final RunStateService stateService;
    private final RunWorker runWorker;
    private final ThreadPoolTaskExecutor runExecutor;
    private final ExecutionProperties executionProperties;
    private final EvaluationPipeline evaluationPipeline;

    public RunService(
            EvaluationRunRepository runRepository,
            TestExecutionRepository executionRepository,
            TargetSystemRepository targetSystemRepository,
            EvaluationDatasetRepository datasetRepository,
            EvaluationTestCaseRepository testCaseRepository,
            RunStateService stateService,
            RunWorker runWorker,
            @Qualifier("runExecutionExecutor") ThreadPoolTaskExecutor runExecutor,
            ExecutionProperties executionProperties,
            EvaluationPipeline evaluationPipeline) {
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.targetSystemRepository = targetSystemRepository;
        this.datasetRepository = datasetRepository;
        this.testCaseRepository = testCaseRepository;
        this.stateService = stateService;
        this.runWorker = runWorker;
        this.runExecutor = runExecutor;
        this.executionProperties = executionProperties;
        this.evaluationPipeline = evaluationPipeline;
    }

    @Transactional
    public EvaluationRun create(CreateRunRequest request) {
        TargetSystem target = targetSystemRepository
                .findById(request.targetSystemId())
                .orElseThrow(() -> new NotFoundException("Target system not found: " + request.targetSystemId()));
        if (!target.isEnabled()) {
            throw new ValidationException(
                    "Target system " + target.getId() + " is disabled",
                    new FieldViolation("targetSystemId", "target system is disabled"));
        }
        if (target.getAdapterType() != AdapterType.STANDARD_HTTP) {
            throw new ValidationException(
                    "Live runs require a STANDARD_HTTP target; imported-trace runs are created via POST /api/v1/imported-traces",
                    new FieldViolation("targetSystemId", "adapterType must be STANDARD_HTTP for a live run"));
        }
        EvaluationDataset dataset = requireReadyDataset(request.datasetId());
        if (testCaseRepository.findByDatasetId(dataset.getId()).isEmpty()) {
            throw new ValidationException(
                    "Dataset " + dataset.getId() + " has no test cases",
                    new FieldViolation("datasetId", "dataset must contain at least one test case"));
        }
        int repetitions = request.repetitionsPerTestCase() != null
                ? request.repetitionsPerTestCase()
                : executionProperties.defaultRepetitions();
        if (repetitions < 1) {
            throw new ValidationException(
                    "repetitionsPerTestCase must be at least 1",
                    new FieldViolation("repetitionsPerTestCase", "must be at least 1"));
        }
        validateEvaluatorSelection(request.enabledEvaluatorKeys(), request.evaluatorConfiguration());
        EvaluationRun run = new EvaluationRun(
                target.getId(),
                dataset.getId(),
                request.name(),
                RunMode.LIVE,
                repetitions,
                request.enabledEvaluatorKeys() != null ? request.enabledEvaluatorKeys() : List.of(),
                request.evaluatorConfiguration());
        return runRepository.save(run);
    }

    /**
     * Creates a REPLAY run (spec §6.4/§21) from a source run's captured executions: one pending
     * execution per terminal source execution, each carrying {@code sourceExecutionId}. The worker
     * later copies the stored invocation result instead of calling the target, so the current
     * evaluator pipeline re-judges the exact captured responses. Source executions that never
     * produced target evidence (PENDING/RUNNING/CANCELLED) have nothing to re-evaluate and are
     * skipped. {@code sourceExecutionId} always references the <em>original</em> captured
     * execution — replaying a replay does not build a chain.
     */
    @Transactional
    public EvaluationRun replay(UUID sourceRunId, ReplayRunRequest request) {
        EvaluationRun source = getOrThrow(sourceRunId);
        if (source.getStatus() == RunStatus.CREATED
                || source.getStatus() == RunStatus.QUEUED
                || source.getStatus() == RunStatus.RUNNING) {
            throw new ConflictException(
                    "Run " + sourceRunId + " is " + source.getStatus()
                            + "; only a run that has finished executing can be replayed");
        }
        List<TestExecution> replayable = executionRepository.findByEvaluationRunIdAndStatusIn(
                sourceRunId,
                List.of(
                        ExecutionStatus.SUCCEEDED,
                        ExecutionStatus.TIMED_OUT,
                        ExecutionStatus.TARGET_ERROR,
                        ExecutionStatus.INVALID_RESPONSE));
        if (replayable.isEmpty()) {
            throw new ConflictException(
                    "Run " + sourceRunId + " has no completed executions to replay");
        }
        List<String> enabledEvaluatorKeys = request != null && request.enabledEvaluatorKeys() != null
                ? request.enabledEvaluatorKeys()
                : source.getEnabledEvaluatorKeys();
        var evaluatorConfiguration = request != null && request.evaluatorConfiguration() != null
                ? request.evaluatorConfiguration()
                : source.getEvaluatorConfiguration();
        validateEvaluatorSelection(enabledEvaluatorKeys, evaluatorConfiguration);
        String name = request != null && request.name() != null && !request.name().isBlank()
                ? request.name()
                : source.getName() + " (replay)";
        EvaluationRun replayRun = new EvaluationRun(
                source.getTargetSystemId(),
                source.getDatasetId(),
                name,
                RunMode.REPLAY,
                source.getRepetitionsPerTestCase(),
                enabledEvaluatorKeys,
                evaluatorConfiguration);
        runRepository.save(replayRun);
        for (TestExecution sourceExecution : replayable) {
            UUID originalExecutionId = sourceExecution.getSourceExecutionId() != null
                    ? sourceExecution.getSourceExecutionId()
                    : sourceExecution.getId();
            executionRepository.save(new TestExecution(
                    replayRun.getId(),
                    sourceExecution.getTestCaseId(),
                    originalExecutionId,
                    sourceExecution.getRepetitionNumber()));
        }
        log.info(
                "Run {}: created replay run {} with {} executions",
                sourceRunId,
                replayRun.getId(),
                replayable.size());
        return replayRun;
    }

    public EvaluationRun start(UUID runId) {
        stateService.queueForStart(runId);
        submit(runId);
        return getOrThrow(runId);
    }

    public EvaluationRun resume(UUID runId) {
        stateService.queueForResume(runId);
        submit(runId);
        return getOrThrow(runId);
    }

    /**
     * Sets the persisted status to CANCELLED immediately. A run that is currently executing is
     * stopped cooperatively: the worker observes the status before each execution and cancels the
     * remainder; executions already in flight finish and keep their real result.
     */
    @Transactional
    public EvaluationRun cancel(UUID runId) {
        EvaluationRun run = runRepository
                .findById(runId)
                .orElseThrow(() -> new NotFoundException("Evaluation run not found: " + runId));
        boolean workerActive = run.getStatus() == RunStatus.RUNNING;
        run.markCancelled();
        if (!workerActive) {
            stateService.cancelRemainingExecutions(runId, "run cancelled before execution");
        }
        return run;
    }

    @Transactional(readOnly = true)
    public EvaluationRun getOrThrow(UUID runId) {
        return runRepository
                .findById(runId)
                .orElseThrow(() -> new NotFoundException("Evaluation run not found: " + runId));
    }

    @Transactional(readOnly = true)
    public Page<EvaluationRun> list(Pageable pageable) {
        return runRepository.findAll(pageable);
    }

    @Transactional(readOnly = true)
    public Page<TestExecution> listExecutions(UUID runId, Pageable pageable) {
        getOrThrow(runId);
        return executionRepository.findByEvaluationRunId(runId, pageable);
    }

    private void submit(UUID runId) {
        try {
            runExecutor.execute(() -> runWorker.process(runId));
        } catch (RejectedExecutionException ex) {
            log.warn("Run {}: execution queue is full, marking INTERRUPTED", runId);
            stateService.markInterruptedAfterQueueRejection(runId);
            throw new ConflictException(
                    "Execution queue is full (capacity " + executionProperties.queueCapacity()
                            + "); the run was marked INTERRUPTED — resume it when capacity is available");
        }
    }

    /** Fails run creation fast when evaluator keys are unknown or the configuration map is malformed. */
    private void validateEvaluatorSelection(
            List<String> enabledEvaluatorKeys, Map<String, Object> evaluatorConfiguration) {
        if (enabledEvaluatorKeys != null && !enabledEvaluatorKeys.isEmpty()) {
            var known = evaluationPipeline.knownEvaluatorKeys();
            List<String> unknown = enabledEvaluatorKeys.stream()
                    .filter(key -> !known.contains(key))
                    .toList();
            if (!unknown.isEmpty()) {
                throw new ValidationException(
                        "Unknown evaluator key(s): " + String.join(", ", unknown),
                        new FieldViolation(
                                "enabledEvaluatorKeys",
                                "unknown evaluator key(s) " + unknown + "; known keys: " + known));
            }
        }
        try {
            evaluationPipeline.validateEvaluatorConfiguration(evaluatorConfiguration);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(
                    "Invalid evaluatorConfiguration: " + ex.getMessage(),
                    new FieldViolation("evaluatorConfiguration", ex.getMessage()));
        }
    }

    private EvaluationDataset requireReadyDataset(UUID datasetId) {
        EvaluationDataset dataset = datasetRepository
                .findById(datasetId)
                .orElseThrow(() -> new NotFoundException("Dataset not found: " + datasetId));
        if (dataset.getStatus() != DatasetStatus.READY) {
            throw new ValidationException(
                    "Dataset " + datasetId + " is " + dataset.getStatus() + "; runs require a READY dataset",
                    new FieldViolation("datasetId", "dataset must be READY"));
        }
        return dataset;
    }
}
