package com.rageval.workbench.evaluation.llm;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Spec §13.2, built as a custom structured evaluator per the Phase 0 API-surface findings: Spring
 * AI's {@code FactCheckingEvaluator} answers with a single pass/score/feedback tuple and cannot
 * carry the per-claim verdicts with supporting/contradicting chunk IDs this metric requires, so
 * it is not wrapped (see docs/architecture.md, Phase 5 notes).
 *
 * <p>The judge must use only the supplied retrieved context. Pass/fail and the overall score are
 * derived deterministically in Java from the per-claim verdicts — the judge supplies evidence,
 * not an unexplained number. A grounded answer can still be objectively wrong when its source
 * document is outdated; that caveat is part of every explanation.
 */
@Component
public class GroundednessEvaluator extends AbstractLlmEvaluator {

    public static final String KEY = "llm-groundedness";

    /** Evidence type of one claim's grounding decision. */
    public static final String CLAIM_GROUNDING_EVIDENCE = "claim-grounding";

    public static final String VERDICT_METADATA_KEY = "verdict";
    public static final String SUPPORTING_CHUNKS_METADATA_KEY = "supportingChunkIds";
    public static final String CONTRADICTING_CHUNKS_METADATA_KEY = "contradictingChunkIds";

    private static final String PROMPT_VERSION = "1.0.0";

