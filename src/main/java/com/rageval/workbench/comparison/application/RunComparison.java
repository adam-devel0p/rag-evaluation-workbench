package com.rageval.workbench.comparison.application;

import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.application.RunSummary;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The structured baseline-vs-candidate comparison (spec §19). Every rate is reported next to the
 * counts it was computed from, and {@code warnings} carries every reason the comparison is not
 * scientifically clean — differing evaluator versions above all. A comparison with warnings is
 * still shown (the consultant decides what it is worth), but it is never presented as clean.
 */
public record RunComparison(
        RunDescriptor baseline,
        RunDescriptor candidate,
        List<String> warnings,
        CaseChangeSummary caseChanges,
        List<MetricRateComparison> metricRates,
        HighSeverityFindingComparison highSeverityFindings,
        LatencyComparison latency,
        List<EvaluatorVersionComparison> evaluatorVersions) {

    /** Identifying metadata of one side of the comparison. */
    public record RunDescriptor(
            UUID runId,
            String name,
            String status,
            String runMode,
            UUID targetSystemId,
            UUID datasetId,
            int repetitionsPerTestCase,
            Instant startedAt,
            Instant completedAt,
            RunSummary.OutcomeCounts testCaseOutcomes) {
    }

    /** How one test case moved between the two runs, matched by external test-case ID. */
    public enum CaseChange {
        /** Present only in the candidate run. */
        ADDED,
        /** Present only in the baseline run. */
        REMOVED,
        /** FAILED in the baseline, PASSED in the candidate. */
        IMPROVED,
        /** PASSED in the baseline, FAILED in the candidate. */
        REGRESSED,
        /** Same conclusive outcome in both runs. */
        UNCHANGED,
        /** At least one side is INCONCLUSIVE or NOT_EVALUATED — no improvement/regression claim is honest. */
        INCONCLUSIVE
    }

    public record CaseComparison(
            String externalTestCaseId,
            Severity severity,
            TestCaseOutcome baselineOutcome,
            TestCaseOutcome candidateOutcome,
            CaseChange change) {
    }

    public record CaseChangeSummary(
            long added,
            long removed,
            long improved,
            long regressed,
            long unchanged,
            long inconclusive,
            List<CaseComparison> cases) {
    }

    /**
     * One evaluator's pass rate in both runs, with the pass/completed counts behind each rate.
     * Rates are {@code null} when a side has no completed evaluations of that metric — an absent
     * rate is absent, never 0. {@code delta} is only present when both rates exist.
     */
    public record MetricRateComparison(
            String evaluatorKey,
            long baselinePassedCount,
            long baselineCompletedCount,
            Double baselineRate,
            long candidatePassedCount,
            long candidateCompletedCount,
            Double candidateRate,
            Double delta) {
    }

    /** Persisted findings of severity HIGH or CRITICAL per run. */
    public record HighSeverityFindingComparison(long baselineCount, long candidateCount) {
    }

    /** Latency distributions side by side; either side is {@code null} when that run recorded no latencies. */
    public record LatencyComparison(RunSummary.LatencySummary baseline, RunSummary.LatencySummary candidate) {
    }

    /**
     * The distinct evaluator version/prompt/model combinations that actually produced results in
     * each run for one evaluator key. {@code matching} is false when the sets differ — including
     * when the evaluator ran in only one of the two runs.
     */
    public record EvaluatorVersionComparison(
            String evaluatorKey, List<String> baselineVersions, List<String> candidateVersions, boolean matching) {
    }
}
