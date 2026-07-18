package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.evaluation.llm.AbstractLlmEvaluator;
import com.rageval.workbench.evaluation.llm.ContradictionEvaluator;
import com.rageval.workbench.evaluation.llm.ContradictionType;
import com.rageval.workbench.evaluation.llm.CompletenessEvaluator;
import com.rageval.workbench.evaluation.llm.GroundednessEvaluator;
import com.rageval.workbench.evaluation.llm.ReferenceCorrectnessEvaluator;
import com.rageval.workbench.evaluation.llm.SemanticRefusalEvaluator;
import com.rageval.workbench.evaluation.deterministic.RefusalEvaluator;
import com.rageval.workbench.evaluation.deterministic.RequiredFactsEvaluator;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The spec §14 human-review triggers that only become computable once every evaluator's verdict
 * for one execution exists: a deterministic and an LLM evaluator disagreeing on the same aspect,
 * and two LLM evaluators disagreeing. Disagreeing results are flagged (never averaged into a
 * single misleading number — both verdicts stay visible) with the reason appended to their
 * explanations.
 *
 * <p>Only pairs that judge the same aspect are compared; an answer can legitimately be relevant
 * yet ungrounded, so a blanket any-two-metrics-differ rule would drown reviewers in noise.
 */
@Component
public class CrossEvaluatorReviewTriggers {

    /** Pairs of evaluator keys that judge the same aspect deterministically vs. by LLM. */
    private static final List<KeyPair> SAME_ASPECT_PAIRS = List.of(
            new KeyPair(RefusalEvaluator.KEY, SemanticRefusalEvaluator.KEY,
                    "the deterministic phrase-based refusal verdict and the semantic refusal verdict disagree"),
            new KeyPair(RequiredFactsEvaluator.KEY, ReferenceCorrectnessEvaluator.KEY,
                    "the deterministic required-facts verdict and the LLM reference-correctness verdict disagree"),
            new KeyPair(RequiredFactsEvaluator.KEY, CompletenessEvaluator.KEY,
                    "the deterministic required-facts verdict and the LLM completeness verdict disagree"));

    public void apply(Collection<EvaluationMetricResult> results) {
        for (KeyPair pair : SAME_ASPECT_PAIRS) {
            Optional<EvaluationMetricResult> first = completed(results, pair.firstKey());
            Optional<EvaluationMetricResult> second = completed(results, pair.secondKey());
            if (first.isPresent() && second.isPresent()
                    && !first.get().getPassed().equals(second.get().getPassed())) {
                first.get().flagForHumanReview(pair.reason());
                second.get().flagForHumanReview(pair.reason());
            }
        }
        contradictionDisagreement(
                results,
                GroundednessEvaluator.KEY,
                ContradictionType.ANSWER_VS_CONTEXT,
                "the groundedness evaluator found the answer supported by the context while the contradiction "
                        + "evaluator found the answer contradicting the context");
        contradictionDisagreement(
                results,
                ReferenceCorrectnessEvaluator.KEY,
                ContradictionType.ANSWER_VS_REFERENCE,
                "the reference-correctness evaluator passed the answer while the contradiction evaluator found "
                        + "it contradicting the reference answer");
    }

    /** Flags an LLM metric that passed although the contradiction evaluator found the opposite relation. */
    private void contradictionDisagreement(
            Collection<EvaluationMetricResult> results,
            String passedKey,
            ContradictionType type,
            String reason) {
        Optional<EvaluationMetricResult> passing = completed(results, passedKey)
                .filter(result -> Boolean.TRUE.equals(result.getPassed()));
        Optional<EvaluationMetricResult> contradiction = completed(results, ContradictionEvaluator.KEY)
                .filter(result -> detectedTypes(result).contains(type.name()));
        if (passing.isPresent() && contradiction.isPresent()) {
            passing.get().flagForHumanReview(reason);
            contradiction.get().flagForHumanReview(reason);
        }
    }

    private static Optional<EvaluationMetricResult> completed(
            Collection<EvaluationMetricResult> results, String evaluatorKey) {
        return results.stream()
                .filter(result -> evaluatorKey.equals(result.getEvaluatorKey())
                        && result.getStatus() == MetricResultStatus.COMPLETED
                        && result.getPassed() != null)
                .findFirst();
    }

    private static List<String> detectedTypes(EvaluationMetricResult contradictionResult) {
        for (EvaluationEvidence evidence : contradictionResult.getEvidence()) {
            if (AbstractLlmEvaluator.JUDGE_OUTPUT_EVIDENCE.equals(evidence.type())) {
                Map<String, Object> metadata = evidence.metadata();
                Object types = metadata != null ? metadata.get(ContradictionEvaluator.TYPES_METADATA_KEY) : null;
                if (types instanceof List<?> list) {
                    return list.stream().map(String::valueOf).toList();
                }
            }
        }
        return List.of();
    }

    private record KeyPair(String firstKey, String secondKey, String reason) {
    }
}
