package com.rageval.workbench.evaluation.deterministic;

import com.rageval.workbench.evaluation.domain.EvaluationApplicability;
import com.rageval.workbench.evaluation.domain.EvaluationContext;
import com.rageval.workbench.evaluation.domain.EvaluationEvidence;
import com.rageval.workbench.evaluation.domain.EvaluationResult;
import com.rageval.workbench.evaluation.domain.EvaluationStrategy;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Spec §11.2: per-execution pass/fail against {@code maximumAcceptableLatencyMilliseconds}. The
 * run-level median/p90/p95/maximum live in the aggregation service — they are properties of the
 * whole run, not of one execution.
 */
@Component
public class LatencyEvaluator implements EvaluationStrategy {

    public static final String KEY = "latency";

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
        if (context.settings().maximumAcceptableLatencyMilliseconds() <= 0) {
            return EvaluationApplicability.notApplicable(
                    "no maximum acceptable latency is configured (maximumAcceptableLatencyMilliseconds <= 0)");
        }
        if (context.execution().getLatencyMilliseconds() == null) {
            return EvaluationApplicability.missingRequiredData(
                    "no latency was recorded for this execution (the invocation did not complete measurably)");
        }
        return EvaluationApplicability.applicable();
    }

    @Override
    public EvaluationResult evaluate(EvaluationContext context) {
        long latency = context.execution().getLatencyMilliseconds();
        long maximum = context.settings().maximumAcceptableLatencyMilliseconds();
        boolean passed = latency <= maximum;
        EvaluationEvidence evidence = new EvaluationEvidence(
                "latency",
                "Measured latency " + latency + " ms against maximum acceptable " + maximum + " ms",
                null,
                null,
                null,
                null,
                Map.of("latencyMilliseconds", latency, "maximumAcceptableLatencyMilliseconds", maximum));
        return new EvaluationResult(
                passed,
                passed ? 1.0 : 0.0,
                1.0,
                (passed ? "Latency acceptable: " : "Latency too high: ")
                        + latency + " ms (maximum " + maximum + " ms)",
                List.of(evidence),
                false);
    }
}
