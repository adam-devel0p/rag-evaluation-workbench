package com.rageval.workbench.evaluation.deterministic;

import static com.rageval.workbench.evaluation.EvaluationFixtures.answerableTestCase;
import static com.rageval.workbench.evaluation.EvaluationFixtures.chunk;
import static com.rageval.workbench.evaluation.EvaluationFixtures.citation;
import static com.rageval.workbench.evaluation.EvaluationFixtures.context;
import static com.rageval.workbench.evaluation.EvaluationFixtures.succeededExecution;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.run.domain.Citation;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CitationRetrievalConsistencyEvaluatorTest {

    private final CitationRetrievalConsistencyEvaluator evaluator = new CitationRetrievalConsistencyEvaluator();

    private final EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());

    @Test
    void citationToRetrievedDocumentIsConsistent() {
        EvaluationResult result = evaluator.evaluate(context(
                testCase,
                succeededExecution("x", List.of(citation("doc-a")), List.of(chunk("doc-a", 1)), null)));
        assertThat(result.passed()).isTrue();
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void citationToUnretrievedDocumentIsFlaggedWithoutClaimingCitationSupport() {
        EvaluationResult result = evaluator.evaluate(context(
                testCase,
                succeededExecution(
                        "x",
                        List.of(citation("legacy-notes"), citation("doc-a")),
                        List.of(chunk("doc-a", 1)),
                        null)));
        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(result.explanation())
                .contains("legacy-notes")
                .contains("does not prove that any citation supports the answer");
        assertThat(result.evidence())
                .anySatisfy(evidence -> {
                    assertThat(evidence.type()).isEqualTo("citation-to-unretrieved-document");
                    assertThat(evidence.documentId()).isEqualTo("legacy-notes");
                });
    }

    @Test
    void noCitationsMeansNotApplicable() {
        assertThat(evaluator.applicability(
                        context(testCase, succeededExecution("x", List.of(), List.of(chunk("doc-a", 1)), null)))
                        .status())
                .isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
    }

    @Test
    void citationsWithoutDocumentIdsMeanMissingRequiredData() {
        Citation titleOnly = new Citation(null, "Some Title", null, null, null, Map.of());
        assertThat(evaluator.applicability(context(
                        testCase, succeededExecution("x", List.of(titleOnly), List.of(chunk("doc-a", 1)), null)))
                        .status())
                .isEqualTo(EvaluationApplicability.Status.MISSING_REQUIRED_DATA);
    }

    @Test
    void citationsWithoutChunksMeanMissingRequiredData() {
        assertThat(evaluator.applicability(
                        context(testCase, succeededExecution("x", List.of(citation("doc-a")), List.of(), null)))
                        .status())
                .isEqualTo(EvaluationApplicability.Status.MISSING_REQUIRED_DATA);
    }
}
