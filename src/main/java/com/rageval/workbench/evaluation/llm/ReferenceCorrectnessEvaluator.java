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
 * Spec §13.3: compares the actual answer against the consultant-authored reference answer,
 * required facts, and forbidden claims. Retrieved context is deliberately NOT shown to the judge
 * unless a run explicitly opts in ({@code referenceCorrectnessUsesRetrievedContext}) — the
 * reference material is the authority for this metric, not whatever the target retrieved.
 *
 * <p>Not applicable when the reference answer is missing and the required facts do not provide
 * sufficient authority (no facts at all). When judging on required facts alone (no reference
 * answer), the result is flagged for human review per spec §14.
 */
@Component
public class ReferenceCorrectnessEvaluator extends AbstractLlmEvaluator {

    public static final String KEY = "llm-reference-correctness";

    private static final String PROMPT_VERSION = "1.0.0";

    public ReferenceCorrectnessEvaluator(JudgeClient judge) {
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
                Judge whether the untrusted chatbot answer is correct against the trusted \
                reference material (reference answer, required facts, forbidden claims). The \
                reference material is the only authority for this metric: do not use outside \
                knowledge, and do not excuse deviations because some other source might say \
                otherwise.
                - correctFacts: reference/required facts the answer states correctly.
                - missingFacts: reference/required facts the answer omits or gets wrong by omission.
                - incorrectClaims: statements in the answer that contradict the reference material \
                or assert a forbidden claim.
                - Harmless wording differences are not errors.
                - passed: true only if nothing material is incorrect and no forbidden claim appears.
                - score: fraction of the reference content the answer gets right, 0 to 1.
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
        boolean hasFacts = !testCase.getRequiredFacts().isEmpty();
        if (!hasReference && !hasFacts) {
            return EvaluationApplicability.notApplicable(
                    "the test case has no reference answer and its required facts do not provide sufficient "
                            + "authority to judge correctness (spec §13.3)");
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
            prompt.append("REFERENCE ANSWER (trusted rubric data):\n")
                    .append(testCase.getReferenceAnswer()).append("\n\n");
        } else {
            prompt.append("No reference answer exists; the required facts below are the only authority.\n\n");
        }
        appendFacts(prompt, "REQUIRED FACTS (trusted rubric data; the answer should state these):",
                testCase.getRequiredFacts());
        appendFacts(prompt, "FORBIDDEN CLAIMS (trusted rubric data; the answer must NOT state these):",
                testCase.getForbiddenClaims());
        prompt.append(UntrustedContent.answerBlock(context.execution().getAnswer()));

        UntrustedContent.ContextBlock block = null;
        if (context.settings().referenceCorrectnessUsesRetrievedContext()) {
            block = contextBlock(context);
            prompt.append("\n\nRetrieved context chunks (explicitly configured as additional input — the "
                            + "reference material above remains the authority):\n")
                    .append(block.text());
        }

        JudgeResponse<ReferenceCorrectnessOutput> response =
                judge.judge(request(prompt.toString()), ReferenceCorrectnessOutput.class);
        ReferenceCorrectnessOutput output = response.output();

        List<EvaluationEvidence> evidence = new ArrayList<>();
        evidence.add(judgeOutputEvidence(output, "Structured reference-correctness judge output"));
        if (block != null) {
            EvaluationEvidence truncation = truncationEvidence(block);
            if (truncation != null) {
                evidence.add(truncation);
            }
        }

        boolean missingReferenceReview = !hasReference;
        String explanation = "Judged against "
                + (hasReference ? "the reference answer and expected facts" : "required facts only (no reference "
                        + "answer exists — flagged for human review per spec §14)")
                + ": " + count(output.correctFacts()) + " correct, " + count(output.missingFacts())
                + " missing, " + count(output.incorrectClaims()) + " incorrect claim(s)"
                + (output.explanation() != null ? ". Judge: " + output.explanation() : "");
        return new EvaluationResult(
                output.passed(),
                clamp(output.score()),
                output.confidence(),
                explanation,
                evidence,
                missingReferenceReview || (block != null && block.truncated()));
    }

    private static void appendFacts(StringBuilder prompt, String heading, List<ExpectedFact> facts) {
        if (facts.isEmpty()) {
            return;
        }
        prompt.append(heading).append('\n');
        for (ExpectedFact fact : facts) {
            prompt.append("- ").append(fact.id()).append(": ").append(fact.value());
            if (fact.explanation() != null) {
                prompt.append(" (").append(fact.explanation()).append(')');
            }
            prompt.append('\n');
        }
        prompt.append('\n');
    }

    private static int count(List<String> list) {
        return list != null ? list.size() : 0;
    }

    static Double clamp(double score) {
        return Math.max(0.0, Math.min(1.0, score));
    }
}
