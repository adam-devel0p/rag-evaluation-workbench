package com.rageval.workbench.target.api.dto;

import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.ResponseMapping;
import com.rageval.workbench.target.domain.TargetHttpMethod;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import tools.jackson.databind.JsonNode;

/**
 * The full desired state of a target, used for both create ({@code POST}) and update ({@code
 * PUT}) — there is no partial-update endpoint, matching the "full replace" model used elsewhere
 * (mirrors how a dataset is DRAFT-editable-in-full but never patched field by field).
 */
public record TargetSystemRequest(
        @NotNull String name,
        String description,
        @NotNull AdapterType adapterType,
        String endpointUrl,
        TargetHttpMethod httpMethod,
        JsonNode requestTemplate,
        String answerJsonPointer,
        String citationsJsonPointer,
        String retrievedChunksJsonPointer,
        String modelJsonPointer,
        boolean enabled,
        @Min(1) int timeoutMilliseconds,
        @Min(1) int connectTimeoutMilliseconds,
        boolean followRedirects,
        @Min(1) int maximumConcurrency,
        @NotNull AuthenticationType authenticationType,
        String secretEnvironmentVariable,
        String apiKeyHeaderName,
        ResponseMapping responseMapping) {
}
