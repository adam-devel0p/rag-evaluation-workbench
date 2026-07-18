package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.deterministic.ExpectedDocumentEvaluator;
import com.rageval.workbench.evaluation.deterministic.RefusalEvaluator;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorSettings;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the spec §18 aggregation for one run, on demand, from persisted data only. The core
 * counting logic lives in static methods so the denominator rules — evaluator errors never in a
 * chatbot pass-rate denominator, zero-denominator rates reported as {@code null} — are unit
 * testable without a database.
 */
@Service
public class RunSummaryService {

    private final EvaluationRunRepository runRepository;
    private final TestExecutionRepository executionRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final EvaluationMetricResultRepository metricResultRepository;
    private final HumanReviewLookup humanReviewLookup;
    private final EvaluatorSettingsFactory settingsFactory;
    private final RepetitionConsistencyAnalyzer consistencyAnalyzer;

    public RunSummaryService(
            EvaluationRunRepository runRepository,
            TestExecutionRepository executionRepository,
            EvaluationTestCaseRepository testCaseRepository,
            EvaluationMetricResultRepository metricResultRepository,
            HumanReviewLookup humanReviewLookup,
            EvaluatorSettingsFactory settingsFactory,
            RepetitionConsistencyAnalyzer consistencyAnalyzer) {
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.testCaseRepository = testCaseRepository;
        this.metricResultRepository = metricResultRepository;
        this.humanReviewLookup = humanReviewLookup;
        this.settingsFactory = settingsFactory;
        this.consistencyAnalyzer = consistencyAnalyzer;
    }

    @Transactional(readOnly = true)
    public RunSummary summarize(UUID runId) {
        EvaluationRun run = runRepository
                .findById(runId)
                .orElseThrow(() -> new NotFoundException("Evaluation run not found: " + runId));
        List<TestExecution> executions = executionRepository.findByEvaluationRunId(runId);
        List<EvaluationMetricResult> allResults = metricResultRepository.findByRunId(runId);
        Map<UUID, EvaluationTestCase> testCases = testCaseRepository.findByDatasetId(run.getDatasetId()).stream()
                .collect(Collectors.toMap(EvaluationTestCase::getId, testCase -> testCase));
        EvaluatorSettings settings = settingsFactory.resolve(run);

        Map<UUID, List<EvaluationMetricResult>> resultsByExecution =
                allResults.stream().collect(Collectors.groupingBy(EvaluationMetricResult::getTestExecutionId));
        Set<UUID> reviewedMetricResultIds = allResults.isEmpty()
                ? Set.of()
                : humanReviewLookup.reviewedMetricResultIds(
                        allResults.stream().map(EvaluationMetricResult::getId).toList());

        List<RunSummary.TestCaseSummary> testCaseSummaries =
                testCaseSummaries(executions, resultsByExecution, testCases, settings.mandatoryEvaluatorKeys());

        return new RunSummary(
                run.getId(),
                run.getName(),
                run.getStatus(),
                run.getRunMode(),
                run.getRepetitionsPerTestCase(),
                executionCounts(executions),
                metricSummaries(allResults, reviewedMetricResultIds),
                outcomeCounts(testCaseSummaries),
                rateByKey(testCaseSummaries, summary -> summary.tags() != null ? summary.tags() : List.of()),
                rateByKey(testCaseSummaries, summary -> List.of(summary.severity().name())),
                testCaseSummaries.stream()
                        .filter(summary -> summary.outcome() == TestCaseOutcome.FAILED
                                && (summary.severity() == Severity.HIGH || summary.severity() == Severity.CRITICAL))
                        .count(),
                retrievalSummary(allResults),
                latencySummary(executions),
                metricPassRate(allResults, RefusalEvaluator.KEY),
                targetErrorRate(executions),
                allResults.stream()
                        .filter(EvaluationMetricResult::isRequiresHumanReview)
                        .filter(result -> !reviewedMetricResultIds.contains(result.getId()))
                        .count(),
                consistencySummary(executions, resultsByExecution),
                testCaseSummaries);
    }

