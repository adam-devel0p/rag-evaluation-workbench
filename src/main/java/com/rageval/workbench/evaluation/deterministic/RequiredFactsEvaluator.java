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
 * Spec §11.3: every non-semantic required fact is checked against the answer; any missing
 * mandatory fact fails the metric, and the score is matched mandatory facts over total mandatory
 * facts. SEMANTIC facts are deferred to the Phase 5 LLM evaluator — when only semantic facts
 * exist, this evaluator is not applicable rather than silently passing.
 */
@Component
public class RequiredFactsEvaluator implements EvaluationStrategy {

    public static final String KEY = "required-facts";

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
        List<ExpectedFact> deterministic = deterministicFacts(context);
        if (context.testCase().getRequiredFacts().isEmpty()) {
            return EvaluationApplicability.notApplicable("the test case defines no required facts");
        }
        if (deterministic.isEmpty()) {
            return EvaluationApplicability.notApplicable(
                    "all required facts are SEMANTIC; semantic matching is performed by an LLM evaluator");
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
        List<ExpectedFact> facts = deterministicFacts(context);
        List<EvaluationEvidence> evidence = new ArrayList<>();
        int mandatoryTotal = 0;
        int mandatoryMatched = 0;
        List<String> missingMandatory = new ArrayList<>();
        for (ExpectedFact fact : facts) {
            Optional<FactMatcher.FactMatch> match = FactMatcher.match(fact, answer);
            if (fact.mandatory()) {
                mandatoryTotal++;
                if (match.isPresent()) {
                    mandatoryMatched++;
                } else {
                    missingMandatory.add(fact.id());
                }
            }
            evidence.add(new EvaluationEvidence(
                    "required-fact",
                    "Fact \"" + fact.id() + "\" (" + fact.matchType() + ", "
                            + (fact.mandatory() ? "mandatory" : "optional") + ") "
                            + (match.isPresent() ? "matched" : "did not match")
                            + ": expected \"" + fact.value() + "\"",
                    null,
                    null,
                    null,
                    match.map(FactMatcher.FactMatch::matchedText).orElse(null),
                    Map.of(
                            "factId", String.valueOf(fact.id()),
                            "matchType", fact.matchType().name(),
                            "mandatory", fact.mandatory(),
                            "matched", match.isPresent())));
        }
        boolean passed = missingMandatory.isEmpty();
        Double score = mandatoryTotal > 0 ? (double) mandatoryMatched / mandatoryTotal : null;
        String explanation;
        if (mandatoryTotal == 0) {
            explanation = "No mandatory facts are defined; " + facts.size()
                    + " optional fact(s) were checked and recorded as evidence without affecting the verdict";
        } else if (passed) {
            explanation = "All " + mandatoryTotal + " mandatory required fact(s) matched the answer";
        } else {
            explanation = "Missing mandatory required fact(s): " + String.join(", ", missingMandatory)
                    + " (" + mandatoryMatched + "/" + mandatoryTotal + " mandatory facts matched)";
        }
        return new EvaluationResult(passed, score, 1.0, explanation, evidence, false);
    }

    private List<ExpectedFact> deterministicFacts(EvaluationContext context) {
        return context.testCase().getRequiredFacts().stream()
                .filter(FactMatcher::isDeterministic)
                .toList();
    }
}
