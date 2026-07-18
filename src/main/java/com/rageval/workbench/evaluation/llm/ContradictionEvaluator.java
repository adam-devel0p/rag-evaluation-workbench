package com.rageval.workbench.evaluation.llm;

import com.rageval.workbench.dataset.domain.EvaluationTestCase;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec §13.8: detects explicit pairs of conflicting statements across four relations —
 * answer vs reference, answer vs context, context vs context, answer vs itself. Never decides
 * which conflicting document is current (black-box data cannot prove it); contradictory context
 * documents are a spec §14 human-review trigger carried by this result.
 */
@Component
public class ContradictionEvaluator extends AbstractLlmEvaluator {

    public static final String KEY = "llm-contradiction";

    /** Evidence type of one conflicting-statement pair. */
    public static final String CONTRADICTION_EVIDENCE = "contradiction-pair";

    /** Metadata key on the judge-output evidence listing the detected {@link ContradictionType}s. */
    public static final String TYPES_METADATA_KEY = "contradictionTypes";

    private static final String PROMPT_VERSION = "1.0.0";

    public ContradictionEvaluator(JudgeClient judge) {
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
                Detect direct contradictions — pairs of statements that cannot both be true.
                Check exactly these relations (skip any whose material is absent):
                - ANSWER_VS_REFERENCE: the chatbot answer contradicts the reference answer.
                - ANSWER_VS_CONTEXT: the chatbot answer contradicts a retrieved context chunk.
                - CONTEXT_VS_CONTEXT: two retrieved context chunks contradict each other.
                - ANSWER_VS_SELF: the chatbot answer contradicts itself.
                For each contradiction return the two conflicting statements verbatim, their \
                sources ("answer", "reference answer", or the chunk id), and a brief explanation.
                Different level of detail, paraphrase, or omission is NOT a contradiction — only \
                statements that cannot both be true. Never decide which conflicting source is \
                correct or more current.
                - confidence: how certain you are, 0 to 1.
                """;
    }

    @Override
    public EvaluationApplicability applicability(EvaluationContext context) {
        return requireSuccessfulAnswer(context);
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        EvaluationTestCase testCase = context.testCase();
        StringBuilder prompt = new StringBuilder();
        prompt.append("Question the chatbot was asked (dataset input):\n")
                .append(testCase.getQuestion()).append("\n\n");
        if (testCase.getReferenceAnswer() != null && !testCase.getReferenceAnswer().isBlank()) {
            prompt.append("REFERENCE ANSWER (trusted rubric data):\n")
                    .append(testCase.getReferenceAnswer()).append("\n\n");
        }
        prompt.append(UntrustedContent.answerBlock(context.execution().getAnswer()));
        UntrustedContent.ContextBlock block = null;
        List<?> chunks = context.execution().getRetrievedChunks();
        if (chunks != null && !chunks.isEmpty()) {
            block = contextBlock(context);
            prompt.append("\n\nRetrieved context chunks:\n").append(block.text());
        }

        JudgeResponse<ContradictionOutput> response =
                judge.judge(request(prompt.toString()), ContradictionOutput.class);
        List<ContradictionPair> pairs = response.output().contradictions() != null
                ? response.output().contradictions()
                : List.of();

        List<String> types = pairs.stream()
                .map(pair -> pair.type() != null ? pair.type().name() : "UNSPECIFIED")
                .distinct()
                .toList();
        boolean contextContradictsItself = types.contains(ContradictionType.CONTEXT_VS_CONTEXT.name());

        List<EvaluationEvidence> evidence = new ArrayList<>();
        Map<String, Object> outputMetadata = new LinkedHashMap<>();
        outputMetadata.put(TYPES_METADATA_KEY, types);
        evidence.add(new EvaluationEvidence(
                JUDGE_OUTPUT_EVIDENCE,
                "Structured contradiction judge output",
                null,
                null,
                null,
                null,
                outputMetadata));
        EvaluationEvidence truncation = block != null ? truncationEvidence(block) : null;
        if (truncation != null) {
            evidence.add(truncation);
        }
        for (ContradictionPair pair : pairs) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("type", pair.type() != null ? pair.type().name() : "UNSPECIFIED");
            metadata.put("sourceA", String.valueOf(pair.sourceA()));
            metadata.put("sourceB", String.valueOf(pair.sourceB()));
            evidence.add(new EvaluationEvidence(
                    CONTRADICTION_EVIDENCE,
                    (pair.type() != null ? pair.type() + ": " : "")
                            + "\"" + pair.statementA() + "\" (" + pair.sourceA() + ") vs \""
                            + pair.statementB() + "\" (" + pair.sourceB() + ")"
                            + (pair.explanation() != null ? " — " + pair.explanation() : ""),
                    null,
                    null,
                    null,
                    pair.statementA(),
                    metadata));
        }

        boolean passed = pairs.isEmpty();
        String explanation = (passed
                        ? "No direct contradictions detected across the checked relations"
                        : pairs.size() + " contradiction(s) detected (" + String.join(", ", types) + ")")
                + (contextContradictsItself
                        ? ". Retrieved documents contradict each other — which document is authoritative "
                                + "cannot be decided from black-box data and requires human review"
                        : "")
                + (response.output().explanation() != null ? ". Judge: " + response.output().explanation() : "");
        return new EvaluationResult(
                passed,
                passed ? 1.0 : 0.0,
                response.output().confidence(),
                explanation,
                evidence,
                contextContradictsItself || (block != null && block.truncated()));
    }
}
