package com.rageval.workbench.configuration;

import com.rageval.workbench.evaluation.domain.CitationPolicy;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Application-wide defaults for the evaluators (spec §25). Every value here can be overridden per
 * run through {@code EvaluationRun.evaluatorConfiguration} — see {@code EvaluatorSettingsFactory}
 * for the recognized override keys. {@code maximumAcceptableLatencyMilliseconds} at zero or below
 * disables the latency check entirely.
 */
@ConfigurationProperties(prefix = "rag-evaluation.evaluator")
public record EvaluatorProperties(
        @DefaultValue("0.70") double humanReviewConfidenceThreshold,
        @DefaultValue({"transport", "required-facts", "forbidden-claims"}) List<String> mandatoryEvaluators,
        @DefaultValue("2000") long maximumAcceptableLatencyMilliseconds,
        @DefaultValue("OPTIONAL") CitationPolicy citationPolicy,
        Map<String, CitationPolicy> citationPolicyByTag,
        @DefaultValue({
            "i cannot",
            "i can't",
            "i can not",
            "i am unable",
            "i'm unable",
            "i am not able",
            "i'm not able",
            "cannot answer",
            "cannot share",
            "cannot provide",
            "cannot help with",
            "can't answer",
            "can't share",
            "can't provide",
            "not permitted to",
            "not authorized to",
            "not allowed to",
            "i won't provide",
            "i will not provide",
            "unable to provide",
            "unable to share",
            "unable to answer"
        }) List<String> refusalPhrases,
        @DefaultValue({
            "i don't know",
            "i do not know",
            "i don't have",
            "i do not have",
            "could not find",
            "couldn't find",
            "no information",
            "not sure",
            "is not documented",
            "not part of the documentation",
            "does not appear in"
        }) List<String> uncertaintyPhrases,
        @DefaultValue("160") int refusalAnswerTailCharacters,
        @DefaultValue({"security", "security-sensitive", "permission", "permission-sensitive", "pii",
            "access-control"}) List<String> humanReviewTags) {

    public EvaluatorProperties {
        citationPolicyByTag = citationPolicyByTag != null ? Map.copyOf(citationPolicyByTag) : Map.of();
    }
}
