package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.dataset.domain.FactMatchType;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic matching of one {@link ExpectedFact} against an answer. Supports the three
 * non-semantic modes; {@code SEMANTIC} facts are routed to an LLM evaluator (Phase 5) and must be
 * filtered out by callers before reaching this class.
 */
final class FactMatcher {

    /** The exact region of the answer that matched, as evidence for the verdict. */
    record FactMatch(String matchedText) {
    }

    private FactMatcher() {
    }

    static boolean isDeterministic(ExpectedFact fact) {
        return fact.matchType() != FactMatchType.SEMANTIC;
    }

    static Optional<FactMatch> match(ExpectedFact fact, String answer) {
        if (answer == null || fact.value() == null) {
            return Optional.empty();
        }
        return switch (fact.matchType()) {
            case EXACT_TEXT -> {
                int index = answer.indexOf(fact.value());
                yield index >= 0
                        ? Optional.of(new FactMatch(answer.substring(index, index + fact.value().length())))
                        : Optional.empty();
            }
            case CASE_INSENSITIVE_TEXT -> {
                // regionMatches avoids the index drift a locale-sensitive toLowerCase could
                // introduce between the compared strings.
                String value = fact.value();
                Optional<FactMatch> found = Optional.empty();
                for (int i = 0; i <= answer.length() - value.length(); i++) {
                    if (answer.regionMatches(true, i, value, 0, value.length())) {
                        found = Optional.of(new FactMatch(answer.substring(i, i + value.length())));
                        break;
                    }
                }
                yield found;
            }
            case REGEX -> {
                // Patterns are validated at dataset import time; compilation here cannot fail for
                // imported data.
                Matcher matcher = Pattern.compile(fact.value()).matcher(answer);
                yield matcher.find() ? Optional.of(new FactMatch(matcher.group())) : Optional.empty();
            }
            case SEMANTIC -> throw new IllegalArgumentException(
                    "SEMANTIC facts are evaluated by an LLM evaluator, not deterministically");
        };
    }
}
