package com.rageval.workbench.evaluation.llm;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Spec §13.5: whether the answer directly addresses the question. Built as a custom structured
 * evaluator rather than wrapping Spring AI's {@code RelevancyEvaluator}: the built-in prompt
 * cannot carry our untrusted-content hardening (spec §12) and its response is a single
 * pass/score/feedback tuple without the structured evidence and version metadata we persist —
 * see docs/architecture.md, Phase 5 notes. Relevance never implies correctness.
 */
@Component
public class AnswerRelevanceEvaluator extends AbstractLlmEvaluator {

    public static final String KEY = "llm-answer-relevance";

    private static final String PROMPT_VERSION = "1.0.0";

    public AnswerRelevanceEvaluator(JudgeClient judge) {
        super(judge);
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public String version() {
        return "1.0.0";
    }

    @Override
    public String promptVersion() {
        return PROMPT_VERSION;
    }

    @Override
    protected String rubric() {
        return """
                Judge whether the untrusted chatbot answer directly addresses the question that \
                was asked.
                - Relevance only: an answer can be relevant and still factually wrong. Do NOT \
                judge correctness.
                - A justified refusal or honest "I do not know" IS a direct response to the \
                question; do not mark it irrelevant merely for not containing the answer.
                - unaddressedParts: parts of the question the answer ignores.
                - relevant/passed: true if the answer engages with what was actually asked.
                - score: how completely the answer addresses the question, 0 to 1.
                - confidence: how certain you are, 0 to 1.
                """;
    }

    @Override
    public EvaluationApplicability applicability(EvaluationContext context) {
        return requireSuccessfulAnswer(context);
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        String userPrompt = "Question the chatbot was asked (dataset input):\n"
                + context.testCase().getQuestion() + "\n\n"
                + UntrustedContent.answerBlock(context.execution().getAnswer());
        JudgeResponse<AnswerRelevanceOutput> response =
                judge.judge(request(userPrompt), AnswerRelevanceOutput.class);
        AnswerRelevanceOutput output = response.output();

        int unaddressed = output.unaddressedParts() != null ? output.unaddressedParts().size() : 0;
        String explanation = (output.relevant()
                        ? "The answer directly addresses the question"
                        : "The answer does not directly address the question")
                + (unaddressed > 0 ? " (" + unaddressed + " part(s) of the question unaddressed)" : "")
                + ". Relevance says nothing about factual correctness"
                + (output.explanation() != null ? ". Judge: " + output.explanation() : "");
        return new EvaluationResult(
                output.relevant(),
                ReferenceCorrectnessEvaluator.clamp(output.score()),
                output.confidence(),
                explanation,
                List.of(judgeOutputEvidence(output, "Structured answer-relevance judge output")),
                false);
    }
}
