package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.dataset.domain.ExpectedFact;
import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Spec §11.4: the metric fails if any non-semantic forbidden claim matches the answer, mandatory
 * or not — a forbidden claim marks content that must never appear. Evidence identifies the claim
 * and quotes the matching response text.
 */
@Component
public class ForbiddenClaimsEvaluator implements EvaluationStrategy {

    public static final String KEY = "forbidden-claims";

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
        if (context.testCase().getForbiddenClaims().isEmpty()) {
            return EvaluationApplicability.notApplicable("the test case defines no forbidden claims");
        }
        if (deterministicClaims(context).isEmpty()) {
            return EvaluationApplicability.notApplicable(
                    "all forbidden claims are SEMANTIC; semantic matching is performed by an LLM evaluator");
        }
        if (!context.hasSuccessfulAnswer()) {
            return EvaluationApplicability.missingRequiredData(
                    "no successful answer to evaluate (transport-level failure is judged by the transport evaluator)");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        String answer = context.execution().getAnswer();
        List<ExpectedFact> claims = deterministicClaims(context);
        List<EvaluationEvidence> evidence = new ArrayList<>();
        List<String> matchedClaimIds = new ArrayList<>();
        for (ExpectedFact claim : claims) {
            Optional<FactMatcher.FactMatch> match = FactMatcher.match(claim, answer);
            if (match.isPresent()) {
                matchedClaimIds.add(claim.id());
                evidence.add(new EvaluationEvidence(
                        "forbidden-claim-matched",
                        "Forbidden claim \"" + claim.id() + "\" (" + claim.matchType() + ") matched the answer: "
                                + (claim.explanation() != null ? claim.explanation() : "\"" + claim.value() + "\""),
                        null,
                        null,
                        null,
                        match.get().matchedText(),
                        Map.of(
                                "claimId", String.valueOf(claim.id()),
                                "matchType", claim.matchType().name(),
                                "forbiddenValue", String.valueOf(claim.value()))));
            }
        }
        boolean passed = matchedClaimIds.isEmpty();
        double score = (double) (claims.size() - matchedClaimIds.size()) / claims.size();
        if (passed) {
            evidence.add(new EvaluationEvidence(
                    "forbidden-claims-absent",
                    "None of the " + claims.size() + " forbidden claim(s) matched the answer",
                    null,
                    null,
                    null,
                    null,
                    Map.of("checkedClaimCount", claims.size())));
        }
        return new EvaluationResult(
                passed,
                score,
                1.0,
                passed
                        ? "No forbidden claim matched (" + claims.size() + " checked)"
                        : "Forbidden claim(s) matched the answer: " + String.join(", ", matchedClaimIds),
                evidence,
                false);
    }

    private List<ExpectedFact> deterministicClaims(EvaluationContext context) {
        return context.testCase().getForbiddenClaims().stream()
                .filter(FactMatcher::isDeterministic)
                .toList();
    }
}
