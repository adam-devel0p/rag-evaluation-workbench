package com.rageval.workbench.evaluation.deterministic;

import static com.rageval.workbench.evaluation.EvaluationFixtures.answerableTestCase;
import static com.rageval.workbench.evaluation.EvaluationFixtures.context;
import static com.rageval.workbench.evaluation.EvaluationFixtures.fact;
import static com.rageval.workbench.evaluation.EvaluationFixtures.succeededExecution;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.evaluation.EvaluationFixtures;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequiredFactsEvaluatorTest {

    private final RequiredFactsEvaluator evaluator = new RequiredFactsEvaluator();

    @Test
    void exactTextMatchIsCaseSensitive() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("f1", "Java 21", FactMatchType.EXACT_TEXT, true)), List.of(), List.of());
        EvaluationResult matched = evaluator.evaluate(
                context(testCase, succeededExecution("Requires Java 21 today.", List.of(), List.of(), null)));
        assertThat(matched.passed()).isTrue();
        assertThat(matched.score()).isEqualTo(1.0);

        EvaluationResult lowercased = evaluator.evaluate(
                context(testCase, succeededExecution("Requires java 21 today.", List.of(), List.of(), null)));
        assertThat(lowercased.passed()).isFalse();
        assertThat(lowercased.score()).isEqualTo(0.0);
    }

    @Test
    void caseInsensitiveMatchQuotesTheOriginalAnswerText() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("f1", "java 21", FactMatchType.CASE_INSENSITIVE_TEXT, true)), List.of(), List.of());
        EvaluationResult result = evaluator.evaluate(
                context(testCase, succeededExecution("It needs JAVA 21 now.", List.of(), List.of(), null)));
        assertThat(result.passed()).isTrue();
        assertThat(result.evidence())
                .anySatisfy(evidence -> assertThat(evidence.quotedText()).isEqualTo("JAVA 21"));
    }

    @Test
    void regexMatchingUsesFindAndQuotesTheMatch() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("f1", "Java\\s+(21|22)", FactMatchType.REGEX, true)), List.of(), List.of());
        EvaluationResult result = evaluator.evaluate(
                context(testCase, succeededExecution("Use Java  21 or newer.", List.of(), List.of(), null)));
        assertThat(result.passed()).isTrue();
        assertThat(result.evidence())
                .anySatisfy(evidence -> assertThat(evidence.quotedText()).isEqualTo("Java  21"));
    }

    @Test
    void anyMissingMandatoryFactFailsAndScoreIsMatchedOverTotalMandatory() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(
                        fact("f1", "30 seconds", FactMatchType.CASE_INSENSITIVE_TEXT, true),
                        fact("f2", "retry count is 3", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of(),
                List.of());
        EvaluationResult result = evaluator.evaluate(context(
                testCase, succeededExecution("The timeout is 30 seconds.", List.of(), List.of(), null)));
        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.explanation()).contains("f2");
    }

    @Test
    void optionalFactsAreRecordedButNeverFailTheMetric() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(
                        fact("mandatory", "alpha", FactMatchType.CASE_INSENSITIVE_TEXT, true),
                        fact("optional", "omega", FactMatchType.CASE_INSENSITIVE_TEXT, false)),
                List.of(),
                List.of());
        EvaluationResult result = evaluator.evaluate(
                context(testCase, succeededExecution("Only alpha appears.", List.of(), List.of(), null)));
        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(1.0);
        assertThat(result.evidence())
                .anySatisfy(evidence -> assertThat(evidence.description()).contains("optional"));
    }

    @Test
    void noFactsMeansNotApplicable() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        EvaluationApplicability applicability = evaluator.applicability(
                context(testCase, succeededExecution("anything", List.of(), List.of(), null)));
        assertThat(applicability.status()).isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
    }

    @Test
    void onlySemanticFactsMeansNotApplicableWithAnExplicitReason() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("f1", "the answer is grounded", FactMatchType.SEMANTIC, true)), List.of(), List.of());
        EvaluationApplicability applicability = evaluator.applicability(
                context(testCase, succeededExecution("anything", List.of(), List.of(), null)));
        assertThat(applicability.status()).isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
        assertThat(applicability.reason()).contains("SEMANTIC");
    }

    @Test
    void failedExecutionMeansMissingRequiredDataNotAChatbotFailure() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(fact("f1", "x", FactMatchType.EXACT_TEXT, true)), List.of(), List.of());
        EvaluationContext context = context(
                testCase, EvaluationFixtures.failedExecution(ExecutionStatus.TARGET_ERROR, 500));
        assertThat(evaluator.applicability(context).status())
                .isEqualTo(EvaluationApplicability.Status.MISSING_REQUIRED_DATA);
    }
}
