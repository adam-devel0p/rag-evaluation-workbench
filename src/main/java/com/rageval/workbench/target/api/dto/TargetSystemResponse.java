package com.rageval.workbench.target.api.dto;

import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.ResponseMapping;
import com.rageval.workbench.target.domain.TargetHttpMethod;
import com.rageval.workbench.target.domain.TargetSystem;
import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The API-facing view of a {@link TargetSystem}. Only ever carries the secret's environment
 * variable <em>name</em> (harmless to display back — it is not the secret itself), never a
 * resolved secret value.
 */
public record TargetSystemResponse(
        UUID id,
        String name,
        String description,
        AdapterType adapterType,
        String endpointUrl,
        TargetHttpMethod httpMethod,
        JsonNode requestTemplate,
        String answerJsonPointer,
        String citationsJsonPointer,
        String retrievedChunksJsonPointer,
        String modelJsonPointer,
        boolean enabled,
        int timeoutMilliseconds,
        int connectTimeoutMilliseconds,
        boolean followRedirects,
        int maximumConcurrency,
        AuthenticationType authenticationType,
        String secretEnvironmentVariable,
        String apiKeyHeaderName,
        ResponseMapping responseMapping,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public static TargetSystemResponse from(TargetSystem target) {
        return new TargetSystemResponse(
                target.getId(),
                target.getName(),
                target.getDescription(),
                target.getAdapterType(),
                target.getEndpointUrl(),
                target.getHttpMethod(),
                target.getRequestTemplate(),
                target.getAnswerJsonPointer(),
                target.getCitationsJsonPointer(),
                target.getRetrievedChunksJsonPointer(),
                target.getModelJsonPointer(),
                target.isEnabled(),
                target.getTimeoutMilliseconds(),
                target.getConnectTimeoutMilliseconds(),
                target.isFollowRedirects(),
                target.getMaximumConcurrency(),
                target.getAuthenticationType(),
                target.getSecretEnvironmentVariable(),
                target.getApiKeyHeaderName(),
                target.getResponseMapping(),
                target.getCreatedAt(),
                target.getUpdatedAt(),
                target.getVersion());
    }
}
