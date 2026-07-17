package com.rageval.workbench.evaluation.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The evaluator configuration resolved for one run: application defaults overlaid with the run's
 * own {@code evaluatorConfiguration} overrides. A plain immutable value — resolution from
 * properties and the run's JSONB map happens in the application layer.
 *
 * <p>{@code maximumAcceptableLatencyMilliseconds} at zero or below disables the latency check.
 * {@code citationPolicyByTag} is consulted in the test case's own tag order; the first tag with a
 * mapping wins, falling back to {@code citationPolicy}.
 */
public record EvaluatorSettings(
        long maximumAcceptableLatencyMilliseconds,
        CitationPolicy citationPolicy,
        Map<String, CitationPolicy> citationPolicyByTag,
        List<String> refusalPhrases,
        List<String> uncertaintyPhrases,
        int refusalAnswerTailCharacters,
        double humanReviewConfidenceThreshold,
        List<String> mandatoryEvaluatorKeys,
        List<String> humanReviewTags) {

    public EvaluatorSettings {
        Objects.requireNonNull(citationPolicy, "citationPolicy must not be null");
        citationPolicyByTag = citationPolicyByTag != null ? Map.copyOf(citationPolicyByTag) : Map.of();
        refusalPhrases = refusalPhrases != null ? List.copyOf(refusalPhrases) : List.of();
        uncertaintyPhrases = uncertaintyPhrases != null ? List.copyOf(uncertaintyPhrases) : List.of();
        mandatoryEvaluatorKeys = mandatoryEvaluatorKeys != null ? List.copyOf(mandatoryEvaluatorKeys) : List.of();
        humanReviewTags = humanReviewTags != null ? List.copyOf(humanReviewTags) : List.of();
    }

    /** The citation policy for a test case with the given tags: first tag override wins, else the default. */
    public CitationPolicy citationPolicyFor(List<String> tags) {
        if (tags != null) {
            for (String tag : tags) {
                CitationPolicy override = citationPolicyByTag.get(tag);
                if (override != null) {
                    return override;
                }
            }
        }
        return citationPolicy;
    }
}
