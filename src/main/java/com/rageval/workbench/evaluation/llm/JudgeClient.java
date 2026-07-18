package com.rageval.workbench.evaluation.llm;

/**
 * The structured judge-call abstraction every LLM evaluator speaks (spec §12): one prompt in, one
 * schema-mapped Java record out. Production wires an Ollama-backed implementation; the normal
 * test suite uses a deterministic fake, so {@code ./mvnw clean verify} never touches a real
 * model.
 *
 * <p>Implementations must throw {@link JudgeUnavailableException} when the judge cannot be
 * reached at all (Ollama down, model not pulled), {@link JudgeMalformedOutputException} when the
 * model answered but the answer does not map onto the requested schema, and plain
 * {@link JudgeException} for other call failures. Callers translate any of these into a
 * metric-status {@code FAILED} result — an evaluator failure, never a chatbot failure.
 */
public interface JudgeClient {

    /** The configured judge model name, persisted with every LLM metric result. */
    String modelName();

    <T> JudgeResponse<T> judge(JudgeRequest request, Class<T> responseType);
}
