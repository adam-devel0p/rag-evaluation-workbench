package com.rageval.workbench.run.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What run orchestration needs from the evaluation side, defined here so the dependency direction
 * stays acyclic: the {@code run} module owns this interface, the evaluation/classification modules
 * implement it. {@link RunWorker} calls {@link #evaluateExecution} after each persisted target
 * result and {@link #completeRunEvaluation} once before finalizing the run.
 */
public interface EvaluationPipeline {

    /** Evaluates one terminal execution; must be idempotent and must never throw for evaluator errors. */
    void evaluateExecution(UUID executionId);

    /**
     * Run-completion work: evaluates any terminal executions still missing metric results (resume
     * safety net) and (re)generates the run's failure classification findings.
     */
    void completeRunEvaluation(UUID runId);

    /** Registered evaluator keys, for validating a run's {@code enabledEvaluatorKeys} at creation. */
    Set<String> knownEvaluatorKeys();

    /**
     * Validates a run's {@code evaluatorConfiguration} map at creation time.
     *
     * @throws IllegalArgumentException describing the offending key when the map is malformed
     */
    void validateEvaluatorConfiguration(Map<String, Object> configuration);
}
