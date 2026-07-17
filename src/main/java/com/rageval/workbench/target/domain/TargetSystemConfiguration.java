package com.rageval.workbench.target.domain;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Everything a {@link TargetSystemAdapter} needs to perform one invocation, resolved from a
 * {@link TargetSystem} by the application layer. Carries the resolved secret <em>value</em> (never
 * the entity's stored environment-variable name) so the adapter package never needs
 * {@code System.getenv} access itself and so the value never round-trips through persistence.
 * {@code resolvedSecretValue} must never be logged or included in any response.
 */
public record TargetSystemConfiguration(
        UUID targetSystemId,
        String name,
        AdapterType adapterType,
        String endpointUrl,
        TargetHttpMethod httpMethod,
        JsonNode requestTemplate,
        String answerJsonPointer,
        String citationsJsonPointer,
        String retrievedChunksJsonPointer,
        String modelJsonPointer,
        ResponseMapping responseMapping,
        int timeoutMilliseconds,
        int connectTimeoutMilliseconds,
        boolean followRedirects,
        AuthenticationType authenticationType,
        String resolvedSecretValue,
        String apiKeyHeaderName) {
}
