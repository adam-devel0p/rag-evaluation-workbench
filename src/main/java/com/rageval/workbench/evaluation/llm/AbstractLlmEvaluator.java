package com.rageval.workbench.evaluation.llm;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.evaluation.domain.EvaluatorType;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared plumbing of the eight LLM evaluators (spec §13): the judge client, the hardened system
 * prompt, untrusted-content delimiting, the configurable context cap with never-silent
 * truncation, and evidence helpers. Judge failures ({@link JudgeException}) are deliberately not
 * caught here — they propagate to {@code EvaluationService}, which records a metric-status
 * {@code FAILED} result (an evaluator failure, never a chatbot failure).
 */
public abstract class AbstractLlmEvaluator implements EvaluationStrategy {

    /** Evidence type carrying the raw structured judge output as metadata (spec §12). */
    public static final String JUDGE_OUTPUT_EVIDENCE = "judge-output";

    /** Evidence type recording context truncation (spec §12: never truncate silently). */
    public static final String CONTEXT_TRUNCATION_EVIDENCE = "context-truncation";

    private static final JsonMapper JSON_MAPPER = JsonMapper.builder().build();

    protected final JudgeClient judge;

    protected AbstractLlmEvaluator(JudgeClient judge) {
        this.judge = judge;
    }

    @Override
    public EvaluatorType evaluatorType() {
        return EvaluatorType.LLM;
    }

    @Override
    public String evaluatorModel() {
        return judge.modelName();
    }

    /** The metric-specific rubric appended to the shared hardened preamble. */
    protected abstract String rubric();

    protected final JudgeRequest request(String userPrompt) {
        return new JudgeRequest(UntrustedContent.SYSTEM_PREAMBLE + "\nEvaluation rubric:\n" + rubric(), userPrompt);
    }

    protected static EvaluationApplicability requireSuccessfulAnswer(EvaluationContext context) {
        if (!context.hasSuccessfulAnswer()) {
            return EvaluationApplicability.missingRequiredData(
                    "the execution has no successful non-blank answer to judge (spec §11.1: answer quality is "
                            + "not evaluated when the invocation failed)");
        }
        return EvaluationApplicability.applicable();
    }

    protected final UntrustedContent.ContextBlock contextBlock(EvaluationContext context) {
        return UntrustedContent.contextBlock(
                context.execution().getRetrievedChunks(), context.settings().maximumContextCharacters());
    }

    /** The structured judge output persisted as evidence metadata — the raw result spec §12 keeps. */
    protected final EvaluationEvidence judgeOutputEvidence(Object output, String description) {
        Map<String, Object> metadata =
                JSON_MAPPER.convertValue(output, new TypeReference<LinkedHashMap<String, Object>>() {});
        return new EvaluationEvidence(JUDGE_OUTPUT_EVIDENCE, description, null, null, null, null, metadata);
    }

    /**
     * Truncation evidence for a capped context block, or null when nothing was cut. Callers must
     * also flag the result for human review — truncation may affect the verdict (spec §12/§14).
     */
    protected final EvaluationEvidence truncationEvidence(UntrustedContent.ContextBlock block) {
        if (!block.truncated()) {
            return null;
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("truncated", true);
        metadata.put("totalContentCharacters", block.totalContentCharacters());
        metadata.put("includedContentCharacters", block.includedContentCharacters());
        metadata.put("includedChunks", block.includedChunks());
        metadata.put("totalChunks", block.totalChunks());
        return new EvaluationEvidence(
                CONTEXT_TRUNCATION_EVIDENCE,
                "Retrieved context was truncated to the configured cap before judging ("
                        + block.includedContentCharacters() + " of " + block.totalContentCharacters()
                        + " characters, " + block.includedChunks() + " of " + block.totalChunks()
                        + " chunks included) — the verdict may be affected and requires human review",
                null,
                null,
                null,
                null,
                metadata);
    }
}
