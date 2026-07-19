package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.common.observability.WorkbenchMetrics;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.evaluation.domain.EvaluatorSettings;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs every enabled, applicable evaluator against one execution and persists one
 * {@link EvaluationMetricResult} per evaluator. The three verdicts the spec insists on never
 * conflating map to metric statuses: chatbot failed the metric → COMPLETED with {@code
 * passed=false}; evaluator itself failed → FAILED with an error message and no pass/fail;
 * evaluator not applicable → SKIPPED with the applicability reason as explanation.
 *
 * <p>Central human-review flagging (the spec §14 triggers computable in Phase 4) happens here, on
 * top of what the evaluator itself requested: confidence below the configured threshold, a failed
 * metric on a HIGH/CRITICAL test case, and a failed metric on a test case carrying a
 * security/permission-sensitive tag.
 */
@Service
public class EvaluationService {

    /** Statuses whose executions are evaluated; PENDING/RUNNING/CANCELLED executions never are. */
    public static final Set<ExecutionStatus> EVALUATABLE_STATUSES = Set.copyOf(EnumSet.of(
            ExecutionStatus.SUCCEEDED,
            ExecutionStatus.TIMED_OUT,
            ExecutionStatus.TARGET_ERROR,
            ExecutionStatus.INVALID_RESPONSE));

    private static final Logger log = LoggerFactory.getLogger(EvaluationService.class);

    private final List<EvaluationStrategy> strategies;
    private final EvaluatorSettingsFactory settingsFactory;
    private final CrossEvaluatorReviewTriggers crossEvaluatorReviewTriggers;
    private final EvaluationMetricResultRepository metricResultRepository;
    private final EvaluationRunRepository runRepository;
    private final TestExecutionRepository executionRepository;
    private final EvaluationTestCaseRepository testCaseRepository;
    private final WorkbenchMetrics metrics;

    public EvaluationService(
            List<EvaluationStrategy> strategies,
            EvaluatorSettingsFactory settingsFactory,
            CrossEvaluatorReviewTriggers crossEvaluatorReviewTriggers,
            EvaluationMetricResultRepository metricResultRepository,
            EvaluationRunRepository runRepository,
            TestExecutionRepository executionRepository,
            EvaluationTestCaseRepository testCaseRepository,
            WorkbenchMetrics metrics) {
        this.strategies = strategies.stream()
                .sorted(Comparator.comparing(EvaluationStrategy::key))
                .toList();
        this.settingsFactory = settingsFactory;
        this.crossEvaluatorReviewTriggers = crossEvaluatorReviewTriggers;
        this.metricResultRepository = metricResultRepository;
        this.runRepository = runRepository;
        this.executionRepository = executionRepository;
        this.testCaseRepository = testCaseRepository;
        this.metrics = metrics;
    }

    /** The keys of all registered evaluation strategies, for validating a run's enabled-evaluator list. */
    public Set<String> knownEvaluatorKeys() {
        return strategies.stream().map(EvaluationStrategy::key).collect(Collectors.toSet());
    }

    /**
     * Evaluates one execution, persisting one metric result per enabled evaluator. Idempotent: an
     * execution that already has metric results (from before an interruption or resume) is left
     * untouched. Executions that never produced a terminal target response are not evaluated.
     */
    @Transactional
    public void evaluateExecution(UUID executionId) {
        TestExecution execution = executionRepository
                .findById(executionId)
                .orElseThrow(() -> new NotFoundException("Test execution not found: " + executionId));
        if (!EVALUATABLE_STATUSES.contains(execution.getStatus())) {
            return;
        }
        if (!metricResultRepository.findByTestExecutionId(executionId).isEmpty()) {
            return;
        }
        EvaluationRun run = runRepository
                .findById(execution.getEvaluationRunId())
                .orElseThrow(() -> new NotFoundException("Evaluation run not found: " + execution.getEvaluationRunId()));
        EvaluationTestCase testCase = testCaseRepository
                .findById(execution.getTestCaseId())
                .orElseThrow(() -> new NotFoundException("Test case not found: " + execution.getTestCaseId()));

        EvaluatorSettings settings = settingsFactory.resolve(run);
        List<EvaluationMetricResult> allResults = new ArrayList<>();
        List<EvaluationMetricResult> completed = new ArrayList<>();
        for (EvaluationStrategy strategy : enabledStrategies(run)) {
            EvaluationMetricResult result =
                    runOne(strategy, new EvaluationContext(run, testCase, execution, settings, completed));
            allResults.add(result);
            if (result.getStatus() == MetricResultStatus.COMPLETED) {
                completed.add(result);
            }
        }
        // The spec §14 cross-evaluator disagreement triggers need every verdict for the execution,
        // so they run after the loop and before anything is persisted.
        crossEvaluatorReviewTriggers.apply(allResults);
        metricResultRepository.saveAll(allResults);
    }

