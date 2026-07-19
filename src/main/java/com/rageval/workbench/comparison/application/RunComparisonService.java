package com.rageval.workbench.comparison.application;

import com.rageval.workbench.classification.infrastructure.EvaluationFindingRepository;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.comparison.application.RunComparison.CaseChange;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.application.RunSummary;
import com.rageval.workbench.evaluation.application.RunSummaryService;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.TestCaseOutcome;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the spec §19 baseline-vs-candidate comparison from persisted data. Test cases are matched
 * by external test-case ID; per-run statistics are reused from {@link RunSummaryService} so the
 * comparison can never drift from the single-run aggregation rules (evaluator errors outside every
 * denominator, zero-denominator rates as {@code null}). The comparison logic itself is static so
 * the change-classification and warning rules are unit testable without a database.
 */
@Service
public class RunComparisonService {

    private final EvaluationRunRepository runRepository;
    private final RunSummaryService summaryService;
    private final EvaluationMetricResultRepository metricResultRepository;
    private final EvaluationFindingRepository findingRepository;

    public RunComparisonService(
            EvaluationRunRepository runRepository,
            RunSummaryService summaryService,
            EvaluationMetricResultRepository metricResultRepository,
            EvaluationFindingRepository findingRepository) {
        this.runRepository = runRepository;
        this.summaryService = summaryService;
        this.metricResultRepository = metricResultRepository;
        this.findingRepository = findingRepository;
    }

    @Transactional(readOnly = true)
    public RunComparison compare(UUID baselineRunId, UUID candidateRunId, boolean allowDatasetMismatch) {
        if (baselineRunId.equals(candidateRunId)) {
            throw new ValidationException(
                    "baselineRunId and candidateRunId must differ",
                    new FieldViolation("candidateRunId", "must not equal baselineRunId"));
        }
        EvaluationRun baseline = loadRun(baselineRunId, "baselineRunId");
        EvaluationRun candidate = loadRun(candidateRunId, "candidateRunId");
        boolean datasetsDiffer = !baseline.getDatasetId().equals(candidate.getDatasetId());
        if (datasetsDiffer && !allowDatasetMismatch) {
            throw new ValidationException(
                    "The two runs use different datasets (" + baseline.getDatasetId() + " vs "
                            + candidate.getDatasetId() + "). If the datasets are compatible versions of the same "
                            + "test set, pass allowDatasetMismatch=true to compare by external test-case ID anyway.",
                    new FieldViolation(
                            "candidateRunId",
                            "run uses dataset " + candidate.getDatasetId() + " but the baseline run uses "
                                    + baseline.getDatasetId()));
        }

        RunSummary baselineSummary = summaryService.summarize(baselineRunId);
        RunSummary candidateSummary = summaryService.summarize(candidateRunId);
        List<EvaluationMetricResult> baselineResults = metricResultRepository.findByRunId(baselineRunId);
        List<EvaluationMetricResult> candidateResults = metricResultRepository.findByRunId(candidateRunId);

        List<RunComparison.EvaluatorVersionComparison> versionComparisons =
                compareEvaluatorVersions(baselineResults, candidateResults);

        List<String> warnings = new ArrayList<>();
        if (datasetsDiffer) {
            warnings.add("The runs use different datasets (" + baseline.getDatasetId() + " vs "
                    + candidate.getDatasetId() + "); test cases were matched by external test-case ID only, "
                    + "without any check that the underlying questions are actually the same.");
        }
        warnings.addAll(runStateWarnings("baseline", baseline));
        warnings.addAll(runStateWarnings("candidate", candidate));
        if (baseline.getRepetitionsPerTestCase() != candidate.getRepetitionsPerTestCase()) {
            warnings.add("The runs use different repetition counts (" + baseline.getRepetitionsPerTestCase()
                    + " vs " + candidate.getRepetitionsPerTestCase()
                    + "); consistency-sensitive results are not directly comparable.");
        }
        if (!baseline.getTargetSystemId().equals(candidate.getTargetSystemId())) {
            warnings.add("The runs evaluate different target systems (" + baseline.getTargetSystemId() + " vs "
                    + candidate.getTargetSystemId()
                    + "); this is a cross-system comparison, not a before/after comparison of one system.");
        }
        warnings.addAll(evaluatorVersionWarnings(versionComparisons));

        return new RunComparison(
                describe(baseline, baselineSummary),
                describe(candidate, candidateSummary),
                List.copyOf(warnings),
                compareCases(baselineSummary.testCases(), candidateSummary.testCases()),
                compareMetricRates(baselineSummary.metrics(), candidateSummary.metrics()),
                new RunComparison.HighSeverityFindingComparison(
                        countHighSeverityFindings(baselineRunId), countHighSeverityFindings(candidateRunId)),
                new RunComparison.LatencyComparison(baselineSummary.latency(), candidateSummary.latency()),
                versionComparisons);
    }

