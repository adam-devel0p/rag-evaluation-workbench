package com.rageval.workbench.evaluation.deterministic;

import static com.rageval.workbench.evaluation.EvaluationFixtures.citation;
import static com.rageval.workbench.evaluation.EvaluationFixtures.context;
import static com.rageval.workbench.evaluation.EvaluationFixtures.settings;
import static com.rageval.workbench.evaluation.EvaluationFixtures.succeededExecution;
import static com.rageval.workbench.evaluation.EvaluationFixtures.testCase;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.Severity;
import com.rageval.workbench.evaluation.domain.CitationPolicy;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.run.domain.Citation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitationPresenceEvaluatorTest {

    private final CitationPresenceEvaluator evaluator = new CitationPresenceEvaluator();

    private EvaluationTestCase caseWithTags(List<String> tags) {
        return testCase(true, false, Severity.MEDIUM, List.of(), List.of(), List.of(), tags);
    }

    @Test
    void requiredPolicyFailsWhenNoCitationExists() {
        EvaluationResult result = evaluator.evaluate(context(
                caseWithTags(List.of()),
                succeededExecution("x", List.of(), List.of(), null),
                settings(2000, CitationPolicy.REQUIRED, Map.of())));
        assertThat(result.passed()).isFalse();
        assertThat(result.explanation()).contains("required");
    }

    @Test
    void forbiddenPolicyFailsWhenCitationsExist() {
        EvaluationResult result = evaluator.evaluate(context(
                caseWithTags(List.of()),
                succeededExecution("x", List.of(citation("doc-a")), List.of(), null),
                settings(2000, CitationPolicy.FORBIDDEN, Map.of())));
        assertThat(result.passed()).isFalse();
        assertThat(result.explanation()).contains("FORBIDDEN");
    }

    @Test
    void optionalPolicyWithoutCitationsIsNotApplicable() {
        assertThat(evaluator.applicability(context(
                        caseWithTags(List.of()),
                        succeededExecution("x", List.of(), List.of(), null),
                        settings(2000, CitationPolicy.OPTIONAL, Map.of())))
                        .status())
                .isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
    }

    @Test
    void blankAndDuplicateCitationsAreViolationsEvenWhenOptional() {
        Citation blank = new Citation("  ", null, null, " ", null, Map.of());
        EvaluationResult result = evaluator.evaluate(context(
                caseWithTags(List.of()),
                succeededExecution("x", List.of(blank, citation("doc-a"), citation("doc-a")), List.of(), null),
                settings(2000, CitationPolicy.OPTIONAL, Map.of())));
        assertThat(result.passed()).isFalse();
        assertThat(result.explanation()).contains("neither a document ID nor a URL").contains("duplicates");
    }

    @Test
    void tagOverrideWinsOverDefaultPolicy() {
        EvaluationResult result = evaluator.evaluate(context(
                caseWithTags(List.of("needs-citation")),
                succeededExecution("x", List.of(), List.of(), null),
                settings(2000, CitationPolicy.OPTIONAL, Map.of("needs-citation", CitationPolicy.REQUIRED))));
        assertThat(result.passed()).isFalse();
    }

    @Test
    void wellFormedCitationsPassTheRequiredPolicy() {
        EvaluationResult result = evaluator.evaluate(context(
                caseWithTags(List.of()),
                succeededExecution("x", List.of(citation("doc-a"), citation("doc-b")), List.of(), null),
                settings(2000, CitationPolicy.REQUIRED, Map.of())));
        assertThat(result.passed()).isTrue();
    }
}