    /**
     * Evaluates every terminal execution of the run that has no metric results yet — the safety
     * net for resumed runs whose earlier executions were invoked but never evaluated before an
     * interruption.
     */
    @Transactional
    public void evaluateMissingForRun(UUID runId) {
        for (TestExecution execution : executionRepository.findByEvaluationRunId(runId)) {
            if (EVALUATABLE_STATUSES.contains(execution.getStatus())) {
                evaluateExecution(execution.getId());
            }
        }
    }

    /** Ordered strategies enabled for this run; an empty enabled list means every registered one. */
    public List<EvaluationStrategy> enabledStrategies(EvaluationRun run) {
        if (run.getEnabledEvaluatorKeys().isEmpty()) {
            return strategies;
        }
        Map<String, EvaluationStrategy> byKey = new LinkedHashMap<>();
        strategies.forEach(strategy -> byKey.put(strategy.key(), strategy));
        List<EvaluationStrategy> enabled = new ArrayList<>();
        for (String key : run.getEnabledEvaluatorKeys()) {
            EvaluationStrategy strategy = byKey.get(key);
            if (strategy != null) {
                enabled.add(strategy);
            } else {
                log.warn("Run {}: enabled evaluator key \"{}\" is not registered; skipping it", run.getId(), key);
            }
        }
        return enabled;
    }

    private EvaluationMetricResult runOne(EvaluationStrategy strategy, EvaluationContext context) {
        MDC.put("evaluatorKey", strategy.key());
        try {
            EvaluationMetricResult result = runOneInContext(strategy, context);
            metrics.recordEvaluatorExecution(
                    strategy.key(),
                    result.getStatus().name(),
                    Duration.ofMillis(result.getDurationMilliseconds() != null
                            ? result.getDurationMilliseconds()
                            : 0L));
            return result;
        } finally {
            MDC.remove("evaluatorKey");
        }
    }

    private EvaluationMetricResult runOneInContext(EvaluationStrategy strategy, EvaluationContext context) {
        long start = System.nanoTime();
        try {
            EvaluationApplicability applicability = strategy.applicability(context);
            if (!applicability.isApplicable()) {
                return new EvaluationMetricResult(
                        context.execution().getId(),
                        strategy.key(),
                        strategy.evaluatorType(),
                        strategy.version(),
                        strategy.evaluatorModel(),
                        strategy.promptVersion(),
                        MetricResultStatus.SKIPPED,
                        null,
                        null,
                        null,
                        applicability.status() + ": " + applicability.reason(),
                        List.of(new EvaluationEvidence(
                                "applicability",
                                applicability.reason(),
                                null,
                                null,
                                null,
                                null,
                                Map.of("applicability", applicability.status().name()))),
                        false,
                        elapsedMillis(start),
                        null);
            }
            EvaluationResult result = strategy.evaluate(context);
            return new EvaluationMetricResult(
                    context.execution().getId(),
                    strategy.key(),
                    strategy.evaluatorType(),
                    strategy.version(),
                    strategy.evaluatorModel(),
                    strategy.promptVersion(),
                    MetricResultStatus.COMPLETED,
                    result.passed(),
                    result.score(),
                    result.confidence(),
                    result.explanation(),
                    result.evidence(),
                    result.requiresHumanReview() || centralReviewTriggers(context, result),
                    elapsedMillis(start),
                    null);
        } catch (RuntimeException ex) {
            // An evaluator crash is an evaluator failure, never a chatbot failure: status FAILED,
            // no pass/fail verdict, excluded from every pass-rate denominator by aggregation.
            log.error(
                    "Evaluator {} failed for execution {} — recording an evaluator error, not a chatbot failure",
                    strategy.key(),
                    context.execution().getId(),
                    ex);
            return new EvaluationMetricResult(
                    context.execution().getId(),
                    strategy.key(),
                    strategy.evaluatorType(),
                    strategy.version(),
                    strategy.evaluatorModel(),
                    strategy.promptVersion(),
                    MetricResultStatus.FAILED,
                    null,
                    null,
                    null,
                    "The evaluator itself failed; this says nothing about the chatbot's answer",
                    List.of(),
                    // Spec §14: malformed/incomplete evaluator output is a human-review trigger —
                    // a missing automated verdict must be visible to a reviewer, not just absent.
                    true,
                    elapsedMillis(start),
                    ex.toString());
        }
    }

    /** Spec §14 review triggers computable from deterministic Phase 4 data. */
    private boolean centralReviewTriggers(EvaluationContext context, EvaluationResult result) {
        EvaluatorSettings settings = context.settings();
        if (result.confidence() != null && result.confidence() < settings.humanReviewConfidenceThreshold()) {
            return true;
        }
        if (!result.passed()) {
            switch (context.testCase().getSeverity()) {
                case HIGH, CRITICAL -> {
                    return true;
                }
                default -> {
                    // fall through to the tag check
                }
            }
            List<String> tags = context.testCase().getTags();
            if (tags != null && tags.stream().anyMatch(settings.humanReviewTags()::contains)) {
                return true;
            }
        }
        return false;
    }

    private long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
