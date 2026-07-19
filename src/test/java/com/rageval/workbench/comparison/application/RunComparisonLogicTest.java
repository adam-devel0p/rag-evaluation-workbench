package com.rageval.workbench.comparison.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.comparison.application.RunComparison.CaseChange;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.application.RunSummary;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The spec §19 comparison rules without a database: change classification is only ever
 * IMPROVED/REGRESSED between two conclusive outcomes, matching is by external test-case ID, rate
 * deltas require both rates, and differing evaluator versions always produce an explicit warning.
 */
class RunComparisonLogicTest {

    @Test
    void changeClassificationRequiresConclusiveOutcomesOnBothSides() {
        assertThat(RunComparisonService.classifyChange(TestCaseOutcome.PASSED, TestCaseOutcome.PASSED))
                .isEqualTo(CaseChange.UNCHANGED);
        assertThat(RunComparisonService.classifyChange(TestCaseOutcome.FAILED, TestCaseOutcome.FAILED))
                .isEqualTo(CaseChange.UNCHANGED);
        assertThat(RunComparisonService.classifyChange(TestCaseOutcome.FAILED, TestCaseOutcome.PASSED))
                .isEqualTo(CaseChange.IMPROVED);
        assertThat(RunComparisonService.classifyChange(TestCaseOutcome.PASSED, TestCaseOutcome.FAILED))
                .isEqualTo(CaseChange.REGRESSED);
        // An evaluator-infrastructure problem on either side must never be sold as a change of the
        // chatbot: INCONCLUSIVE/NOT_EVALUATED always yields INCONCLUSIVE.
        assertThat(RunComparisonService.classifyChange(TestCaseOutcome.INCONCLUSIVE, TestCaseOutcome.PASSED))
                .isEqualTo(CaseChange.INCONCLUSIVE);
        assertThat(RunComparisonService.classifyChange(TestCaseOutcome.FAILED, TestCaseOutcome.NOT_EVALUATED))
                .isEqualTo(CaseChange.INCONCLUSIVE);
        assertThat(RunComparisonService.classifyChange(TestCaseOutcome.NOT_EVALUATED, TestCaseOutcome.INCONCLUSIVE))
                .isEqualTo(CaseChange.INCONCLUSIVE);
    }

    @Test
    void casesAreMatchedByExternalIdAndUnmatchedOnesAreAddedOrRemoved() {
        List<RunSummary.TestCaseSummary> baseline = List.of(
                testCase("TC-1", TestCaseOutcome.PASSED),
                testCase("TC-2", TestCaseOutcome.FAILED),
                testCase("TC-3", TestCaseOutcome.FAILED));
        List<RunSummary.TestCaseSummary> candidate = List.of(
                testCase("TC-1", TestCaseOutcome.FAILED),
                testCase("TC-2", TestCaseOutcome.PASSED),
                testCase("TC-4", TestCaseOutcome.PASSED));

        RunComparison.CaseChangeSummary summary = RunComparisonService.compareCases(baseline, candidate);

        assertThat(summary.added()).isEqualTo(1);
        assertThat(summary.removed()).isEqualTo(1);
        assertThat(summary.improved()).isEqualTo(1);
        assertThat(summary.regressed()).isEqualTo(1);
        assertThat(summary.unchanged()).isZero();
        assertThat(summary.inconclusive()).isZero();
        assertThat(summary.cases()).hasSize(4);
        assertThat(changeOf(summary, "TC-1")).isEqualTo(CaseChange.REGRESSED);
        assertThat(changeOf(summary, "TC-2")).isEqualTo(CaseChange.IMPROVED);
        assertThat(changeOf(summary, "TC-3")).isEqualTo(CaseChange.REMOVED);
        assertThat(changeOf(summary, "TC-4")).isEqualTo(CaseChange.ADDED);
        RunComparison.CaseComparison removed = summary.cases().stream()
                .filter(comparison -> comparison.externalTestCaseId().equals("TC-3"))
                .findFirst()
                .orElseThrow();
        assertThat(removed.candidateOutcome()).isNull();
        assertThat(removed.baselineOutcome()).isEqualTo(TestCaseOutcome.FAILED);
    }

