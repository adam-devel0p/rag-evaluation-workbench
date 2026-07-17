package com.rageval.workbench.evaluation.domain;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Everything an {@link EvaluationStrategy} may look at for one execution (spec §10): the run, the
 * test case, the execution itself, the resolved evaluator configuration, and the metric results
 * already completed for this execution (for strategies that build on earlier verdicts).
 */
public record EvaluationContext(
        EvaluationRun run,
        EvaluationTestCase testCase,
        TestExecution execution,
        EvaluatorSettings settings,
        List<EvaluationMetricResult> completedResults) {

    public EvaluationContext {
        Objects.requireNonNull(run, "run must not be null");
        Objects.requireNonNull(testCase, "testCase must not be null");
        Objects.requireNonNull(execution, "execution must not be null");
        Objects.requireNonNull(settings, "settings must not be null");
        completedResults = completedResults != null ? List.copyOf(completedResults) : List.of();
    }

    public Optional<EvaluationMetricResult> completedResult(String evaluatorKey) {
        return completedResults.stream()
                .filter(result -> result.getEvaluatorKey().equals(evaluatorKey))
                .findFirst();
    }

    /** True when the target produced a successful invocation with a non-blank answer to judge. */
    public boolean hasSuccessfulAnswer() {
        return execution.getStatus() == ExecutionStatus.SUCCEEDED
                && execution.getAnswer() != null
                && !execution.getAnswer().isBlank();
    }
}
