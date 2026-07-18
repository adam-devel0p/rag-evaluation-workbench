package com.rageval.workbench.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.rageval.workbench.configuration.EvaluatorProperties;
import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.dataset.infrastructure.EvaluationTestCaseRepository;
import com.rageval.workbench.evaluation.EvaluationFixtures;
import com.rageval.workbench.evaluation.deterministic.RequiredFactsEvaluator;
import com.rageval.workbench.evaluation.domain.CitationPolicy;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.infrastructure.EvaluationMetricResultRepository;
import com.rageval.workbench.run.domain.EvaluationRun;
import com.rageval.workbench.run.domain.TestExecution;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.run.infrastructure.TestExecutionRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The evaluator-failure contract of spec §6.6/§12: a crashing evaluator produces a FAILED metric
 * result with an error message and no pass/fail verdict — never a failed chatbot answer — and the
 * other evaluators still run.
 */
class EvaluationServiceTest {

    private static final class CrashingStrategy implements EvaluationStrategy {
        @Override
        public String key() {
            return "crashing";
        }

        @Override
        public String version() {
            return "1.0.0";
        }

        @Override
        public EvaluationApplicability applicability(EvaluationContext context) {
            return EvaluationApplicability.applicable();
        }

        @Override
        public EvaluationResult evaluate(EvaluationContext context) {
            throw new IllegalStateException("deliberate evaluator crash");
        }
    }

    private final EvaluationMetricResultRepository metricResultRepository =
            mock(EvaluationMetricResultRepository.class);
    private final EvaluationRunRepository runRepository = mock(EvaluationRunRepository.class);
    private final TestExecutionRepository executionRepository = mock(TestExecutionRepository.class);
    private final EvaluationTestCaseRepository testCaseRepository = mock(EvaluationTestCaseRepository.class);

    private final List<EvaluationMetricResult> saved = new ArrayList<>();

    private EvaluationService service;
    private EvaluationRun run;
    private EvaluationTestCase testCase;
    private TestExecution execution;

    @BeforeEach
    void setUp() {
        service = new EvaluationService(
                List.of(new CrashingStrategy(), new RequiredFactsEvaluator()),
                new EvaluatorSettingsFactory(new EvaluatorProperties(
                        0.70,
                        List.of("transport", "required-facts", "forbidden-claims"),
                        2000,
                        CitationPolicy.OPTIONAL,
                        Map.of(),
                        List.of("i cannot"),
                        List.of("i don't know"),
                        160,
                        List.of("security"),
                        30000,
                        false)),
                new CrossEvaluatorReviewTriggers(),
                metricResultRepository,
                runRepository,
                executionRepository,
                testCaseRepository);
        run = EvaluationFixtures.run();
        testCase = EvaluationFixtures.answerableTestCase(
                List.of(EvaluationFixtures.fact("f", "Java 21", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of());
        execution = EvaluationFixtures.succeededExecution("Requires Java 21.", List.of(), List.of(), null);

        when(executionRepository.findById(execution.getId())).thenReturn(Optional.of(execution));
        when(runRepository.findById(any())).thenReturn(Optional.of(run));
        when(testCaseRepository.findById(any())).thenReturn(Optional.of(testCase));
        when(metricResultRepository.findByTestExecutionId(execution.getId())).thenReturn(List.of());
        when(metricResultRepository.saveAll(any())).thenAnswer(invocation -> {
            Iterable<EvaluationMetricResult> results = invocation.getArgument(0);
            results.forEach(saved::add);
            return results;
        });
    }

    @Test
    void aCrashingEvaluatorIsRecordedAsEvaluatorFailureAndOthersStillRun() {
        service.evaluateExecution(execution.getId());

        assertThat(saved).hasSize(2);
        EvaluationMetricResult crashed = saved.stream()
                .filter(result -> result.getEvaluatorKey().equals("crashing"))
                .findFirst()
                .orElseThrow();
        assertThat(crashed.getStatus()).isEqualTo(MetricResultStatus.FAILED);
        assertThat(crashed.getPassed()).isNull();
        assertThat(crashed.getErrorMessage()).contains("deliberate evaluator crash");
        assertThat(crashed.getExplanation()).contains("says nothing about the chatbot");
        // Spec §14: a missing automated verdict (evaluator failure) must reach a human reviewer.
        assertThat(crashed.isRequiresHumanReview()).isTrue();

        EvaluationMetricResult facts = saved.stream()
                .filter(result -> result.getEvaluatorKey().equals(RequiredFactsEvaluator.KEY))
                .findFirst()
                .orElseThrow();
        assertThat(facts.getStatus()).isEqualTo(MetricResultStatus.COMPLETED);
        assertThat(facts.getPassed()).isTrue();
    }

    @Test
    void alreadyEvaluatedExecutionIsLeftUntouched() {
        when(metricResultRepository.findByTestExecutionId(execution.getId()))
                .thenReturn(List.of(saved(new CrashingStrategy())));
        service.evaluateExecution(execution.getId());
        assertThat(saved).isEmpty();
    }

    @Test
    void failedMetricOnHighSeverityTestCaseIsFlaggedForHumanReview() {
        testCase = EvaluationFixtures.testCase(
                true,
                false,
                Severity.CRITICAL,
                List.of(EvaluationFixtures.fact("f", "absent fact", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of(),
                List.of());
        when(testCaseRepository.findById(any())).thenReturn(Optional.of(testCase));

        service.evaluateExecution(execution.getId());

        EvaluationMetricResult facts = saved.stream()
                .filter(result -> result.getEvaluatorKey().equals(RequiredFactsEvaluator.KEY))
                .findFirst()
                .orElseThrow();
        assertThat(facts.getPassed()).isFalse();
        assertThat(facts.isRequiresHumanReview()).isTrue();
    }

    private EvaluationMetricResult saved(EvaluationStrategy strategy) {
        return new EvaluationMetricResult(
                execution.getId(),
                strategy.key(),
                com.rageval.workbench.evaluation.domain.EvaluatorType.DETERMINISTIC,
                "1.0.0",
                null,
                null,
                MetricResultStatus.COMPLETED,
                true,
                1.0,
                1.0,
                "prior result",
                List.of(),
                false,
                1L,
                null);
    }
}
