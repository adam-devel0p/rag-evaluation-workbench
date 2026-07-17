package com.rageval.workbench.evaluation.application;

import static com.rageval.workbench.evaluation.EvaluationFixtures.answerableTestCase;
import static com.rageval.workbench.evaluation.EvaluationFixtures.fact;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.evaluation.EvaluationFixtures;
import com.rageval.workbench.evaluation.deterministic.RequiredFactsEvaluator;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.run.domain.TestExecution;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Spec §17: fact-level comparison across repetitions — different fact-match outcomes are material
 * inconsistency, mere wording variation is not.
 */
class RepetitionConsistencyAnalyzerTest {

    private final RepetitionConsistencyAnalyzer analyzer = new RepetitionConsistencyAnalyzer();
    private final RequiredFactsEvaluator requiredFacts = new RequiredFactsEvaluator();

    private final EvaluationTestCase testCase = answerableTestCase(
            List.of(fact("retention", "30 days", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
            List.of(),
            List.of());

    private TestExecution repetition(int number, String answer) {
        TestExecution execution = new TestExecution(UUID.randomUUID(), testCase.getId(), null, number);
        execution.markRunning();
        execution.recordSuccess(
                null, answer, null, List.of(), List.of(), "m", 10L, 1, 1, 200, 1, Instant.now(), Instant.now());
        return execution;
    }

    private EvaluationMetricResult evaluate(TestExecution execution) {
        EvaluationContext context = new EvaluationContext(
                EvaluationFixtures.run(), testCase, execution, EvaluationFixtures.defaultSettings(), List.of());
        EvaluationResult result = requiredFacts.evaluate(context);
        return new EvaluationMetricResult(
                execution.getId(),
                requiredFacts.key(),
                EvaluatorType.DETERMINISTIC,
                "1.0.0",
                null,
                null,
                MetricResultStatus.COMPLETED,
                result.passed(),
                result.score(),
                result.confidence(),
                result.explanation(),
                result.evidence(),
                false,
                1L,
                null);
    }

    @Test
    void differentFactMatchOutcomesAcrossRepetitionsAreMaterialInconsistency() {
        TestExecution first = repetition(1, "Records are retained for 30 days.");
        TestExecution second = repetition(2, "Records are retained for 14 days.");
        var comparison = analyzer.compare(
                List.of(first, second),
                Map.of(first.getId(), List.of(evaluate(first)), second.getId(), List.of(evaluate(second))));
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.consistent()).isFalse();
        assertThat(comparison.differences()).anySatisfy(diff -> assertThat(diff).contains("retention"));
    }

    @Test
    void wordingVariationWithIdenticalFactOutcomesIsConsistent() {
        TestExecution first = repetition(1, "Records are retained for 30 days.");
        TestExecution second = repetition(2, "The retention period for records is 30 days, per policy.");
        var comparison = analyzer.compare(
                List.of(first, second),
                Map.of(first.getId(), List.of(evaluate(first)), second.getId(), List.of(evaluate(second))));
        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.consistent()).isTrue();
    }

    @Test
    void fewerThanTwoSuccessfulRepetitionsAreNotComparable() {
        TestExecution only = repetition(1, "Records are retained for 30 days.");
        var comparison = analyzer.compare(List.of(only), Map.of(only.getId(), List.of(evaluate(only))));
        assertThat(comparison.comparable()).isFalse();
    }
}
