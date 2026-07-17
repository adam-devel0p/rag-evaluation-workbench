package com.rageval.workbench.target.application;

import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.TargetSystem;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves a target's secret <em>value</em> from the environment at invocation time. The entity
 * only ever stores the environment-variable name; the value returned here must never be logged,
 * persisted, or included in any API response.
 */
@Component
public class SecretResolver {

    public Optional<String> resolve(TargetSystem target) {
        if (target.getAuthenticationType() == AuthenticationType.NONE) {
            return Optional.empty();
        }
        String variableName = target.getSecretEnvironmentVariable();
        if (variableName == null || variableName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(System.getenv(variableName));
    }
}
