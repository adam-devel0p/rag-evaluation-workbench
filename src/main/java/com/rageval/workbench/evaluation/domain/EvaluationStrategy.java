package com.rageval.workbench.evaluation.domain;

/**
 * The domain contract every evaluator implements (spec §10), deliberately independent of Spring
 * AI: deterministic evaluators (Phase 4) and LLM evaluators (Phase 5) plug in behind the same
 * interface. Callers must check {@link #applicability} first and only invoke {@link #evaluate}
 * when it answers {@code APPLICABLE}; the reason carried by a non-applicable answer becomes the
 * persisted explanation of the skipped metric result.
 */
public interface EvaluationStrategy {

    /** Stable key identifying this evaluator, e.g. {@code required-facts}; referenced by run configuration. */
    String key();

    /** Version of the evaluator logic, persisted with every result so replays can detect drift. */
    String version();

    /** How this evaluator reaches its verdict; persisted on every metric result. */
    default EvaluatorType evaluatorType() {
        return EvaluatorType.DETERMINISTIC;
    }

    /** Version of the judge prompt for LLM evaluators (spec §12); null for deterministic ones. */
    default String promptVersion() {
        return null;
    }

    /** The judge model name for LLM evaluators (spec §12); null for deterministic ones. */
    default String evaluatorModel() {
        return null;
    }

    EvaluationApplicability applicability(EvaluationContext context);

    /** Only called when {@link #applicability} answered APPLICABLE. */
    EvaluationResult evaluate(EvaluationContext context);
}
