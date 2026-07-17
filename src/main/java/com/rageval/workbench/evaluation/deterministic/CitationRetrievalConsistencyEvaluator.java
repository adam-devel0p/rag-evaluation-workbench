package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Spec §11.8: flags citations referencing documents absent from the retrieved chunks. This proves
 * retrieval <em>consistency</em> only — a citation to a retrieved document is not thereby shown to
 * actually support the answer (that is the Phase 5 groundedness/reference-correctness territory),
 * and every explanation this evaluator writes says so.
 */
@Component
public class CitationRetrievalConsistencyEvaluator implements EvaluationStrategy {

    public static final String KEY = "citation-retrieval-consistency";

    private static final String SCOPE_DISCLAIMER =
            "This checks retrieval consistency only; it does not prove that any citation supports the answer";

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
        if (!context.hasSuccessfulAnswer()) {
            return EvaluationApplicability.missingRequiredData(
                    "no successful invocation to evaluate (transport-level failure is judged by the transport evaluator)");
        }
        List<Citation> citations = citations(context);
        if (citations.isEmpty()) {
            return EvaluationApplicability.notApplicable("the execution returned no citations to check");
        }
        if (chunks(context).isEmpty()) {
            return EvaluationApplicability.missingRequiredData(
                    "citations are present but no retrieved chunks are exposed; consistency cannot be checked");
        }
        if (citations.stream().allMatch(citation -> citation.documentId() == null || citation.documentId().isBlank())) {
            return EvaluationApplicability.missingRequiredData(
                    "no citation carries a document ID; consistency with retrieved chunks cannot be checked");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        List<Citation> citations = citations(context);
        Set<String> retrievedDocumentIds = new HashSet<>();
        for (RetrievedChunk chunk : chunks(context)) {
            if (chunk.documentId() != null && !chunk.documentId().isBlank()) {
                retrievedDocumentIds.add(chunk.documentId());
            }
        }

        List<EvaluationEvidence> evidence = new ArrayList<>();
        int checkable = 0;
        int consistent = 0;
        List<String> unretrievedDocuments = new ArrayList<>();
        for (Citation citation : citations) {
            if (citation.documentId() == null || citation.documentId().isBlank()) {
                evidence.add(new EvaluationEvidence(
                        "citation-not-checkable",
                        "Citation without a document ID cannot be checked for retrieval consistency",
                        null,
                        null,
                        null,
                        citation.quotedText(),
                        Map.of("documentTitle", String.valueOf(citation.documentTitle()))));
                continue;
            }
            checkable++;
            if (retrievedDocumentIds.contains(citation.documentId())) {
                consistent++;
            } else {
                unretrievedDocuments.add(citation.documentId());
                evidence.add(new EvaluationEvidence(
                        "citation-to-unretrieved-document",
                        "Cited document \"" + citation.documentId()
                                + "\" does not appear in any retrieved chunk. " + SCOPE_DISCLAIMER,
                        citation.documentId(),
                        null,
                        null,
                        citation.quotedText(),
                        Map.of("retrievedDocumentIds", List.copyOf(retrievedDocumentIds))));
            }
        }

        boolean passed = unretrievedDocuments.isEmpty();
        double score = (double) consistent / checkable;
        return new EvaluationResult(
                passed,
                score,
                1.0,
                (passed
                                ? "All " + checkable + " checkable citation(s) reference retrieved documents. "
                                : "Citation(s) reference document(s) absent from the retrieved chunks: "
                                        + String.join(", ", unretrievedDocuments) + ". ")
                        + SCOPE_DISCLAIMER,
                evidence,
                false);
    }

    private List<Citation> citations(EvaluationContext context) {
        List<Citation> citations = context.execution().getCitations();
        return citations != null ? citations : List.of();
    }

    private List<RetrievedChunk> chunks(EvaluationContext context) {
        List<RetrievedChunk> chunks = context.execution().getRetrievedChunks();
        return chunks != null ? chunks : List.of();
    }
}
