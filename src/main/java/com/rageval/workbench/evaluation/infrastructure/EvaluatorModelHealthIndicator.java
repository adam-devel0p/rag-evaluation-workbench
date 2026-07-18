package com.rageval.workbench.evaluation.infrastructure;

import com.rageval.workbench.configuration.LlmProperties;
import java.util.List;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Health details identifying evaluator-model unavailability (spec §12): reports whether the
 * configured Ollama judge model is reachable and pulled. DOWN here means LLM evaluator results
 * will be recorded as FAILED (never chatbot failures) while deterministic evaluators keep
 * working — the application itself stays fully functional.
 */
@Component("evaluatorModel")
public class EvaluatorModelHealthIndicator implements HealthIndicator {

    private final OllamaApi ollamaApi;
    private final LlmProperties properties;

    public EvaluatorModelHealthIndicator(OllamaApi ollamaApi, LlmProperties properties) {
        this.ollamaApi = ollamaApi;
        this.properties = properties;
    }

    @Override
    public Health health() {
        String model = properties.model();
        List<OllamaApi.Model> models;
        try {
            OllamaApi.ListModelResponse response = ollamaApi.listModels();
            models = response.models() != null ? response.models() : List.of();
        } catch (RuntimeException ex) {
            return Health.down()
                    .withDetail("evaluatorModel", model)
                    .withDetail("reason", "Ollama is unreachable; LLM evaluator results will be FAILED "
                            + "(deterministic evaluators are unaffected)")
                    .withDetail("error", ex.getClass().getSimpleName())
                    .build();
        }
        boolean available = models.stream()
                .map(OllamaApi.Model::name)
                .anyMatch(name -> name.equals(model) || name.equals(model + ":latest"));
        if (!available) {
            return Health.down()
                    .withDetail("evaluatorModel", model)
                    .withDetail("reason", "Ollama is reachable but the configured evaluator model is not "
                            + "pulled; LLM evaluator results will be FAILED until it is")
                    .withDetail("availableModels", models.size())
                    .build();
        }
        return Health.up().withDetail("evaluatorModel", model).build();
    }
}