    public GroundednessEvaluator(JudgeClient judge) {
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
                For each claim from the chatbot answer, decide whether it is supported by the \
                supplied retrieved context chunks. Use ONLY the supplied context — outside \
                knowledge is forbidden for this metric, and a claim that is true in the real \
                world but absent from the context is NOT_IN_CONTEXT.
                Per-claim verdicts:
                - SUPPORTED: the context states the claim.
                - PARTIALLY_SUPPORTED: the context states part of the claim.
                - CONTRADICTED: the context states the opposite.
                - NOT_IN_CONTEXT: the context says nothing about the claim.
                For every claim, list the chunk ids (from the chunk BEGIN markers) that support or \
                contradict it where possible, and explain the verdict briefly.
                Set overallScore to the fraction of claims that are SUPPORTED, passed to whether \
                every claim is SUPPORTED, and confidence to how certain you are overall (0 to 1).
                """;
    }

    @Override
    public EvaluationApplicability applicability(EvaluationContext context) {
        EvaluationApplicability answer = requireSuccessfulAnswer(context);
        if (!answer.isApplicable()) {
            return answer;
        }
        List<?> chunks = context.execution().getRetrievedChunks();
        if (chunks == null || chunks.isEmpty()) {
            return EvaluationApplicability.missingRequiredData(
                    "no retrieved chunks are available to ground the answer against; from black-box data this "
                            + "cannot be distinguished from the target simply not exposing retrieval");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        UntrustedContent.ContextBlock block = contextBlock(context);
        String claimsSection = previouslyExtractedClaims(context)
                .map(claims -> "Claims to verify (previously extracted from the answer):\n" + claims)
                .orElse("""
                        No pre-extracted claims are available. First split the untrusted answer into \
                        independently verifiable factual claims yourself (claimId c1, c2, ... in answer \
                        order), then ground each one.""");

        String userPrompt = "Question the chatbot was asked (dataset input):\n"
                + context.testCase().getQuestion() + "\n\n"
                + claimsSection + "\n\n"
                + UntrustedContent.answerBlock(context.execution().getAnswer()) + "\n\n"
                + "Retrieved context chunks:\n" + block.text();
        JudgeResponse<GroundednessOutput> response = judge.judge(request(userPrompt), GroundednessOutput.class);
        List<ClaimGroundingDecision> decisions =
                response.output().claims() != null ? response.output().claims() : List.of();

        Map<GroundingVerdict, Integer> verdictCounts = new EnumMap<>(GroundingVerdict.class);
        List<EvaluationEvidence> evidence = new ArrayList<>();
        evidence.add(judgeOutputEvidence(response.output(), "Structured groundedness judge output"));
        EvaluationEvidence truncation = truncationEvidence(block);
        if (truncation != null) {
            evidence.add(truncation);
        }
        for (ClaimGroundingDecision decision : decisions) {
            GroundingVerdict verdict = decision.verdict();
            if (verdict != null) {
                verdictCounts.merge(verdict, 1, Integer::sum);
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put(VERDICT_METADATA_KEY, verdict != null ? verdict.name() : "MISSING");
            metadata.put(SUPPORTING_CHUNKS_METADATA_KEY, listOrEmpty(decision.supportingChunkIds()));
            metadata.put(CONTRADICTING_CHUNKS_METADATA_KEY, listOrEmpty(decision.contradictingChunkIds()));
            String firstSupporting = decision.supportingChunkIds() != null
                            && !decision.supportingChunkIds().isEmpty()
                    ? decision.supportingChunkIds().get(0)
                    : null;
            evidence.add(new EvaluationEvidence(
                    CLAIM_GROUNDING_EVIDENCE,
                    "Claim " + decision.claimId() + ": " + (verdict != null ? verdict : "no verdict")
                            + (decision.explanation() != null ? " — " + decision.explanation() : ""),
                    null,
                    firstSupporting,
                    null,
                    decision.claimText(),
                    metadata));
        }

        // Derived in Java, not taken from the judge: pass iff no claim is CONTRADICTED or
        // NOT_IN_CONTEXT; the score is the supported fraction with partial support at half weight.
        int total = decisions.size();
        int supported = verdictCounts.getOrDefault(GroundingVerdict.SUPPORTED, 0);
        int partiallySupported = verdictCounts.getOrDefault(GroundingVerdict.PARTIALLY_SUPPORTED, 0);
        int contradicted = verdictCounts.getOrDefault(GroundingVerdict.CONTRADICTED, 0);
        int notInContext = verdictCounts.getOrDefault(GroundingVerdict.NOT_IN_CONTEXT, 0);
        // No claim decisions means nothing ungrounded was asserted (e.g. a pure refusal): a
        // vacuous pass flagged for review — never a chatbot failure manufactured from absence.
        boolean passed = contradicted == 0 && notInContext == 0;
        Double score = total > 0 ? (supported + 0.5 * partiallySupported) / total : null;

        String explanation = (total == 0
                        ? "The judge identified no independently verifiable claims, so there is nothing to "
                                + "ground — vacuously passed and flagged for human review"
                        : supported + " of " + total + " claim(s) fully supported by the retrieved context, "
                                + partiallySupported + " partially supported, " + contradicted + " contradicted, "
                                + notInContext + " not in context")
                + ". Groundedness proves consistency with the retrieved context only — a grounded answer can "
                + "still be wrong if the source document is outdated"
                + (response.output().explanation() != null
                        ? ". Judge: " + response.output().explanation()
                        : "");
        boolean requiresReview = block.truncated() || total == 0;
        return new EvaluationResult(
                passed, score, response.output().confidence(), explanation, evidence, requiresReview);
    }

    private Optional<String> previouslyExtractedClaims(EvaluationContext context) {
        Optional<EvaluationMetricResult> extraction = context.completedResult(ClaimExtractionEvaluator.KEY);
        if (extraction.isEmpty()) {
            return Optional.empty();
        }
        StringBuilder claims = new StringBuilder();
        for (EvaluationEvidence entry : extraction.get().getEvidence()) {
            if (ClaimExtractionEvaluator.CLAIM_EVIDENCE.equals(entry.type())) {
                Object claimId = entry.metadata() != null
                        ? entry.metadata().get(ClaimExtractionEvaluator.CLAIM_ID_METADATA_KEY)
                        : null;
                claims.append("- ").append(claimId != null ? claimId : "?").append(": ")
                        .append(entry.quotedText()).append('\n');
            }
        }
        return claims.isEmpty() ? Optional.empty() : Optional.of(claims.toString());
    }

    private static List<String> listOrEmpty(List<String> value) {
        return value != null ? value : List.of();
    }
}
