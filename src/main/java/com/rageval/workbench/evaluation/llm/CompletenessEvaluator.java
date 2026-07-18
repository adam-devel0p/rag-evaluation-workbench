package com.rageval.workbench.evaluation.llm;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Spec §13.4: whether all material parts of the expected answer are covered. Must not penalize
 * harmless wording differences; must identify omitted required content. The expected answer is
 * the reference answer plus the mandatory required facts; without either there is nothing to be
 * complete against.
 */
@Component
public class CompletenessEvaluator extends AbstractLlmEvaluator {

    public static final String KEY = "llm-completeness";

    private static final String PROMPT_VERSION = "1.0.0";

    public CompletenessEvaluator(JudgeClient judge) {
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
                Judge whether the untrusted chatbot answer covers all material parts of the \
                expected answer (the trusted reference answer and required facts).
                - Completeness only: do NOT judge factual correctness here.
                - Harmless wording differences, reordering, or paraphrasing are NOT omissions.
                - omittedRequiredContent: material parts of the expected answer that are absent.
                - coveredPoints: material parts that are present.
                - passed: true only if no material part is omitted.
                - score: fraction of the material expected content that is covered, 0 to 1.
                - confidence: how certain you are, 0 to 1.
                """;
    }

    @Override
    public EvaluationApplicability applicability(EvaluationContext context) {
        EvaluationApplicability answer = requireSuccessfulAnswer(context);
        if (!answer.isApplicable()) {
            return answer;
        }
        EvaluationTestCase testCase = context.testCase();
        boolean hasReference = testCase.getReferenceAnswer() != null && !testCase.getReferenceAnswer().isBlank();
        if (!hasReference && testCase.getRequiredFacts().isEmpty()) {
            return EvaluationApplicability.notApplicable(
                    "the test case defines no reference answer and no required facts, so there is no expected "
                            + "content to judge completeness against");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        EvaluationTestCase testCase = context.testCase();
        boolean hasReference = testCase.getReferenceAnswer() != null && !testCase.getReferenceAnswer().isBlank();

        StringBuilder prompt = new StringBuilder();
        prompt.append("Question the chatbot was asked (dataset input):\n")
                .append(testCase.getQuestion()).append("\n\n");
        if (hasReference) {
            prompt.append("EXPECTED ANSWER (trusted rubric data):\n")
                    .append(testCase.getReferenceAnswer()).append("\n\n");
        }
        List<ExpectedFact> requiredFacts = testCase.getRequiredFacts();
        if (!requiredFacts.isEmpty()) {
            prompt.append("REQUIRED CONTENT (trusted rubric data):\n");
            for (ExpectedFact fact : requiredFacts) {
                prompt.append("- ").append(fact.id()).append(": ").append(fact.value()).append('\n');
            }
            prompt.append('\n');
        }
        prompt.append(UntrustedContent.answerBlock(context.execution().getAnswer()));

        JudgeResponse<CompletenessOutput> response =
                judge.judge(request(prompt.toString()), CompletenessOutput.class);
        CompletenessOutput output = response.output();

        List<EvaluationEvidence> evidence = new ArrayList<>();
        evidence.add(judgeOutputEvidence(output, "Structured completeness judge output"));

        int omitted = output.omittedRequiredContent() != null ? output.omittedRequiredContent().size() : 0;
        int covered = output.coveredPoints() != null ? output.coveredPoints().size() : 0;
        String explanation = covered + " material part(s) of the expected answer covered, " + omitted
                + " omitted. Completeness does not judge correctness, and harmless wording differences are "
                + "not counted as omissions"
                + (!hasReference
                        ? ". Judged against required facts only — no reference answer exists (flagged for "
                                + "human review per spec §14)"
                        : "")
                + (output.explanation() != null ? ". Judge: " + output.explanation() : "");
        return new EvaluationResult(
                output.passed(),
                ReferenceCorrectnessEvaluator.clamp(output.score()),
                output.confidence(),
                explanation,
                evidence,
                !hasReference);
    }
}
