package com.rageval.workbench.evaluation.deterministic;

import static com.rageval.workbench.evaluation.EvaluationFixtures.answerableTestCase;
import static com.rageval.workbench.evaluation.EvaluationFixtures.chunk;
import static com.rageval.workbench.evaluation.EvaluationFixtures.context;
import static com.rageval.workbench.evaluation.EvaluationFixtures.succeededExecution;
import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpectedDocumentEvaluatorTest {

    private final ExpectedDocumentEvaluator evaluator = new ExpectedDocumentEvaluator();

    private Map<String, Object> retrievalMetrics(EvaluationResult result) {
        return result.evidence().stream()
                .filter(evidence -> "retrieval-metrics".equals(evidence.type()))
                .findFirst()
                .map(EvaluationEvidence::metadata)
                .orElseThrow();
    }

    @Test
    void expectedDocumentAtRankOneHitsEverything() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("doc-a"));
        EvaluationResult result = evaluator.evaluate(context(
                testCase,
                succeededExecution("x", List.of(), List.of(chunk("doc-a", 1), chunk("doc-b", 2)), null)));
        Map<String, Object> metrics = retrievalMetrics(result);
        assertThat(result.passed()).isTrue();
        assertThat(metrics)
                .containsEntry(ExpectedDocumentEvaluator.HIT_AT_1_KEY, true)
                .containsEntry(ExpectedDocumentEvaluator.HIT_AT_3_KEY, true)
                .containsEntry(ExpectedDocumentEvaluator.HIT_AT_5_KEY, true)
                .containsEntry(ExpectedDocumentEvaluator.FIRST_RELEVANT_RANK_KEY, 1);
        assertThat(result.score()).isEqualTo(1.0);
    }

    @Test
    void rankFourHitsAtFiveButNotAtThree() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("doc-x"));
        EvaluationResult result = evaluator.evaluate(context(
                testCase,
                succeededExecution(
                        "x",
                        List.of(),
                        List.of(chunk("a", 1), chunk("b", 2), chunk("c", 3), chunk("doc-x", 4)),
                        null)));
        Map<String, Object> metrics = retrievalMetrics(result);
        assertThat(metrics)
                .containsEntry(ExpectedDocumentEvaluator.HIT_AT_1_KEY, false)
                .containsEntry(ExpectedDocumentEvaluator.HIT_AT_3_KEY, false)
                .containsEntry(ExpectedDocumentEvaluator.HIT_AT_5_KEY, true)
                .containsEntry(ExpectedDocumentEvaluator.FIRST_RELEVANT_RANK_KEY, 4);
        assertThat(result.score()).isEqualTo(0.25);
    }

    @Test
    void reciprocalRankUsesTheBestExpectedDocument() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("doc-a", "doc-b"));
        EvaluationResult result = evaluator.evaluate(context(
                testCase,
                succeededExecution("x", List.of(), List.of(chunk("z", 1), chunk("doc-b", 2), chunk("doc-a", 3)), null)));
        assertThat(result.score()).isEqualTo(0.5);
        assertThat(retrievalMetrics(result)).containsEntry(ExpectedDocumentEvaluator.FIRST_RELEVANT_RANK_KEY, 2);
    }

    @Test
    void absentExpectedDocumentFailsWithReciprocalRankZero() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("missing-doc"));
        EvaluationResult result = evaluator.evaluate(context(
                testCase, succeededExecution("x", List.of(), List.of(chunk("other", 1)), null)));
        assertThat(result.passed()).isFalse();
        assertThat(result.score()).isEqualTo(0.0);
        assertThat(retrievalMetrics(result)).containsEntry(ExpectedDocumentEvaluator.HIT_AT_5_KEY, false);
    }

    @Test
    void listPositionIsUsedWhenChunksCarryNoRank() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("doc-a"));
        RetrievedChunk unranked = new RetrievedChunk("c1", "doc-a", null, "content", null, null, Map.of());
        RetrievedChunk unrankedFirst = new RetrievedChunk("c0", "doc-z", null, "content", null, null, Map.of());
        EvaluationResult result = evaluator.evaluate(
                context(testCase, succeededExecution("x", List.of(), List.of(unrankedFirst, unranked), null)));
        assertThat(retrievalMetrics(result)).containsEntry(ExpectedDocumentEvaluator.FIRST_RELEVANT_RANK_KEY, 2);
    }

    @Test
    void noChunksMeansMissingRequiredDataNotAFailedMetric() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of("doc-a"));
        EvaluationApplicability applicability = evaluator.applicability(
                context(testCase, succeededExecution("x", List.of(), List.of(), null)));
        assertThat(applicability.status()).isEqualTo(EvaluationApplicability.Status.MISSING_REQUIRED_DATA);
    }

    @Test
    void noExpectedDocumentsMeansNotApplicable() {
        EvaluationTestCase testCase = answerableTestCase(List.of(), List.of(), List.of());
        assertThat(evaluator.applicability(
                        context(testCase, succeededExecution("x", List.of(), List.of(chunk("a", 1)), null)))
                        .status())
                .isEqualTo(EvaluationApplicability.Status.NOT_APPLICABLE);
    }
}
