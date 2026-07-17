package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.evaluation.domain.CitationPolicy;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import com.rageval.workbench.run.domain.Citation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Spec §11.6: citations required / optional / forbidden, resolved per test case from the run's
 * tag-based overrides falling back to the run or application default. When citations are present
 * they must be usable: each needs a document ID or URL, no blank values, no duplicates.
 */
@Component
public class CitationPresenceEvaluator implements EvaluationStrategy {

    public static final String KEY = "citation-presence";

    /** Metadata keys on the summary evidence entry, read by the failure classification rules. */
    public static final String POLICY_METADATA_KEY = "citationPolicy";
    public static final String CITATION_COUNT_METADATA_KEY = "citationCount";

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
                    "no successful answer to evaluate (transport-level failure is judged by the transport evaluator)");
        }
        CitationPolicy policy = context.settings().citationPolicyFor(context.testCase().getTags());
        List<Citation> citations = citations(context);
        if (policy == CitationPolicy.OPTIONAL && citations.isEmpty()) {
            return EvaluationApplicability.notApplicable(
                    "citations are optional for this test case and none were returned — nothing to assess");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        CitationPolicy policy = context.settings().citationPolicyFor(context.testCase().getTags());
        List<Citation> citations = citations(context);
        List<EvaluationEvidence> evidence = new ArrayList<>();
        List<String> violations = new ArrayList<>();

        if (policy == CitationPolicy.FORBIDDEN && !citations.isEmpty()) {
            violations.add(citations.size() + " citation(s) returned although citations are forbidden");
            for (Citation citation : citations) {
                evidence.add(citationEvidence("forbidden-citation-present",
                        "Citation present although forbidden", citation));
            }
        }
        if (policy == CitationPolicy.REQUIRED && citations.isEmpty()) {
            violations.add("no citation returned although citations are required");
        }

        if (policy != CitationPolicy.FORBIDDEN) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < citations.size(); i++) {
                Citation citation = citations.get(i);
                boolean hasDocumentId = citation.documentId() != null && !citation.documentId().isBlank();
                boolean hasUrl = citation.url() != null && !citation.url().isBlank();
                if (!hasDocumentId && !hasUrl) {
                    violations.add("citation " + (i + 1) + " has neither a document ID nor a URL");
                    evidence.add(citationEvidence("citation-without-identifier",
                            "Citation " + (i + 1) + " carries neither a document ID nor a URL", citation));
                }
                String identity = (hasDocumentId ? citation.documentId() : String.valueOf(citation.url()))
                        + "|" + citation.section() + "|" + citation.quotedText();
                if (!seen.add(identity)) {
                    violations.add("citation " + (i + 1) + " duplicates an earlier citation");
                    evidence.add(citationEvidence("duplicate-citation",
                            "Citation " + (i + 1) + " duplicates an earlier citation", citation));
                }
            }
        }

        boolean passed = violations.isEmpty();
        evidence.add(new EvaluationEvidence(
                "citation-presence-summary",
                passed
                        ? citations.size() + " citation(s) checked against policy " + policy + ", no violations"
                        : "Policy " + policy + " violated: " + String.join("; ", violations),
                null,
                null,
                null,
                null,
                Map.of(POLICY_METADATA_KEY, policy.name(), CITATION_COUNT_METADATA_KEY, citations.size())));
        return new EvaluationResult(
                passed,
                passed ? 1.0 : 0.0,
                1.0,
                passed
                        ? "Citations satisfy policy " + policy + " (" + citations.size() + " citation(s))"
                        : "Citation policy " + policy + " violated: " + String.join("; ", violations),
                evidence,
                false);
    }

    private List<Citation> citations(EvaluationContext context) {
        List<Citation> citations = context.execution().getCitations();
        return citations != null ? citations : List.of();
    }

    private EvaluationEvidence citationEvidence(String type, String description, Citation citation) {
        return new EvaluationEvidence(
                type,
                description,
                citation.documentId(),
                null,
                null,
                citation.quotedText(),
                Map.of(
                        "documentTitle", String.valueOf(citation.documentTitle()),
                        "section", String.valueOf(citation.section()),
                        "url", String.valueOf(citation.url())));
    }
}
