package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec §11.7: Hit@1/3/5, first relevant rank, and reciprocal rank of the test case's expected
 * document IDs within the retrieved chunks. Matching is exact document ID only — no fuzzy title
 * matching in deterministic evaluation. Chunk order uses the chunk's own {@code rank} when the
 * target provides one, else the list position.
 */
@Component
public class ExpectedDocumentEvaluator implements EvaluationStrategy {

    public static final String KEY = "expected-documents";

    /** Metadata keys on the retrieval-metrics evidence entry; read by aggregation and classification. */
    public static final String HIT_AT_1_KEY = "hitAt1";
    public static final String HIT_AT_3_KEY = "hitAt3";
    public static final String HIT_AT_5_KEY = "hitAt5";
    public static final String FIRST_RELEVANT_RANK_KEY = "firstRelevantRank";
    public static final String RECIPROCAL_RANK_KEY = "reciprocalRank";

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
        if (context.testCase().getExpectedDocumentIds().isEmpty()) {
            return EvaluationApplicability.notApplicable("the test case defines no expected documents");
        }
        if (!context.hasSuccessfulAnswer()) {
            return EvaluationApplicability.missingRequiredData(
                    "no successful invocation to evaluate (transport-level failure is judged by the transport evaluator)");
        }
        if (chunks(context).isEmpty()) {
            return EvaluationApplicability.missingRequiredData(
                    "no retrieved chunks are available; from black-box data this cannot be distinguished from the "
                            + "target simply not exposing retrieval");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        List<String> expected = context.testCase().getExpectedDocumentIds();
        List<RetrievedChunk> chunks = chunks(context);

        // Effective rank per chunk: the target's own rank when present, else the list position.
        Map<String, Integer> bestRankByDocument = new HashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (chunk.documentId() == null || chunk.documentId().isBlank()) {
                continue;
            }
            int rank = chunk.rank() != null ? chunk.rank() : i + 1;
            bestRankByDocument.merge(chunk.documentId(), rank, Math::min);
        }

        Integer firstRelevantRank = null;
        List<EvaluationEvidence> evidence = new ArrayList<>();
        for (String documentId : expected) {
            Integer rank = bestRankByDocument.get(documentId);
            if (rank != null && (firstRelevantRank == null || rank < firstRelevantRank)) {
                firstRelevantRank = rank;
            }
            evidence.add(new EvaluationEvidence(
                    "expected-document",
                    rank != null
                            ? "Expected document \"" + documentId + "\" retrieved at rank " + rank
                            : "Expected document \"" + documentId + "\" absent from the retrieved chunks",
                    documentId,
                    null,
                    rank,
                    null,
                    Map.of("retrieved", rank != null)));
        }

        boolean hit1 = firstRelevantRank != null && firstRelevantRank <= 1;
        boolean hit3 = firstRelevantRank != null && firstRelevantRank <= 3;
        boolean hit5 = firstRelevantRank != null && firstRelevantRank <= 5;
        double reciprocalRank = firstRelevantRank != null ? 1.0 / firstRelevantRank : 0.0;

        Map<String, Object> metrics = new HashMap<>();
        metrics.put(HIT_AT_1_KEY, hit1);
        metrics.put(HIT_AT_3_KEY, hit3);
        metrics.put(HIT_AT_5_KEY, hit5);
        metrics.put(FIRST_RELEVANT_RANK_KEY, firstRelevantRank);
        metrics.put(RECIPROCAL_RANK_KEY, reciprocalRank);
        metrics.put("expectedDocumentCount", expected.size());
        metrics.put("retrievedDocumentIds", List.copyOf(bestRankByDocument.keySet()));
        evidence.add(new EvaluationEvidence(
                "retrieval-metrics",
                "Hit@1=" + hit1 + ", Hit@3=" + hit3 + ", Hit@5=" + hit5
                        + ", first relevant rank=" + (firstRelevantRank != null ? firstRelevantRank : "none")
                        + ", reciprocal rank=" + reciprocalRank,
                null,
                null,
                null,
                null,
                metrics));

        boolean passed = firstRelevantRank != null;
        return new EvaluationResult(
                passed,
                reciprocalRank,
                1.0,
                passed
                        ? "An expected document was retrieved (first relevant rank " + firstRelevantRank
                                + "; Hit@1=" + hit1 + ", Hit@3=" + hit3 + ", Hit@5=" + hit5
                                + "; score is the reciprocal rank)"
                        : "None of the " + expected.size()
                                + " expected document(s) appear in the retrieved chunks (score is the reciprocal rank, 0.0)",
                evidence,
                false);
    }

    private List<RetrievedChunk> chunks(EvaluationContext context) {
        List<RetrievedChunk> chunks = context.execution().getRetrievedChunks();
        return chunks != null ? chunks : List.of();
    }
}
