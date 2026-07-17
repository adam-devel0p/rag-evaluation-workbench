package com.rageval.workbench.evaluation.application;

import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationMetricResult;
import com.rageval.workbench.evaluation.deterministic.ExpectedDocumentEvaluator;
import com.rageval.workbench.evaluation.deterministic.ForbiddenClaimsEvaluator;
import com.rageval.workbench.evaluation.deterministic.RefusalEvaluator;
import com.rageval.workbench.evaluation.deterministic.RequiredFactsEvaluator;
import com.rageval.workbench.evaluation.domain.MetricResultStatus;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.ExecutionStatus;
import com.rageval.workbench.run.domain.TestExecution;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Deterministic fact-level comparison of a test case's successful repetitions (spec §17): wording
 * variation is deliberately invisible here — what is compared is which required facts matched,
 * which forbidden claims matched, the refusal classification, the cited document set, and the
 * first relevant retrieval rank. A difference in any of these is material inconsistency (different
 * numbers/versions/refusal behavior manifest as different fact-match outcomes), which is exactly
 * the deterministic comparison the spec prefers over a semantic one.
 */
@Component
public class RepetitionConsistencyAnalyzer {

    /** The comparable per-repetition signature; equal signatures mean consistent behavior. */
    public record RepetitionSignature(
            UUID executionId,
            int repetitionNumber,
            Map<String, Boolean> requiredFactMatches,
            Set<String> matchedForbiddenClaimIds,
            String refusalClassification,
            Set<String> citedDocumentIds,
            Integer firstRelevantRank) {

        boolean sameBehaviorAs(RepetitionSignature other) {
            return requiredFactMatches.equals(other.requiredFactMatches)
                    && matchedForbiddenClaimIds.equals(other.matchedForbiddenClaimIds)
                    && Objects.equals(refusalClassification, other.refusalClassification)
                    && citedDocumentIds.equals(other.citedDocumentIds)
                    && Objects.equals(firstRelevantRank, other.firstRelevantRank);
        }

        List<String> differencesTo(RepetitionSignature other) {
            List<String> differences = new ArrayList<>();
            if (!requiredFactMatches.equals(other.requiredFactMatches)) {
                differences.add("required-fact matches " + requiredFactMatches + " vs " + other.requiredFactMatches);
            }
            if (!matchedForbiddenClaimIds.equals(other.matchedForbiddenClaimIds)) {
                differences.add("matched forbidden claims " + matchedForbiddenClaimIds + " vs "
                        + other.matchedForbiddenClaimIds);
            }
            if (!Objects.equals(refusalClassification, other.refusalClassification)) {
                differences.add("refusal classification " + refusalClassification + " vs "
                        + other.refusalClassification);
            }
            if (!citedDocumentIds.equals(other.citedDocumentIds)) {
                differences.add("cited documents " + citedDocumentIds + " vs " + other.citedDocumentIds);
            }
            if (!Objects.equals(firstRelevantRank, other.firstRelevantRank)) {
                differences.add("first relevant retrieval rank " + firstRelevantRank + " vs "
                        + other.firstRelevantRank);
            }
            return differences;
        }
    }

    /** Comparison of all successful repetitions of one test case. */
    public record ConsistencyComparison(
            boolean comparable, boolean consistent, List<RepetitionSignature> signatures, List<String> differences) {
    }

    /**
     * @param executions all executions of one test case within the run
     * @param resultsByExecution metric results grouped by execution id
     */
    public ConsistencyComparison compare(
            List<TestExecution> executions, Map<UUID, List<EvaluationMetricResult>> resultsByExecution) {
        List<RepetitionSignature> signatures = executions.stream()
                .filter(execution -> execution.getStatus() == ExecutionStatus.SUCCEEDED)
                .sorted((a, b) -> Integer.compare(a.getRepetitionNumber(), b.getRepetitionNumber()))
                .map(execution -> signature(execution, resultsByExecution.getOrDefault(execution.getId(), List.of())))
                .toList();
        if (signatures.size() < 2) {
            return new ConsistencyComparison(false, true, signatures, List.of());
        }
        RepetitionSignature first = signatures.get(0);
        List<String> differences = new ArrayList<>();
        for (int i = 1; i < signatures.size(); i++) {
            RepetitionSignature other = signatures.get(i);
            if (!first.sameBehaviorAs(other)) {
                for (String difference : first.differencesTo(other)) {
                    differences.add("repetition " + first.repetitionNumber() + " vs "
                            + other.repetitionNumber() + ": " + difference);
                }
            }
        }
        return new ConsistencyComparison(true, differences.isEmpty(), signatures, differences);
    }

    private RepetitionSignature signature(TestExecution execution, List<EvaluationMetricResult> results) {
        Map<String, Boolean> requiredFactMatches = new TreeMap<>();
        Set<String> matchedForbiddenClaimIds = new TreeSet<>();
        String refusalClassification = null;
        Integer firstRelevantRank = null;
        for (EvaluationMetricResult result : results) {
            if (result.getStatus() != MetricResultStatus.COMPLETED) {
                continue;
            }
            switch (result.getEvaluatorKey()) {
                case RequiredFactsEvaluator.KEY -> {
                    for (EvaluationEvidence evidence : result.getEvidence()) {
                        if ("required-fact".equals(evidence.type()) && evidence.metadata() != null) {
                            Object factId = evidence.metadata().get("factId");
                            Object matched = evidence.metadata().get("matched");
                            if (factId != null && matched instanceof Boolean matchedBoolean) {
                                requiredFactMatches.put(factId.toString(), matchedBoolean);
                            }
                        }
                    }
                }
                case ForbiddenClaimsEvaluator.KEY -> {
                    for (EvaluationEvidence evidence : result.getEvidence()) {
                        if ("forbidden-claim-matched".equals(evidence.type()) && evidence.metadata() != null) {
                            Object claimId = evidence.metadata().get("claimId");
                            if (claimId != null) {
                                matchedForbiddenClaimIds.add(claimId.toString());
                            }
                        }
                    }
                }
                case RefusalEvaluator.KEY -> {
                    for (EvaluationEvidence evidence : result.getEvidence()) {
                        if (evidence.metadata() != null
                                && evidence.metadata().get(RefusalEvaluator.CLASSIFICATION_METADATA_KEY) != null) {
                            refusalClassification = evidence.metadata()
                                    .get(RefusalEvaluator.CLASSIFICATION_METADATA_KEY)
                                    .toString();
                        }
                    }
                }
                case ExpectedDocumentEvaluator.KEY -> {
                    for (EvaluationEvidence evidence : result.getEvidence()) {
                        if ("retrieval-metrics".equals(evidence.type()) && evidence.metadata() != null) {
                            Object rank = evidence.metadata().get(ExpectedDocumentEvaluator.FIRST_RELEVANT_RANK_KEY);
                            if (rank instanceof Number number) {
                                firstRelevantRank = number.intValue();
                            }
                        }
                    }
                }
                default -> {
                    // other evaluators carry no repetition-comparable signal
                }
            }
        }
        Set<String> citedDocumentIds = new LinkedHashSet<>();
        if (execution.getCitations() != null) {
            for (Citation citation : execution.getCitations()) {
                if (citation.documentId() != null && !citation.documentId().isBlank()) {
                    citedDocumentIds.add(citation.documentId());
                }
            }
        }
        return new RepetitionSignature(
                execution.getId(),
                execution.getRepetitionNumber(),
                requiredFactMatches,
                matchedForbiddenClaimIds,
                refusalClassification,
                citedDocumentIds,
                firstRelevantRank);
    }
}
