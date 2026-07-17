package com.rageval.workbench.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Spec §18 / §28.1 "aggregation denominator logic": evaluator errors never sit in a chatbot
 * pass-rate denominator, and an evaluator infrastructure failure makes an outcome INCONCLUSIVE,
 * never FAILED.
 */
class AggregationLogicTest {

    private static final List<String> MANDATORY = List.of("transport", "required-facts", "forbidden-claims");

    private EvaluationMetricResult result(String key, MetricResultStatus status, Boolean passed) {
        return new EvaluationMetricResult(
                UUID.randomUUID(),
                key,
                EvaluatorType.DETERMINISTIC,
                "1.0.0",
                null,
                null,
                status,
                passed,
                null,
                null,
                "test explanation",
                List.of(),
                false,
                1L,
                status == MetricResultStatus.FAILED ? "boom" : null);
    }

    @Test
    void allMandatoryCompletedAndPassedMeansPassed() {
        List<EvaluationMetricResult> results = List.of(
                result("transport", MetricResultStatus.COMPLETED, true),
                result("required-facts", MetricResultStatus.COMPLETED, true),
                result("forbidden-claims", MetricResultStatus.SKIPPED, null));
        assertThat(TestCaseOutcomeCalculator.executionOutcome(MANDATORY, results))
                .isEqualTo(TestCaseOutcome.PASSED);
    }

    @Test
    void mandatoryEvaluatorErrorMakesTheOutcomeInconclusiveNeverFailed() {
        List<EvaluationMetricResult> results = List.of(
                result("transport", MetricResultStatus.COMPLETED, true),
                result("required-facts", MetricResultStatus.FAILED, null));
        assertThat(TestCaseOutcomeCalculator.executionOutcome(MANDATORY, results))
                .isEqualTo(TestCaseOutcome.INCONCLUSIVE);
    }

    @Test
    void aRealMetricFailureBeatsAnEvaluatorError() {
        List<EvaluationMetricResult> results = List.of(
                result("transport", MetricResultStatus.COMPLETED, false),
                result("required-facts", MetricResultStatus.FAILED, null));
        assertThat(TestCaseOutcomeCalculator.executionOutcome(MANDATORY, results))
                .isEqualTo(TestCaseOutcome.FAILED);
    }

    @Test
    void nonMandatoryFailuresDoNotFailTheOutcome() {
        List<EvaluationMetricResult> results = List.of(
                result("transport", MetricResultStatus.COMPLETED, true),
                result("latency", MetricResultStatus.COMPLETED, false));
        assertThat(TestCaseOutcomeCalculator.executionOutcome(MANDATORY, results))
                .isEqualTo(TestCaseOutcome.PASSED);
    }

    @Test
    void noResultsMeansNotEvaluated() {
        assertThat(TestCaseOutcomeCalculator.executionOutcome(MANDATORY, List.of()))
                .isEqualTo(TestCaseOutcome.NOT_EVALUATED);
        assertThat(TestCaseOutcomeCalculator.executionOutcome(
                        MANDATORY, List.of(result("required-facts", MetricResultStatus.SKIPPED, null))))
                .isEqualTo(TestCaseOutcome.NOT_EVALUATED);
    }

    @Test
    void testCaseOutcomeIsWorstOfItsRepetitions() {
        assertThat(TestCaseOutcomeCalculator.testCaseOutcome(
                        List.of(TestCaseOutcome.PASSED, TestCaseOutcome.FAILED, TestCaseOutcome.INCONCLUSIVE)))
                .isEqualTo(TestCaseOutcome.FAILED);
        assertThat(TestCaseOutcomeCalculator.testCaseOutcome(
                        List.of(TestCaseOutcome.PASSED, TestCaseOutcome.INCONCLUSIVE)))
                .isEqualTo(TestCaseOutcome.INCONCLUSIVE);
        assertThat(TestCaseOutcomeCalculator.testCaseOutcome(
                        List.of(TestCaseOutcome.PASSED, TestCaseOutcome.NOT_EVALUATED)))
                .isEqualTo(TestCaseOutcome.PASSED);
        assertThat(TestCaseOutcomeCalculator.testCaseOutcome(List.of())).isEqualTo(TestCaseOutcome.NOT_EVALUATED);
    }

    @Test
    void metricPassRateExcludesEvaluatorErrorsFromTheDenominator() {
        // 2 completed (1 passed, 1 failed) + 2 evaluator errors: pass rate must be 1/2, not 1/4.
        List<EvaluationMetricResult> results = List.of(
                result("required-facts", MetricResultStatus.COMPLETED, true),
                result("required-facts", MetricResultStatus.COMPLETED, false),
                result("required-facts", MetricResultStatus.FAILED, null),
                result("required-facts", MetricResultStatus.FAILED, null),
                result("required-facts", MetricResultStatus.SKIPPED, null));
        List<RunSummary.MetricSummary> summaries = RunSummaryService.metricSummaries(results, Set.of());
        assertThat(summaries).hasSize(1);
        RunSummary.MetricSummary summary = summaries.get(0);
        assertThat(summary.completedCount()).isEqualTo(2);
        assertThat(summary.evaluatorErrorCount()).isEqualTo(2);
        assertThat(summary.notApplicableCount()).isEqualTo(1);
        assertThat(summary.applicableExecutionCount()).isEqualTo(4);
        assertThat(summary.passRate()).isEqualTo(0.5);
    }

    @Test
    void zeroDenominatorRatesAreNullNotZero() {
        List<EvaluationMetricResult> onlyErrors =
                List.of(result("required-facts", MetricResultStatus.FAILED, null));
        assertThat(RunSummaryService.metricSummaries(onlyErrors, Set.of()).get(0).passRate()).isNull();
        assertThat(RunSummaryService.rate(0, 0)).isNull();
    }
}
