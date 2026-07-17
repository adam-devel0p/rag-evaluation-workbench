package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import com.rageval.workbench.run.domain.RunMode;
import com.rageval.workbench.run.domain.RunStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The spec §18 run-level statistics, computed on demand from persisted executions and metric
 * results — nothing here hides missing data: every rate is reported next to the counts that formed
 * it, evaluator errors are counted separately and never appear in a pass-rate denominator, and a
 * rate whose denominator is zero is {@code null}, not a fake 0 or 100. Rates that require the
 * Phase 5 LLM evaluators (groundedness, reference correctness, completeness) are absent until
 * those evaluators exist — they are not guessed at from deterministic data.
 */
public record RunSummary(
        UUID runId,
        String runName,
        RunStatus runStatus,
        RunMode runMode,
        int repetitionsPerTestCase,
        ExecutionCounts executions,
        List<MetricSummary> metrics,
        OutcomeCounts testCaseOutcomes,
        Map<String, RateSummary> passRateByTag,
        Map<String, RateSummary> passRateBySeverity,
        long highOrCriticalFailureCount,
        RetrievalSummary retrieval,
        LatencySummary latency,
        Double correctRefusalRate,
        Double targetErrorRate,
        long humanReviewBacklog,
        ConsistencySummary consistency,
        List<TestCaseSummary> testCases) {

    /** Execution counts by terminal state; {@code failed} covers TIMED_OUT/TARGET_ERROR/INVALID_RESPONSE. */
    public record ExecutionCounts(long total, long succeeded, long failed, long cancelled, long incomplete) {
    }

    /**
     * Per-evaluator bookkeeping (spec §18): {@code passRate} is passed over <em>completed</em>
     * evaluations only — evaluator errors are counted in {@code evaluatorErrorCount} and are never
     * part of the denominator.
     */
    public record MetricSummary(
            String evaluatorKey,
            long applicableExecutionCount,
            long completedCount,
            long passedCount,
            long failedCount,
            long evaluatorErrorCount,
            long notApplicableCount,
            long unreviewedCount,
            long humanConfirmedCount,
            Double passRate) {
    }

    /** Test-case outcome distribution; pass rate is passed/(passed+failed), inconclusive excluded. */
    public record OutcomeCounts(
            long total, long passed, long failed, long inconclusive, long notEvaluated, Double passRate) {
    }

    public record RateSummary(long passed, long failed, Double passRate) {
    }

    /** Aggregated over completed expected-document evaluations only. */
    public record RetrievalSummary(
            long evaluatedCount, Double hitRateAt1, Double hitRateAt3, Double hitRateAt5, Double meanReciprocalRank) {
    }

    /** Over executions that recorded a latency; percentiles use linear interpolation (see {@link Percentiles}). */
    public record LatencySummary(long sampleCount, double median, double p90, double p95, long maximum) {
    }

    /** Deterministic fact-level repetition consistency (spec §17); test cases with fewer than two successful repetitions are not comparable. */
    public record ConsistencySummary(long comparableTestCases, long consistentTestCases, Double consistencyRate) {
    }

    public record TestCaseSummary(
            UUID testCaseId,
            String externalId,
            Severity severity,
            List<String> tags,
            TestCaseOutcome outcome,
            List<TestCaseOutcome> executionOutcomes) {
    }
}
