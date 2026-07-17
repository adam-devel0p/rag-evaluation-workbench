package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec §11.9: for {@code answerable=false} or {@code shouldRefuse=true} test cases, confirms the
 * system did not confidently fabricate an answer, using deterministic signals only for now: a
 * configured refusal or uncertainty phrase counts as a non-answer signal. A substantive answer
 * with no such signal is flagged as a likely fabrication with reduced confidence — the semantic
 * refusal evaluator (Phase 5) sharpens this judgment.
 */
@Component
public class AnswerabilityEvaluator implements EvaluationStrategy {

    public static final String KEY = "answerability";

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public EvaluationApplicability applicability(EvaluationContext context) {
        if (context.testCase().isAnswerable() && !context.testCase().isShouldRefuse()) {
            return EvaluationApplicability.notApplicable(
                    "the test case is answerable and requires no refusal — fabrication-on-unanswerable does not apply");
        }
        if (!context.hasSuccessfulAnswer()) {
            return EvaluationApplicability.missingRequiredData(
                    "no successful answer to evaluate; an error response is a transport failure, not a fabricated answer");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        String answer = context.execution().getAnswer();
        String lowered = answer.toLowerCase(Locale.ROOT);

        String signal = findPhrase(lowered, context.settings().refusalPhrases());
        String signalType = "refusal";
        if (signal == null) {
            signal = findPhrase(lowered, context.settings().uncertaintyPhrases());
            signalType = "uncertainty";
        }

        if (signal != null) {
            return new EvaluationResult(
                    true,
                    1.0,
                    0.7,
                    "A deterministic non-answer signal was found (" + signalType + " phrase \"" + signal
                            + "\"); the system did not confidently fabricate an answer. Whether the wording is "
                            + "fully appropriate is judged by the refusal/semantic evaluators",
                    List.of(new EvaluationEvidence(
                            "non-answer-signal",
                            signalType + " phrase \"" + signal + "\" found in the answer",
                            null,
                            null,
                            null,
                            signal,
                            Map.of("signalType", signalType))),
                    false);
        }

        return new EvaluationResult(
                false,
                0.0,
                0.5,
                "No deterministic non-answer signal (refusal or uncertainty phrase) was found although the test "
                        + "case is marked " + (context.testCase().isShouldRefuse() ? "refusal-required" : "unanswerable")
                        + " — the system appears to have produced a confident answer, which suggests fabrication. "
                        + "Phrase-based detection cannot rule out an unusually worded non-answer",
                List.of(new EvaluationEvidence(
                        "confident-answer-suspected",
                        "Answer of " + answer.length() + " characters contains no configured refusal or "
                                + "uncertainty phrase",
                        null,
                        null,
                        null,
                        null,
                        Map.of("answerLength", answer.length()))),
                true);
    }

    private String findPhrase(String loweredAnswer, List<String> phrases) {
        for (String phrase : phrases) {
            if (loweredAnswer.contains(phrase.toLowerCase(Locale.ROOT))) {
                return phrase;
            }
        }
        return null;
    }
}
