package com.rageval.workbench.evaluation.llm;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec §13.1: splits the chatbot answer into independently verifiable factual claims. This is an
 * enabling metric, not a pass/fail judgment — its extracted claims are persisted as evidence and
 * consumed by the groundedness evaluator (which runs later in key order:
 * {@code llm-claim-extraction} &lt; {@code llm-groundedness}).
 */
@Component
public class ClaimExtractionEvaluator extends AbstractLlmEvaluator {

    public static final String KEY = "llm-claim-extraction";

    /** Evidence type of one extracted claim; metadata carries claimId and claimType. */
    public static final String CLAIM_EVIDENCE = "extracted-claim";

    public static final String CLAIM_ID_METADATA_KEY = "claimId";
    public static final String CLAIM_TYPE_METADATA_KEY = "claimType";

    private static final String PROMPT_VERSION = "1.0.0";

    public ClaimExtractionEvaluator(JudgeClient judge) {
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
                Split the chatbot answer into independently verifiable factual claims.
                - Each claim must be a single verifiable assertion, quoted or minimally rephrased.
                - Do not treat greetings, pure opinions, or explicit uncertainty statements as \
                factual claims unless they contain a factual assertion.
                - claimId must be c1, c2, c3, ... in answer order.
                - claimType is one of FACT, INSTRUCTION, NUMBER, VERSION, OWNERSHIP, POLICY, OTHER.
                - An answer with no verifiable factual content yields an empty claims list.
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
        JudgeResponse<ClaimExtractionOutput> response =
                judge.judge(request(userPrompt), ClaimExtractionOutput.class);
        List<ExtractedClaim> claims =
                response.output().claims() != null ? response.output().claims() : List.of();

        List<EvaluationEvidence> evidence = new ArrayList<>();
        evidence.add(judgeOutputEvidence(response.output(), "Structured claim-extraction judge output"));
        for (ExtractedClaim claim : claims) {
            evidence.add(new EvaluationEvidence(
                    CLAIM_EVIDENCE,
                    "Extracted claim " + claim.claimId(),
                    null,
                    null,
                    null,
                    claim.text(),
                    Map.of(
                            CLAIM_ID_METADATA_KEY, String.valueOf(claim.claimId()),
                            CLAIM_TYPE_METADATA_KEY, String.valueOf(claim.claimType()))));
        }
        return new EvaluationResult(
                true,
                null,
                null,
                "Extracted " + claims.size() + " independently verifiable claim(s) from the answer. This "
                        + "metric carries no pass/fail judgment of the chatbot; the claims feed the "
                        + "groundedness evaluator",
                evidence,
                false);
    }
}
