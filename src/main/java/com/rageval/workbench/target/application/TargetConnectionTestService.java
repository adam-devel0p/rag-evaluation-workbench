package com.rageval.workbench.target.application;

import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.target.api.dto.TestConnectionRequest;
import com.rageval.workbench.target.api.dto.TestConnectionResponse;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.TargetInvocationOutcome;
import com.rageval.workbench.target.domain.TargetInvocationRequest;
import com.rageval.workbench.target.domain.TargetInvocationResult;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.domain.TargetSystemConfiguration;
import com.rageval.workbench.target.infrastructure.TargetSystemAdapterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates a target's adapter configuration by actually invoking it once and checking whether
 * the configured pointer mappings resolved, rather than only checking the mappings are
 * syntactically present.
 */
@Service
public class TargetConnectionTestService {

    private final TargetSystemService targetSystemService;
    private final TargetSystemConfigurationFactory configurationFactory;
    private final TargetSystemAdapterRegistry adapterRegistry;

    public TargetConnectionTestService(
            TargetSystemService targetSystemService,
            TargetSystemConfigurationFactory configurationFactory,
            TargetSystemAdapterRegistry adapterRegistry) {
        this.targetSystemService = targetSystemService;
        this.configurationFactory = configurationFactory;
        this.adapterRegistry = adapterRegistry;
    }

    @Transactional(readOnly = true)
    public TestConnectionResponse testConnection(UUID targetId, TestConnectionRequest request) {
        TargetSystem target = targetSystemService.getOrThrow(targetId);
        if (target.getAdapterType() != AdapterType.STANDARD_HTTP) {
            throw new ValidationException(
                    "Connection test is only supported for STANDARD_HTTP targets",
                    new FieldViolation("adapterType", "must be STANDARD_HTTP to test a live connection"));
        }

        TargetSystemConfiguration configuration = configurationFactory.create(target);
        if (target.getAuthenticationType() != AuthenticationType.NONE && configuration.resolvedSecretValue() == null) {
            throw new ValidationException(
                    "Environment variable " + target.getSecretEnvironmentVariable() + " is not set",
                    new FieldViolation("secretEnvironmentVariable", "referenced environment variable is not set"));
        }

        TestConnectionRequest effectiveRequest = request != null ? request : new TestConnectionRequest(null, null, null);
        TargetInvocationRequest invocationRequest = new TargetInvocationRequest(
                "connection-test",
                effectiveRequest.effectiveSampleQuestion(),
                effectiveRequest.conversationId(),
                effectiveRequest.userContext());

        TargetInvocationResult result =
                adapterRegistry.resolve(AdapterType.STANDARD_HTTP).invoke(configuration, invocationRequest);

        return new TestConnectionResponse(
                result.outcome() == TargetInvocationOutcome.SUCCEEDED,
                result.httpStatus(),
                result.latencyMilliseconds(),
                result.answer(),
                result.citations().size(),
                result.retrievedChunks().size(),
                result.targetModel(),
                result.inputTokens(),
                result.outputTokens(),
                result.errorType(),
                result.errorMessage(),
                result.attemptCount(),
                warnings(target, result));
    }

    private List<String> warnings(TargetSystem target, TargetInvocationResult result) {
        List<String> warnings = new ArrayList<>();
        if (isConfigured(target.getCitationsJsonPointer()) && result.citations().isEmpty()) {
            warnings.add("citationsJsonPointer is configured but resolved no citations");
        }
        if (isConfigured(target.getRetrievedChunksJsonPointer()) && result.retrievedChunks().isEmpty()) {
            warnings.add("retrievedChunksJsonPointer is configured but resolved no retrieved chunks");
        }
        if (isConfigured(target.getModelJsonPointer()) && result.targetModel() == null) {
            warnings.add("modelJsonPointer is configured but did not resolve");
        }
        return warnings;
    }

    private boolean isConfigured(String pointer) {
        return pointer != null && !pointer.isBlank();
    }
}
