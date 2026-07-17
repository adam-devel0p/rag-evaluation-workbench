package com.rageval.workbench.target.application;

import com.rageval.workbench.common.error.ConflictException;
import com.rageval.workbench.common.error.FieldViolation;
import com.rageval.workbench.common.error.NotFoundException;
import com.rageval.workbench.common.error.ValidationException;
import com.rageval.workbench.run.infrastructure.EvaluationRunRepository;
import com.rageval.workbench.target.api.dto.TargetSystemRequest;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.TargetSystem;
import com.rageval.workbench.target.infrastructure.TargetSystemRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CRUD orchestration for {@link TargetSystem}. Controllers never touch the repository directly. */
@Service
public class TargetSystemService {

    private final TargetSystemRepository targetSystemRepository;
    private final EvaluationRunRepository evaluationRunRepository;
    private final TargetUrlValidator urlValidator;

    public TargetSystemService(
            TargetSystemRepository targetSystemRepository,
            EvaluationRunRepository evaluationRunRepository,
            TargetUrlValidator urlValidator) {
        this.targetSystemRepository = targetSystemRepository;
        this.evaluationRunRepository = evaluationRunRepository;
        this.urlValidator = urlValidator;
    }

    @Transactional
    public TargetSystem create(TargetSystemRequest request) {
        validate(request);
        TargetSystem target = new TargetSystem(
                request.name(),
                request.description(),
                request.adapterType(),
                request.endpointUrl(),
                request.httpMethod(),
                request.requestTemplate(),
                request.answerJsonPointer(),
                request.citationsJsonPointer(),
                request.retrievedChunksJsonPointer(),
                request.modelJsonPointer(),
                request.enabled(),
                request.timeoutMilliseconds(),
                request.connectTimeoutMilliseconds(),
                request.followRedirects(),
                request.maximumConcurrency(),
                request.authenticationType(),
                request.secretEnvironmentVariable(),
                request.apiKeyHeaderName(),
                request.responseMapping());
        return targetSystemRepository.save(target);
    }

    @Transactional
    public TargetSystem update(UUID id, TargetSystemRequest request) {
        validate(request);
        TargetSystem target = getOrThrow(id);
        target.update(
                request.name(),
                request.description(),
                request.adapterType(),
                request.endpointUrl(),
                request.httpMethod(),
                request.requestTemplate(),
                request.answerJsonPointer(),
                request.citationsJsonPointer(),
                request.retrievedChunksJsonPointer(),
                request.modelJsonPointer(),
                request.enabled(),
                request.timeoutMilliseconds(),
                request.connectTimeoutMilliseconds(),
                request.followRedirects(),
                request.maximumConcurrency(),
                request.authenticationType(),
                request.secretEnvironmentVariable(),
                request.apiKeyHeaderName(),
                request.responseMapping());
        return target;
    }

    @Transactional(readOnly = true)
    public TargetSystem getOrThrow(UUID id) {
        return targetSystemRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("Target system not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<TargetSystem> list(Pageable pageable) {
        return targetSystemRepository.findAll(pageable);
    }

    @Transactional
    public void delete(UUID id) {
        TargetSystem target = getOrThrow(id);
        if (evaluationRunRepository.existsByTargetSystemId(id)) {
            throw new ConflictException(
                    "Target system " + id + " has dependent evaluation runs; disable it instead of deleting");
        }
        targetSystemRepository.delete(target);
    }

    private void validate(TargetSystemRequest request) {
        if (request.adapterType() == AdapterType.STANDARD_HTTP) {
            urlValidator.validate(request.endpointUrl());
            if (request.httpMethod() == null) {
                throw new ValidationException(
                        "httpMethod is required for a STANDARD_HTTP target",
                        new FieldViolation("httpMethod", "must not be null"));
            }
            if (request.answerJsonPointer() == null || request.answerJsonPointer().isBlank()) {
                throw new ValidationException(
                        "answerJsonPointer is mandatory for a STANDARD_HTTP target",
                        new FieldViolation("answerJsonPointer", "must not be blank"));
            }
        }
        if (request.authenticationType() != AuthenticationType.NONE
                && (request.secretEnvironmentVariable() == null || request.secretEnvironmentVariable().isBlank())) {
            throw new ValidationException(
                    "secretEnvironmentVariable is required when authenticationType is not NONE",
                    new FieldViolation("secretEnvironmentVariable", "must not be blank"));
        }
        if (request.authenticationType() == AuthenticationType.API_KEY_HEADER
                && (request.apiKeyHeaderName() == null || request.apiKeyHeaderName().isBlank())) {
            throw new ValidationException(
                    "apiKeyHeaderName is required when authenticationType is API_KEY_HEADER",
                    new FieldViolation("apiKeyHeaderName", "must not be blank"));
        }
    }
}
