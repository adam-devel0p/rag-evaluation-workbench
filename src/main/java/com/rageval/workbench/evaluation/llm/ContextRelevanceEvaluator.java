package com.rageval.workbench.evaluation.llm;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec §13.6: judges each retrieved chunk against the question. The judge decides per chunk; the
 * ratios the spec asks for (relevant/retrieved, relevant-in-top-3, irrelevant top-ranked) are
 * computed deterministically in Java from those decisions and the persisted chunk ranks. Skipped
 * entirely when the execution exposes no chunks.
 */
@Component
public class ContextRelevanceEvaluator extends AbstractLlmEvaluator {

    public static final String KEY = "llm-context-relevance";

    /** Evidence type of one chunk's relevance decision. */
    public static final String CHUNK_DECISION_EVIDENCE = "chunk-relevance";

    public static final String RELEVANT_RATIO_METADATA_KEY = "relevantRatio";
    public static final String RELEVANT_IN_TOP_THREE_METADATA_KEY = "relevantInTopThree";
    public static final String IRRELEVANT_TOP_RANKED_METADATA_KEY = "irrelevantTopRanked";

    private static final String PROMPT_VERSION = "1.0.0";

    public ContextRelevanceEvaluator(JudgeClient judge) {
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
                For EACH retrieved context chunk, judge whether it is relevant to answering the \
                question — would a competent person consult this chunk to answer it?
                - Judge each chunk on its own; do not let one chunk's relevance influence another's.
                - Do not judge the chatbot answer here; only the chunks against the question.
                - Return one decision per chunk, using the exact chunk id from its BEGIN marker.
                - confidence: how certain you are overall, 0 to 1.
                """;
    }

    @Override
    public EvaluationApplicability applicability(EvaluationContext context) {
        List<RetrievedChunk> chunks = context.execution().getRetrievedChunks();
        if (chunks == null || chunks.isEmpty()) {
            return EvaluationApplicability.missingRequiredData(
                    "the execution exposes no retrieved chunks; spec §13.6 says this evaluator must not run "
                            + "without chunks (from black-box data this cannot be distinguished from the target "
                            + "not exposing retrieval)");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        UntrustedContent.ContextBlock block = contextBlock(context);
        String userPrompt = "Question the chatbot was asked (dataset input):\n"
                + context.testCase().getQuestion() + "\n\n"
                + "Retrieved context chunks:\n" + block.text();
        JudgeResponse<ContextRelevanceOutput> response =
                judge.judge(request(userPrompt), ContextRelevanceOutput.class);
        List<ChunkRelevanceDecision> decisions =
                response.output().chunks() != null ? response.output().chunks() : List.of();

        // Only chunks that were actually shown to the judge count toward the ratios.
        List<RetrievedChunk> judgedChunks = new ArrayList<>();
        for (RetrievedChunk chunk : context.execution().getRetrievedChunks()) {
            if (block.includedChunkIds().contains(chunk.chunkId() != null ? chunk.chunkId() : "unknown")) {
                judgedChunks.add(chunk);
            }
        }
        Map<String, Boolean> relevanceByChunkId = new HashMap<>();
        for (ChunkRelevanceDecision decision : decisions) {
            relevanceByChunkId.put(decision.chunkId(), decision.relevant());
        }

        int relevant = 0;
        int relevantInTopThree = 0;
        boolean irrelevantTopRanked = false;
        List<EvaluationEvidence> evidence = new ArrayList<>();
        evidence.add(judgeOutputEvidence(response.output(), "Structured context-relevance judge output"));
        EvaluationEvidence truncation = truncationEvidence(block);
        if (truncation != null) {
            evidence.add(truncation);
        }
        for (int position = 0; position < judgedChunks.size(); position++) {
            RetrievedChunk chunk = judgedChunks.get(position);
            String chunkId = chunk.chunkId() != null ? chunk.chunkId() : "unknown";
            Boolean chunkRelevant = relevanceByChunkId.get(chunkId);
            int rank = chunk.rank() != null ? chunk.rank() : position + 1;
            if (Boolean.TRUE.equals(chunkRelevant)) {
                relevant++;
                if (rank <= 3) {
                    relevantInTopThree++;
                }
            } else if (rank == 1) {
                irrelevantTopRanked = true;
            }
            ChunkRelevanceDecision decision = decisions.stream()
                    .filter(candidate -> chunkId.equals(candidate.chunkId()))
                    .findFirst()
                    .orElse(null);
            evidence.add(new EvaluationEvidence(
                    CHUNK_DECISION_EVIDENCE,
                    "Chunk " + chunkId + " (rank " + rank + "): "
                            + (chunkRelevant == null
                                    ? "no decision returned by the judge"
                                    : (chunkRelevant ? "relevant" : "irrelevant"))
                            + (decision != null && decision.explanation() != null
                                    ? " — " + decision.explanation()
                                    : ""),
                    chunk.documentId(),
                    chunkId,
                    chunk.rank(),
                    null,
                    Map.of("relevant", chunkRelevant != null ? chunkRelevant.toString() : "undecided")));
        }

        double relevantRatio = judgedChunks.isEmpty() ? 0.0 : (double) relevant / judgedChunks.size();
        boolean passed = relevant > 0 && !irrelevantTopRanked;
        Map<String, Object> ratioMetadata = new LinkedHashMap<>();
        ratioMetadata.put(RELEVANT_RATIO_METADATA_KEY, relevantRatio);
        ratioMetadata.put(RELEVANT_IN_TOP_THREE_METADATA_KEY, relevantInTopThree);
        ratioMetadata.put(IRRELEVANT_TOP_RANKED_METADATA_KEY, irrelevantTopRanked);
        evidence.add(new EvaluationEvidence(
                "retrieval-relevance-metrics",
                "Derived deterministically from the per-chunk decisions",
                null,
                null,
                null,
                null,
                ratioMetadata));

        String explanation = relevant + " of " + judgedChunks.size() + " judged chunk(s) relevant to the "
                + "question (" + relevantInTopThree + " in the top 3 ranks; top-ranked chunk "
                + (irrelevantTopRanked ? "irrelevant" : "relevant or undecided") + "). This judges retrieval "
                + "quality only, not the answer"
                + (response.output().explanation() != null ? ". Judge: " + response.output().explanation() : "");
        return new EvaluationResult(
                passed,
                relevantRatio,
                response.output().confidence(),
                explanation,
                evidence,
                block.truncated() || decisions.isEmpty());
    }
}