    /** IMPROVED/REGRESSED only between two conclusive outcomes; anything else is honest INCONCLUSIVE. */
    static CaseChange classifyChange(TestCaseOutcome baselineOutcome, TestCaseOutcome candidateOutcome) {
        boolean baselineConclusive =
                baselineOutcome == TestCaseOutcome.PASSED || baselineOutcome == TestCaseOutcome.FAILED;
        boolean candidateConclusive =
                candidateOutcome == TestCaseOutcome.PASSED || candidateOutcome == TestCaseOutcome.FAILED;
        if (!baselineConclusive || !candidateConclusive) {
            return CaseChange.INCONCLUSIVE;
        }
        if (baselineOutcome == candidateOutcome) {
            return CaseChange.UNCHANGED;
        }
        return candidateOutcome == TestCaseOutcome.PASSED ? CaseChange.IMPROVED : CaseChange.REGRESSED;
    }

    static RunComparison.CaseChangeSummary compareCases(
            List<RunSummary.TestCaseSummary> baselineCases, List<RunSummary.TestCaseSummary> candidateCases) {
        Map<String, RunSummary.TestCaseSummary> baselineByExternalId = byExternalId(baselineCases);
        Map<String, RunSummary.TestCaseSummary> candidateByExternalId = byExternalId(candidateCases);
        TreeSet<String> allExternalIds = new TreeSet<>(baselineByExternalId.keySet());
        allExternalIds.addAll(candidateByExternalId.keySet());

        List<RunComparison.CaseComparison> cases = new ArrayList<>();
        for (String externalId : allExternalIds) {
            RunSummary.TestCaseSummary inBaseline = baselineByExternalId.get(externalId);
            RunSummary.TestCaseSummary inCandidate = candidateByExternalId.get(externalId);
            CaseChange change;
            if (inBaseline == null) {
                change = CaseChange.ADDED;
            } else if (inCandidate == null) {
                change = CaseChange.REMOVED;
            } else {
                change = classifyChange(inBaseline.outcome(), inCandidate.outcome());
            }
            Severity severity = inCandidate != null ? inCandidate.severity() : inBaseline.severity();
            cases.add(new RunComparison.CaseComparison(
                    externalId,
                    severity,
                    inBaseline != null ? inBaseline.outcome() : null,
                    inCandidate != null ? inCandidate.outcome() : null,
                    change));
        }
        return new RunComparison.CaseChangeSummary(
                countChange(cases, CaseChange.ADDED),
                countChange(cases, CaseChange.REMOVED),
                countChange(cases, CaseChange.IMPROVED),
                countChange(cases, CaseChange.REGRESSED),
                countChange(cases, CaseChange.UNCHANGED),
                countChange(cases, CaseChange.INCONCLUSIVE),
                List.copyOf(cases));
    }

    static List<RunComparison.MetricRateComparison> compareMetricRates(
            List<RunSummary.MetricSummary> baselineMetrics, List<RunSummary.MetricSummary> candidateMetrics) {
        Map<String, RunSummary.MetricSummary> baselineByKey = byEvaluatorKey(baselineMetrics);
        Map<String, RunSummary.MetricSummary> candidateByKey = byEvaluatorKey(candidateMetrics);
        TreeSet<String> allKeys = new TreeSet<>(baselineByKey.keySet());
        allKeys.addAll(candidateByKey.keySet());

        List<RunComparison.MetricRateComparison> comparisons = new ArrayList<>();
        for (String key : allKeys) {
            RunSummary.MetricSummary inBaseline = baselineByKey.get(key);
            RunSummary.MetricSummary inCandidate = candidateByKey.get(key);
            Double baselineRate = inBaseline != null ? inBaseline.passRate() : null;
            Double candidateRate = inCandidate != null ? inCandidate.passRate() : null;
            comparisons.add(new RunComparison.MetricRateComparison(
                    key,
                    inBaseline != null ? inBaseline.passedCount() : 0,
                    inBaseline != null ? inBaseline.completedCount() : 0,
                    baselineRate,
                    inCandidate != null ? inCandidate.passedCount() : 0,
                    inCandidate != null ? inCandidate.completedCount() : 0,
                    candidateRate,
                    baselineRate != null && candidateRate != null ? candidateRate - baselineRate : null));
        }
        return List.copyOf(comparisons);
    }

    /** Distinct version/prompt/model combinations that actually judged results, per evaluator key and run. */
    static List<RunComparison.EvaluatorVersionComparison> compareEvaluatorVersions(
            List<EvaluationMetricResult> baselineResults, List<EvaluationMetricResult> candidateResults) {
        Map<String, TreeSet<String>> baselineVersions = versionsByEvaluatorKey(baselineResults);
        Map<String, TreeSet<String>> candidateVersions = versionsByEvaluatorKey(candidateResults);
        TreeSet<String> allKeys = new TreeSet<>(baselineVersions.keySet());
        allKeys.addAll(candidateVersions.keySet());

        List<RunComparison.EvaluatorVersionComparison> comparisons = new ArrayList<>();
        for (String key : allKeys) {
            TreeSet<String> inBaseline = baselineVersions.getOrDefault(key, new TreeSet<>());
            TreeSet<String> inCandidate = candidateVersions.getOrDefault(key, new TreeSet<>());
            comparisons.add(new RunComparison.EvaluatorVersionComparison(
                    key, List.copyOf(inBaseline), List.copyOf(inCandidate), inBaseline.equals(inCandidate)));
        }
        return List.copyOf(comparisons);
    }

