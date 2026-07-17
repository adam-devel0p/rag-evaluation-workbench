package com.rageval.workbench.evaluation.deterministic;

import static com.rageval.workbench.evaluation.EvaluationFixtures.answerableTestCase;
import static com.rageval.workbench.evaluation.EvaluationFixtures.context;
import static com.rageval.workbench.evaluation.EvaluationFixtures.settings;
import static com.rageval.workbench.evaluation.EvaluationFixtures.succeededExecution;
import static com.rageval.workbench.evaluation.EvaluationFixtures.testCase;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.EvaluationFixtures;
import com.rageval.workbench.evaluation.domain.CitationPolicy;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.run.domain.ExecutionStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TransportAndLatencyAndAnswerabilityEvaluatorTest {

    private final EvaluationTestCase plainCase = answerableTestCase(List.of(), List.of(), List.of());

    @Nested
    class Transport {
        private final TransportEvaluator evaluator = new TransportEvaluator();

        @Test
        void succeededExecutionWithAnswerPasses() {
            EvaluationResult result = evaluator.evaluate(
                    context(plainCase, succeededExecution("an answer", List.of(), List.of(), null)));
            assertThat(result.passed()).isTrue();
        }

        @Test
        void failedExecutionFailsWithTheFailureInEvidence() {
            EvaluationResult result = evaluator.evaluate(
                    context(plainCase, EvaluationFixtures.failedExecution(ExecutionStatus.TIMED_OUT, null)));
            assertThat(result.passed()).isFalse();
            assertThat(result.explanation()).contains("TIMED_OUT");
        }

        @Test
        void blankAnswerOnSuccessfulInvocationFails() {
            EvaluationResult result = evaluator.evaluate(
                    context(plainCase, succeededExecution("   ", List.of(), List.of(), null)));
            assertThat(result.passed()).isFalse();
            assertThat(result.explanation()).contains("non-blank answer");
        }
    }

    @Nested
    class Latency {
        private final LatencyEvaluator evaluator = new LatencyEvaluator();

        @Test
        void latencyWithinMaximumPasses() {
            EvaluationResult result = evaluator.evaluate(
                    context(plainCase, succeededExecution("x", List.of(), List.of(), 1500L)));
            assertThat(result.passed()).isTrue();
        }

        @Test
        void latencyAboveMaximumFails() {
            EvaluationResult result = evaluator.evaluate(
                    context(plainCase, succeededExecution("x", List.of(), List.of(), 2500L)));
            assertThat(result.passed()).isFalse();
            assertThat(result.explanation()).contains("2500").contains("2000");
        }

        @Test
        void disabledThresholdMeansNotApplicable() {
            assertThat(evaluator.applicability(context(
                            plainCase,
                            succeededExecution("x", List.of(), List.of(), 100L),
                            settings(0, CitationPolicy.OPTIONAL, Map.of())))
                            .status())
                    .isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
        }

        @Test
        void missingLatencyMeansMissingRequiredData() {
            assertThat(evaluator.applicability(context(
                            plainCase, EvaluationFixtures.failedExecution(ExecutionStatus.TARGET_ERROR, 500)))
                            .status())
                    .isEqualTo(EvaluationApplicability.Status.MISSING_REQUIRED_DATA);
        }
    }

    @Nested
    class Answerability {
        private final AnswerabilityEvaluator evaluator = new AnswerabilityEvaluator();

        private final EvaluationTestCase unanswerable =
                testCase(false, false, Severity.MEDIUM, List.of(), List.of(), List.of(), List.of());

        @Test
        void uncertaintySignalPasses() {
            EvaluationResult result = evaluator.evaluate(context(
                    unanswerable,
                    succeededExecution(
                            "I could not find information about that in the documentation.",
                            List.of(),
                            List.of(),
                            null)));
            assertThat(result.passed()).isTrue();
        }

        @Test
        void confidentAnswerToUnanswerableQuestionFailsAndIsFlaggedForReview() {
            EvaluationResult result = evaluator.evaluate(context(
                    unanswerable,
                    succeededExecution("The CFO's phone number is 555-0100.", List.of(), List.of(), null)));
            assertThat(result.passed()).isFalse();
            assertThat(result.requiresHumanReview()).isTrue();
            assertThat(result.explanation()).contains("fabrication");
        }

        @Test
        void answerableNoRefusalCaseIsNotApplicable() {
            assertThat(evaluator.applicability(
                            context(plainCase, succeededExecution("x", List.of(), List.of(), null)))
                            .status())
                    .isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
        }
    }
}
