package com.rageval.workbench.evaluation.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.rageval.workbench.evaluation.deterministic.RefusalEvaluator;
import com.rageval.workbench.evaluation.deterministic.RequiredFactsEvaluator;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.llm.AbstractLlmEvaluator;
import com.rageval.workbench.evaluation.llm.ContradictionEvaluator;
import com.rageval.workbench.evaluation.llm.GroundednessEvaluator;
import com.rageval.workbench.evaluation.llm.ReferenceCorrectnessEvaluator;
import com.rageval.workbench.evaluation.llm.SemanticRefusalEvaluator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Spec §14: deterministic/LLM disagreement and LLM/LLM disagreement flag both results for human
 * review — the conflicting verdicts stay visible instead of being averaged away.
 */
class CrossEvaluatorReviewTriggersTest {

    private final CrossEvaluatorReviewTriggers triggers = new CrossEvaluatorReviewTriggers();
    private final UUID executionId = UUID.randomUUID();

    private EvaluationMetricResult completed(String key, boolean passed, List<EvaluationEvidence> evidence) {
        return new EvaluationMetricResult(
                executionId, key, EvaluatorType.LLM, "1.0.0", null, null,
                MetricResultStatus.COMPLETED, passed, passed ? 1.0 : 0.0, 0.9, "verdict", evidence, false, 1L, null);
    }

    private EvaluationMetricResult failed(String key) {
        return new EvaluationMetricResult(
                executionId, key, EvaluatorType.LLM, "1.0.0", null, null,
                MetricResultStatus.FAILED, null, null, null, "evaluator failed", List.of(), true, 1L, null);
    }

    @Test
    void deterministicAndLlmRefusalDisagreementFlagsBothResults() {
        EvaluationMetricResult deterministic = completed(RefusalEvaluator.KEY, true, List.of());
        EvaluationMetricResult semantic = completed(SemanticRefusalEvaluator.KEY, false, List.of());

        triggers.apply(List.of(deterministic, semantic));

        assertThat(deterministic.isRequiresHumanReview()).isTrue();
        assertThat(semantic.isRequiresHumanReview()).isTrue();
        assertThat(deterministic.getExplanation()).contains("disagree");
    }

    @Test
    void agreementFlagsNothing() {
        EvaluationMetricResult deterministic = completed(RefusalEvaluator.KEY, false, List.of());
        EvaluationMetricResult semantic = completed(SemanticRefusalEvaluator.KEY, false, List.of());

        triggers.apply(List.of(deterministic, semantic));

        assertThat(deterministic.isRequiresHumanReview()).isFalse();
        assertThat(semantic.isRequiresHumanReview()).isFalse();
    }

    @Test
    void failedEvaluatorResultsAreNeverTreatedAsDisagreement() {
        EvaluationMetricResult deterministic = completed(RequiredFactsEvaluator.KEY, true, List.of());
        EvaluationMetricResult llm = failed(ReferenceCorrectnessEvaluator.KEY);

        triggers.apply(List.of(deterministic, llm));

        assertThat(deterministic.isRequiresHumanReview()).isFalse();
    }

    @Test
    void groundednessPassWithAnswerVsContextContradictionFlagsBoth() {
        EvaluationMetricResult groundedness = completed(GroundednessEvaluator.KEY, true, List.of());
        EvaluationMetricResult contradiction = completed(
                ContradictionEvaluator.KEY,
                false,
                List.of(new EvaluationEvidence(
                        AbstractLlmEvaluator.JUDGE_OUTPUT_EVIDENCE,
                        "output",
                        null,
                        null,
                        null,
                        null,
                        Map.of(ContradictionEvaluator.TYPES_METADATA_KEY, List.of("ANSWER_VS_CONTEXT")))));

        triggers.apply(List.of(groundedness, contradiction));

        assertThat(groundedness.isRequiresHumanReview()).isTrue();
        assertThat(contradiction.isRequiresHumanReview()).isTrue();
    }

    @Test
    void contradictionOfADifferentRelationDoesNotFlagGroundedness() {
        EvaluationMetricResult groundedness = completed(GroundednessEvaluator.KEY, true, List.of());
        EvaluationMetricResult contradiction = completed(
                ContradictionEvaluator.KEY,
                false,
                List.of(new EvaluationEvidence(
                        AbstractLlmEvaluator.JUDGE_OUTPUT_EVIDENCE,
                        "output",
                        null,
                        null,
                        null,
                        null,
                        Map.of(ContradictionEvaluator.TYPES_METADATA_KEY, List.of("ANSWER_VS_SELF")))));

        triggers.apply(List.of(groundedness, contradiction));

        assertThat(groundedness.isRequiresHumanReview()).isFalse();
    }
}