    /**
     * Spec §19's hard rule: a comparison across differing evaluator versions must never be
     * presented as scientifically clean. Every mismatch produces an explicit warning naming the
     * evaluator and both version sets.
     */
    static List<String> evaluatorVersionWarnings(List<RunComparison.EvaluatorVersionComparison> comparisons) {
        List<String> warnings = new ArrayList<>();
        for (RunComparison.EvaluatorVersionComparison comparison : comparisons) {
            if (comparison.matching()) {
                continue;
            }
            if (comparison.baselineVersions().isEmpty() || comparison.candidateVersions().isEmpty()) {
                String onlyIn = comparison.baselineVersions().isEmpty() ? "candidate" : "baseline";
                warnings.add("Evaluator '" + comparison.evaluatorKey() + "' produced results only in the " + onlyIn
                        + " run; its metric rates are not comparable between the two runs.");
            } else {
                warnings.add("Evaluator versions differ for '" + comparison.evaluatorKey() + "': baseline "
                        + comparison.baselineVersions() + " vs candidate " + comparison.candidateVersions()
                        + ". Metric changes for this evaluator may reflect evaluator changes rather than target "
                        + "changes — this comparison is not scientifically clean for that metric.");
            }
        }
        return warnings;
    }

    private List<String> runStateWarnings(String side, EvaluationRun run) {
        return switch (run.getStatus()) {
            case COMPLETED -> List.of();
            case PARTIALLY_COMPLETED -> List.of("The " + side + " run is PARTIALLY_COMPLETED: at least one "
                    + "execution failed at the target; per-case comparisons still cover every captured execution.");
            case CREATED, QUEUED, RUNNING -> List.of("The " + side + " run is " + run.getStatus()
                    + " — it has not finished executing, so this comparison is over incomplete data.");
            case FAILED, CANCELLED, INTERRUPTED -> List.of("The " + side + " run is " + run.getStatus()
                    + "; its results are incomplete and the comparison may be misleading.");
        };
    }

    private long countHighSeverityFindings(UUID runId) {
        return findingRepository.findByEvaluationRunId(runId).stream()
                .filter(finding ->
                        finding.getSeverity() == Severity.HIGH || finding.getSeverity() == Severity.CRITICAL)
                .count();
    }

    private RunComparison.RunDescriptor describe(EvaluationRun run, RunSummary summary) {
        return new RunComparison.RunDescriptor(
                run.getId(),
                run.getName(),
                run.getStatus().name(),
                run.getRunMode().name(),
                run.getTargetSystemId(),
                run.getDatasetId(),
                run.getRepetitionsPerTestCase(),
                run.getStartedAt(),
                run.getCompletedAt(),
                summary.testCaseOutcomes());
    }

    private EvaluationRun loadRun(UUID runId, String parameterName) {
        return runRepository
                .findById(runId)
                .orElseThrow(() -> new NotFoundException(
                        "Evaluation run not found for " + parameterName + ": " + runId));
    }

    private static Map<String, RunSummary.TestCaseSummary> byExternalId(List<RunSummary.TestCaseSummary> cases) {
        return cases.stream()
                .collect(Collectors.toMap(
                        RunSummary.TestCaseSummary::externalId,
                        Function.identity(),
                        (first, second) -> first,
                        LinkedHashMap::new));
    }

    private static Map<String, RunSummary.MetricSummary> byEvaluatorKey(List<RunSummary.MetricSummary> metrics) {
        return metrics.stream()
                .collect(Collectors.toMap(RunSummary.MetricSummary::evaluatorKey, Function.identity()));
    }

    private static Map<String, TreeSet<String>> versionsByEvaluatorKey(List<EvaluationMetricResult> results) {
        Map<String, TreeSet<String>> versions = new TreeMap<>();
        for (EvaluationMetricResult result : results) {
            versions.computeIfAbsent(result.getEvaluatorKey(), ignored -> new TreeSet<>())
                    .add(describeVersion(result));
        }
        return versions;
    }

    private static String describeVersion(EvaluationMetricResult result) {
        StringBuilder description = new StringBuilder("version ").append(result.getEvaluatorVersion());
        if (result.getPromptVersion() != null) {
            description.append(", prompt ").append(result.getPromptVersion());
        }
        if (result.getEvaluatorModel() != null) {
            description.append(", model ").append(result.getEvaluatorModel());
        }
        return description.toString();
    }

    private static long countChange(List<RunComparison.CaseComparison> cases, CaseChange change) {
        return cases.stream().filter(comparison -> comparison.change() == change).count();
    }
}