    @Test
    void metricRateDeltaIsAbsentUnlessBothSidesHaveARate() {
        List<RunSummary.MetricSummary> baseline = List.of(
                metric("required-facts", 4, 8, 0.5),
                metric("refusal", 0, 0, null));
        List<RunSummary.MetricSummary> candidate = List.of(
                metric("required-facts", 6, 8, 0.75),
                metric("refusal", 2, 2, 1.0),
                metric("latency", 3, 4, 0.75));

        List<RunComparison.MetricRateComparison> rates =
                RunComparisonService.compareMetricRates(baseline, candidate);

        RunComparison.MetricRateComparison requiredFacts = rateOf(rates, "required-facts");
        assertThat(requiredFacts.delta()).isEqualTo(0.25);
        assertThat(requiredFacts.baselinePassedCount()).isEqualTo(4);
        assertThat(requiredFacts.candidateCompletedCount()).isEqualTo(8);

        // Zero-denominator baseline: no rate, so no delta — never a fake 0%-to-100% jump.
        RunComparison.MetricRateComparison refusal = rateOf(rates, "refusal");
        assertThat(refusal.baselineRate()).isNull();
        assertThat(refusal.candidateRate()).isEqualTo(1.0);
        assertThat(refusal.delta()).isNull();

        // Metric only present in the candidate run: baseline side is empty, no delta.
        RunComparison.MetricRateComparison latency = rateOf(rates, "latency");
        assertThat(latency.baselineCompletedCount()).isZero();
        assertThat(latency.baselineRate()).isNull();
        assertThat(latency.delta()).isNull();
    }

    @Test
    void differingEvaluatorVersionsProduceAnExplicitWarning() {
        List<EvaluationMetricResult> baseline = List.of(
                result("required-facts", "1.0.0", null, null),
                result("llm-groundedness", "1.0.0", "groundedness-v1", "llama3:8b"));
        List<EvaluationMetricResult> candidate = List.of(
                result("required-facts", "1.0.0", null, null),
                result("llm-groundedness", "1.1.0", "groundedness-v2", "llama3:8b"));

        List<RunComparison.EvaluatorVersionComparison> comparisons =
                RunComparisonService.compareEvaluatorVersions(baseline, candidate);
        List<String> warnings = RunComparisonService.evaluatorVersionWarnings(comparisons);

        assertThat(comparisons).hasSize(2);
        assertThat(versionOf(comparisons, "required-facts").matching()).isTrue();
        assertThat(versionOf(comparisons, "llm-groundedness").matching()).isFalse();
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0))
                .contains("llm-groundedness")
                .contains("groundedness-v1")
                .contains("groundedness-v2")
                .contains("not scientifically clean");
    }

    @Test
    void evaluatorPresentInOnlyOneRunIsWarnedAsNotComparable() {
        List<EvaluationMetricResult> baseline = List.of(result("required-facts", "1.0.0", null, null));
        List<EvaluationMetricResult> candidate = List.of(
                result("required-facts", "1.0.0", null, null),
                result("llm-completeness", "1.0.0", "completeness-v1", "llama3:8b"));

        List<String> warnings = RunComparisonService.evaluatorVersionWarnings(
                RunComparisonService.compareEvaluatorVersions(baseline, candidate));

        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("llm-completeness").contains("only in the candidate run");
    }

    @Test
    void identicalVersionsProduceNoWarnings() {
        List<EvaluationMetricResult> results = List.of(
                result("required-facts", "1.0.0", null, null),
                result("llm-groundedness", "1.0.0", "groundedness-v1", "llama3:8b"));

        List<String> warnings = RunComparisonService.evaluatorVersionWarnings(
                RunComparisonService.compareEvaluatorVersions(results, results));

        assertThat(warnings).isEmpty();
    }

    private static RunSummary.TestCaseSummary testCase(String externalId, TestCaseOutcome outcome) {
        return new RunSummary.TestCaseSummary(
                UUID.randomUUID(), externalId, Severity.MEDIUM, List.of(), outcome, List.of(outcome));
    }

    private static RunSummary.MetricSummary metric(String key, long passed, long completed, Double passRate) {
        return new RunSummary.MetricSummary(
                key, completed, completed, passed, completed - passed, 0, 0, 0, 0, passRate);
    }

    private static EvaluationMetricResult result(
            String evaluatorKey, String evaluatorVersion, String promptVersion, String evaluatorModel) {
        return new EvaluationMetricResult(
                UUID.randomUUID(),
                evaluatorKey,
                promptVersion != null ? EvaluatorType.LLM : EvaluatorType.DETERMINISTIC,
                evaluatorVersion,
                evaluatorModel,
                promptVersion,
                MetricResultStatus.COMPLETED,
                true,
                1.0,
                1.0,
                "unit-test result",
                List.of(),
                false,
                1L,
                null);
    }

    private static CaseChange changeOf(RunComparison.CaseChangeSummary summary, String externalId) {
        return summary.cases().stream()
                .filter(comparison -> comparison.externalTestCaseId().equals(externalId))
                .findFirst()
                .orElseThrow()
                .change();
    }

    private static RunComparison.MetricRateComparison rateOf(
            List<RunComparison.MetricRateComparison> rates, String evaluatorKey) {
        return rates.stream()
                .filter(rate -> rate.evaluatorKey().equals(evaluatorKey))
                .findFirst()
                .orElseThrow();
    }

    private static RunComparison.EvaluatorVersionComparison versionOf(
            List<RunComparison.EvaluatorVersionComparison> comparisons, String evaluatorKey) {
        return comparisons.stream()
                .filter(comparison -> comparison.evaluatorKey().equals(evaluatorKey))
                .findFirst()
                .orElseThrow();
    }
}