    static RunSummary.ExecutionCounts executionCounts(List<TestExecution> executions) {
        long succeeded = countByStatus(executions, ExecutionStatus.SUCCEEDED);
        long failed = executions.stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.TIMED_OUT
                        || execution.getStatus() == ExecutionStatus.TARGET_ERROR
                        || execution.getStatus() == ExecutionStatus.INVALID_RESPONSE)
                .count();
        long cancelled = countByStatus(executions, ExecutionStatus.CANCELLED);
        return new RunSummary.ExecutionCounts(
                executions.size(), succeeded, failed, cancelled, executions.size() - succeeded - failed - cancelled);
    }

    /** Spec §18 per-metric counts; the pass-rate denominator is completed evaluations only. */
    static List<RunSummary.MetricSummary> metricSummaries(
            List<EvaluationMetricResult> results, Set<UUID> reviewedMetricResultIds) {
        Map<String, List<EvaluationMetricResult>> byKey = new TreeMap<>(
                results.stream().collect(Collectors.groupingBy(EvaluationMetricResult::getEvaluatorKey)));
        List<RunSummary.MetricSummary> summaries = new ArrayList<>();
        byKey.forEach((key, metricResults) -> {
            long completed = metricResults.stream()
                    .filter(result -> result.getStatus() == MetricResultStatus.COMPLETED)
                    .count();
            long passed = metricResults.stream()
                    .filter(result -> result.getStatus() == MetricResultStatus.COMPLETED
                            && Boolean.TRUE.equals(result.getPassed()))
                    .count();
            long failed = metricResults.stream()
                    .filter(result -> result.getStatus() == MetricResultStatus.COMPLETED
                            && Boolean.FALSE.equals(result.getPassed()))
                    .count();
            long errors = metricResults.stream()
                    .filter(result -> result.getStatus() == MetricResultStatus.FAILED)
                    .count();
            long notApplicable = metricResults.stream()
                    .filter(result -> result.getStatus() == MetricResultStatus.SKIPPED)
                    .count();
            long unreviewed = metricResults.stream()
                    .filter(EvaluationMetricResult::isRequiresHumanReview)
                    .filter(result -> !reviewedMetricResultIds.contains(result.getId()))
                    .count();
            long humanConfirmed = metricResults.stream()
                    .filter(result -> reviewedMetricResultIds.contains(result.getId()))
                    .count();
            summaries.add(new RunSummary.MetricSummary(
                    key,
                    completed + errors,
                    completed,
                    passed,
                    failed,
                    errors,
                    notApplicable,
                    unreviewed,
                    humanConfirmed,
                    rate(passed, completed)));
        });
        return summaries;
    }

    static RunSummary.OutcomeCounts outcomeCounts(List<RunSummary.TestCaseSummary> summaries) {
        long passed = countOutcome(summaries, TestCaseOutcome.PASSED);
        long failed = countOutcome(summaries, TestCaseOutcome.FAILED);
        long inconclusive = countOutcome(summaries, TestCaseOutcome.INCONCLUSIVE);
        long notEvaluated = countOutcome(summaries, TestCaseOutcome.NOT_EVALUATED);
        return new RunSummary.OutcomeCounts(
                summaries.size(), passed, failed, inconclusive, notEvaluated, rate(passed, passed + failed));
    }

    private List<RunSummary.TestCaseSummary> testCaseSummaries(
            List<TestExecution> executions,
            Map<UUID, List<EvaluationMetricResult>> resultsByExecution,
            Map<UUID, EvaluationTestCase> testCases,
            List<String> mandatoryEvaluatorKeys) {
        Map<UUID, List<TestExecution>> byTestCase = executions.stream()
                .collect(Collectors.groupingBy(TestExecution::getTestCaseId, LinkedHashMap::new, Collectors.toList()));
        List<RunSummary.TestCaseSummary> summaries = new ArrayList<>();
        byTestCase.forEach((testCaseId, testCaseExecutions) -> {
            EvaluationTestCase testCase = testCases.get(testCaseId);
            List<TestCaseOutcome> executionOutcomes = testCaseExecutions.stream()
                    .sorted(Comparator.comparingInt(TestExecution::getRepetitionNumber))
                    .map(execution -> TestCaseOutcomeCalculator.executionOutcome(
                            mandatoryEvaluatorKeys, resultsByExecution.getOrDefault(execution.getId(), List.of())))
                    .toList();
            summaries.add(new RunSummary.TestCaseSummary(
                    testCaseId,
                    testCase != null ? testCase.getExternalId() : testCaseId.toString(),
                    testCase != null ? testCase.getSeverity() : Severity.MEDIUM,
                    testCase != null ? testCase.getTags() : List.of(),
                    TestCaseOutcomeCalculator.testCaseOutcome(executionOutcomes),
                    executionOutcomes));
        });
        summaries.sort(Comparator.comparing(RunSummary.TestCaseSummary::externalId));
        return summaries;
    }

    private static Map<String, RunSummary.RateSummary> rateByKey(
            List<RunSummary.TestCaseSummary> summaries,
            Function<RunSummary.TestCaseSummary, List<String>> keyExtractor) {
        Map<String, long[]> counts = new TreeMap<>();
        for (RunSummary.TestCaseSummary summary : summaries) {
            if (summary.outcome() != TestCaseOutcome.PASSED && summary.outcome() != TestCaseOutcome.FAILED) {
                continue;
            }
            for (String key : new HashSet<>(keyExtractor.apply(summary))) {
                long[] passFail = counts.computeIfAbsent(key, ignored -> new long[2]);
                if (summary.outcome() == TestCaseOutcome.PASSED) {
                    passFail[0]++;
                } else {
                    passFail[1]++;
                }
            }
        }
        Map<String, RunSummary.RateSummary> rates = new TreeMap<>();
        counts.forEach((key, passFail) -> rates.put(
                key, new RunSummary.RateSummary(passFail[0], passFail[1], rate(passFail[0], passFail[0] + passFail[1]))));
        return rates;
    }

    /** Hit@K / MRR aggregated from the retrieval-metrics evidence of completed expected-document results. */
    static RunSummary.RetrievalSummary retrievalSummary(List<EvaluationMetricResult> results) {
        long evaluated = 0;
        long hit1 = 0;
        long hit3 = 0;
        long hit5 = 0;
        double reciprocalRankSum = 0.0;
        for (EvaluationMetricResult result : results) {
            if (!ExpectedDocumentEvaluator.KEY.equals(result.getEvaluatorKey())
                    || result.getStatus() != MetricResultStatus.COMPLETED) {
                continue;
            }
            Map<String, Object> metrics = retrievalMetrics(result);
            if (metrics == null) {
                continue;
            }
            evaluated++;
            hit1 += Boolean.TRUE.equals(metrics.get(ExpectedDocumentEvaluator.HIT_AT_1_KEY)) ? 1 : 0;
            hit3 += Boolean.TRUE.equals(metrics.get(ExpectedDocumentEvaluator.HIT_AT_3_KEY)) ? 1 : 0;
            hit5 += Boolean.TRUE.equals(metrics.get(ExpectedDocumentEvaluator.HIT_AT_5_KEY)) ? 1 : 0;
            Object reciprocalRank = metrics.get(ExpectedDocumentEvaluator.RECIPROCAL_RANK_KEY);
            if (reciprocalRank instanceof Number number) {
                reciprocalRankSum += number.doubleValue();
            }
        }
        if (evaluated == 0) {
            return new RunSummary.RetrievalSummary(0, null, null, null, null);
        }
        return new RunSummary.RetrievalSummary(
                evaluated,
                rate(hit1, evaluated),
                rate(hit3, evaluated),
                rate(hit5, evaluated),
                reciprocalRankSum / evaluated);
    }

    private static Map<String, Object> retrievalMetrics(EvaluationMetricResult result) {
        for (EvaluationEvidence evidence : result.getEvidence()) {
            if ("retrieval-metrics".equals(evidence.type())) {
                return evidence.metadata();
            }
        }
        return null;
    }

    static RunSummary.LatencySummary latencySummary(List<TestExecution> executions) {
        List<Long> latencies = executions.stream()
                .map(TestExecution::getLatencyMilliseconds)
                .filter(Objects::nonNull)
                .toList();
        if (latencies.isEmpty()) {
            return null;
        }
        return new RunSummary.LatencySummary(
                latencies.size(),
                Percentiles.median(latencies),
                Percentiles.percentile(latencies, 90),
                Percentiles.percentile(latencies, 95),
                Percentiles.maximum(latencies));
    }

    static Double metricPassRate(List<EvaluationMetricResult> results, String evaluatorKey) {
        long completed = results.stream()
                .filter(result -> evaluatorKey.equals(result.getEvaluatorKey())
                        && result.getStatus() == MetricResultStatus.COMPLETED)
                .count();
        long passed = results.stream()
                .filter(result -> evaluatorKey.equals(result.getEvaluatorKey())
                        && result.getStatus() == MetricResultStatus.COMPLETED
                        && Boolean.TRUE.equals(result.getPassed()))
                .count();
        return rate(passed, completed);
    }

    static Double targetErrorRate(List<TestExecution> executions) {
        long terminal = executions.stream()
                .filter(execution -> execution.getStatus() != ExecutionStatus.PENDING
                        && execution.getStatus() != ExecutionStatus.RUNNING
                        && execution.getStatus() != ExecutionStatus.CANCELLED)
                .count();
        long failed = executions.stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.TIMED_OUT
                        || execution.getStatus() == ExecutionStatus.TARGET_ERROR
                        || execution.getStatus() == ExecutionStatus.INVALID_RESPONSE)
                .count();
        return rate(failed, terminal);
    }

    private RunSummary.ConsistencySummary consistencySummary(
            List<TestExecution> executions, Map<UUID, List<EvaluationMetricResult>> resultsByExecution) {
        Map<UUID, List<TestExecution>> byTestCase =
                executions.stream().collect(Collectors.groupingBy(TestExecution::getTestCaseId));
        long comparable = 0;
        long consistent = 0;
        for (List<TestExecution> testCaseExecutions : byTestCase.values()) {
            RepetitionConsistencyAnalyzer.ConsistencyComparison comparison =
                    consistencyAnalyzer.compare(testCaseExecutions, resultsByExecution);
            if (comparison.comparable()) {
                comparable++;
                if (comparison.consistent()) {
                    consistent++;
                }
            }
        }
        return new RunSummary.ConsistencySummary(comparable, consistent, rate(consistent, comparable));
    }

    private static long countByStatus(List<TestExecution> executions, ExecutionStatus status) {
        return executions.stream().filter(execution -> execution.getStatus() == status).count();
    }

    private static long countOutcome(List<RunSummary.TestCaseSummary> summaries, TestCaseOutcome outcome) {
        return summaries.stream().filter(summary -> summary.outcome() == outcome).count();
    }

    /** {@code null} when the denominator is zero — an absent rate is reported as absent, never as 0 or 1. */
    static Double rate(long numerator, long denominator) {
        return denominator > 0 ? (double) numerator / denominator : null;
    }
}
