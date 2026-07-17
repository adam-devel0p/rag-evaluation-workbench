package com.rageval.workbench.target.application;

import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.domain.TargetSystemConfiguration;
import org.springframework.stereotype.Component;

/** Builds the adapter-facing {@link TargetSystemConfiguration} from a persisted {@link TargetSystem}, resolving its secret. */
@Component
public class TargetSystemConfigurationFactory {

    private final SecretResolver secretResolver;

    public TargetSystemConfigurationFactory(SecretResolver secretResolver) {
        this.secretResolver = secretResolver;
    }

    public TargetSystemConfiguration create(TargetSystem target) {
        String resolvedSecretValue = secretResolver.resolve(target).orElse(null);
        return new TargetSystemConfiguration(
                target.getId(),
                target.getName(),
                target.getAdapterType(),
                target.getEndpointUrl(),
                target.getHttpMethod(),
                target.getRequestTemplate(),
                target.getAnswerJsonPointer(),
                target.getCitationsJsonPointer(),
                target.getRetrievedChunksJsonPointer(),
                target.getModelJsonPointer(),
                target.getResponseMapping(),
                target.getTimeoutMilliseconds(),
                target.getConnectTimeoutMilliseconds(),
                target.isFollowRedirects(),
                target.getAuthenticationType(),
                resolvedSecretValue,
                target.getApiKeyHeaderName());
    }
}
