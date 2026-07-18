package com.rageval.workbench.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration of the local Ollama judge model (spec §12). The values are environment-driven in
 * {@code application.properties} ({@code OLLAMA_EVALUATOR_MODEL}, {@code OLLAMA_TEMPERATURE},
 * {@code OLLAMA_MAX_CONCURRENCY}; the base URL maps onto Spring AI's own
 * {@code spring.ai.ollama.base-url} via {@code OLLAMA_BASE_URL}). The defaults are a conservative
 * example — no particular model is required for the application to compile or start, and nothing
 * here causes a connection attempt at startup.
 *
 * <p>{@code temperature} defaults to zero: judge tasks must be as deterministic as the model
 * allows. {@code maximumConcurrency} bounds concurrent judge calls against the single local
 * Ollama instance.
 */
@ConfigurationProperties(prefix = "rag-evaluation.llm")
public record LlmProperties(
        @DefaultValue("llama3.2:3b") String model,
        @DefaultValue("0.0") double temperature,
        @DefaultValue("2") int maximumConcurrency) {
}
