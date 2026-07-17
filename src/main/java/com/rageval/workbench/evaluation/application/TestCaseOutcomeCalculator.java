package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import java.util.Collection;
import java.util.List;

/**
 * The spec §18 default outcome rule, implemented transparently: an execution passes only if all
 * enabled <em>mandatory</em> evaluators that completed successfully pass. An evaluator
 * infrastructure failure (metric status FAILED) on a mandatory evaluator makes the outcome
 * INCONCLUSIVE — never FAILED — because nothing was proven about the chatbot. SKIPPED
 * (not-applicable) results are ignored entirely; an execution with no completed mandatory results
 * at all is NOT_EVALUATED.
 */
public final class TestCaseOutcomeCalculator {

    private TestCaseOutcomeCalculator() {
    }

    /** Outcome of a single execution from its persisted metric results. */
    public static TestCaseOutcome executionOutcome(
            Collection<String> mandatoryEvaluatorKeys, Collection<EvaluationMetricResult> results) {
        boolean anyFailed = false;
        boolean anyEvaluatorError = false;
        boolean anyCompleted = false;
        for (EvaluationMetricResult result : results) {
            if (!mandatoryEvaluatorKeys.contains(result.getEvaluatorKey())) {
                continue;
            }
            if (result.getStatus() == MetricResultStatus.FAILED) {
                anyEvaluatorError = true;
            } else if (result.getStatus() == MetricResultStatus.COMPLETED) {
                anyCompleted = true;
                if (Boolean.FALSE.equals(result.getPassed())) {
                    anyFailed = true;
                }
            }
        }
        if (anyFailed) {
            return TestCaseOutcome.FAILED;
        }
        if (anyEvaluatorError) {
            return TestCaseOutcome.INCONCLUSIVE;
        }
        return anyCompleted ? TestCaseOutcome.PASSED : TestCaseOutcome.NOT_EVALUATED;
    }

    /**
     * Outcome of a test case over all its repetitions: any failing repetition fails the test case;
     * otherwise any inconclusive repetition makes it inconclusive; otherwise it passes if at least
     * one repetition was actually evaluated.
     */
    public static TestCaseOutcome testCaseOutcome(List<TestCaseOutcome> executionOutcomes) {
        if (executionOutcomes.contains(TestCaseOutcome.FAILED)) {
            return TestCaseOutcome.FAILED;
        }
        if (executionOutcomes.contains(TestCaseOutcome.INCONCLUSIVE)) {
            return TestCaseOutcome.INCONCLUSIVE;
        }
        if (executionOutcomes.contains(TestCaseOutcome.PASSED)) {
            return TestCaseOutcome.PASSED;
        }
        return TestCaseOutcome.NOT_EVALUATED;
    }
}
