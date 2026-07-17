package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.configuration.EvaluatorProperties;
import com.rageval.workbench.evaluation.domain.CitationPolicy;
import com.rageval.workbench.evaluation.domain.EvaluatorSettings;
import com.rageval.workbench.run.domain.EvaluationRun;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves the effective {@link EvaluatorSettings} for a run: application defaults from
 * {@link EvaluatorProperties} overlaid with the run's own {@code evaluatorConfiguration} JSONB
 * map. Recognized override keys (all optional):
 *
 * <pre>
 * maximumAcceptableLatencyMilliseconds  number   (&lt;= 0 disables the latency check)
 * citationPolicy                        string   REQUIRED | OPTIONAL | FORBIDDEN
 * citationPolicyByTag                   object   tag -&gt; policy string
 * refusalPhrases                        array of strings
 * uncertaintyPhrases                    array of strings
 * refusalAnswerTailCharacters           number
 * humanReviewConfidenceThreshold        number in [0, 1]
 * mandatoryEvaluators                   array of evaluator keys
 * humanReviewTags                       array of strings
 * </pre>
 *
 * Malformed values throw {@link IllegalArgumentException} with the offending key — surfaced as a
 * 400 at run creation via the pipeline's configuration validation, so a run that reaches a worker
 * always has resolvable settings.
 */
@Component
public class EvaluatorSettingsFactory {

    private final EvaluatorProperties properties;

    public EvaluatorSettingsFactory(EvaluatorProperties properties) {
        this.properties = properties;
    }

    public EvaluatorSettings resolve(EvaluationRun run) {
        return resolve(run.getEvaluatorConfiguration());
    }

    public EvaluatorSettings resolve(Map<String, Object> overrides) {
        Map<String, Object> config = overrides != null ? overrides : Map.of();
        return new EvaluatorSettings(
                longValue(config, "maximumAcceptableLatencyMilliseconds", properties.maximumAcceptableLatencyMilliseconds()),
                policyValue(config.get("citationPolicy"), "citationPolicy", properties.citationPolicy()),
                policyMap(config, "citationPolicyByTag", properties.citationPolicyByTag()),
                stringList(config, "refusalPhrases", properties.refusalPhrases()),
                stringList(config, "uncertaintyPhrases", properties.uncertaintyPhrases()),
                (int) longValue(config, "refusalAnswerTailCharacters", properties.refusalAnswerTailCharacters()),
                thresholdValue(config, "humanReviewConfidenceThreshold", properties.humanReviewConfidenceThreshold()),
                stringList(config, "mandatoryEvaluators", properties.mandatoryEvaluators()),
                stringList(config, "humanReviewTags", properties.humanReviewTags()));
    }

    private long longValue(Map<String, Object> config, String key, long defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String string) {
            try {
                return Long.parseLong(string.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(key + " must be a number, got \"" + string + "\"");
            }
        }
        throw new IllegalArgumentException(key + " must be a number, got " + value.getClass().getSimpleName());
    }

    private double thresholdValue(Map<String, Object> config, String key, double defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        double parsed;
        if (value instanceof Number number) {
            parsed = number.doubleValue();
        } else if (value instanceof String string) {
            try {
                parsed = Double.parseDouble(string.trim());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(key + " must be a number, got \"" + string + "\"");
            }
        } else {
            throw new IllegalArgumentException(key + " must be a number, got " + value.getClass().getSimpleName());
        }
        if (parsed < 0.0 || parsed > 1.0) {
            throw new IllegalArgumentException(key + " must be within [0.0, 1.0], got " + parsed);
        }
        return parsed;
    }

    private CitationPolicy policyValue(Object value, String key, CitationPolicy defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof String string) {
            try {
                return CitationPolicy.valueOf(string.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(
                        key + " must be one of REQUIRED, OPTIONAL, FORBIDDEN, got \"" + string + "\"");
            }
        }
        throw new IllegalArgumentException(key + " must be a string, got " + value.getClass().getSimpleName());
    }

    private Map<String, CitationPolicy> policyMap(
            Map<String, Object> config, String key, Map<String, CitationPolicy> defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, CitationPolicy> result = new HashMap<>();
            map.forEach((tag, policy) ->
                    result.put(String.valueOf(tag), policyValue(policy, key + "." + tag, null)));
            return result;
        }
        throw new IllegalArgumentException(key + " must be an object of tag -> policy");
    }

    private List<String> stringList(Map<String, Object> config, String key, List<String> defaultValue) {
        Object value = config.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object element : list) {
                if (!(element instanceof String string) || string.isBlank()) {
                    throw new IllegalArgumentException(key + " must contain only non-blank strings");
                }
                result.add(string);
            }
            return result;
        }
        throw new IllegalArgumentException(key + " must be an array of strings");
    }
}
