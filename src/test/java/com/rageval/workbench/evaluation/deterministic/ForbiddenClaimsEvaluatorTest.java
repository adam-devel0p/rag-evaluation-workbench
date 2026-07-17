package com.rageval.workbench.evaluation.deterministic;

import static com.rageval.workbench.evaluation.EvaluationFixtures.answerableTestCase;
import static com.rageval.workbench.evaluation.EvaluationFixtures.context;
import static com.rageval.workbench.evaluation.EvaluationFixtures.fact;
import static com.rageval.workbench.evaluation.EvaluationFixtures.succeededExecution;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.FactMatchType;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import java.util.List;
import org.junit.jupiter.api.Test;

class ForbiddenClaimsEvaluatorTest {

    private final ForbiddenClaimsEvaluator evaluator = new ForbiddenClaimsEvaluator();

    @Test
    void anyMatchingForbiddenClaimFailsWithIdentifyingEvidence() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(),
                List.of(fact("old-version", "Java 17", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of());
        EvaluationResult result = evaluator.evaluate(context(
                testCase, succeededExecution("The service requires java 17.", List.of(), List.of(), null)));
        assertThat(result.passed()).isFalse();
        assertThat(result.explanation()).contains("old-version");
        assertThat(result.evidence()).anySatisfy(evidence -> {
            assertThat(evidence.type()).isEqualTo("forbidden-claim-matched");
            assertThat(evidence.quotedText()).isEqualTo("java 17");
        });
    }

    @Test
    void regexForbiddenClaimMatches() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(),
                List.of(fact("credential", "password\\s+is\\s+\\S+", FactMatchType.REGEX, true)),
                List.of());
        EvaluationResult result = evaluator.evaluate(context(
                testCase, succeededExecution("The password is hunter2 here.", List.of(), List.of(), null)));
        assertThat(result.passed()).isFalse();
        assertThat(result.evidence())
                .anySatisfy(evidence -> assertThat(evidence.quotedText()).isEqualTo("password is hunter2"));
    }

    @Test
    void noMatchPassesWithScoreOne() {
        EvaluationTestCase testCase = answerableTestCase(
                List.of(),
                List.of(
                        fact("c1", "TLS 1.1", FactMatchType.CASE_INSENSITIVE_TEXT, true),
                        fact("c2", "TLS 1.0", FactMatchType.CASE_INSENSITIVE_TEXT, true)),
                List.of());
        EvaluationResult result = evaluator.evaluate(
                context(testCase, succeededExecution("Use TLS 1.3 only.", List.of(), List.of(), null)));
        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void noForbiddenClaimsMeansNotApplicable() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        assertThat(evaluator.applicability(
                        context(testCase, succeededExecution("anything", List.of(), List.of(), null)))
                        .status())
                .isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
    }
}
