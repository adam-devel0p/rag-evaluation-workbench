package com.rageval.workbench.target.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import tools.jackson.databind.JsonNode;

/**
 * The external chatbot/RAG system under evaluation. Never persists the actual secret value —
 * only the name of the environment variable holding it.
 */
@Entity
@Table(name = "target_system")
public class TargetSystem {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_type", nullable = false, length = 30)
    private AdapterType adapterType;

    @Column(name = "endpoint_url", length = 2048)
    private String endpointUrl;

    @Enumerated(EnumType.STRING)
    @Column(name = "http_method", length = 10)
    private TargetHttpMethod httpMethod;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_template")
    private JsonNode requestTemplate;

    @Column(name = "answer_json_pointer", length = 500)
    private String answerJsonPointer;

    @Column(name = "citations_json_pointer", length = 500)
    private String citationsJsonPointer;

    @Column(name = "retrieved_chunks_json_pointer", length = 500)
    private String retrievedChunksJsonPointer;

    @Column(name = "model_json_pointer", length = 500)
    private String modelJsonPointer;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "timeout_milliseconds", nullable = false)
    private int timeoutMilliseconds;

    @Column(name = "connect_timeout_milliseconds", nullable = false)
    private int connectTimeoutMilliseconds;

    @Column(name = "follow_redirects", nullable = false)
    private boolean followRedirects;

    @Column(name = "maximum_concurrency", nullable = false)
    private int maximumConcurrency;

    @Enumerated(EnumType.STRING)
    @Column(name = "authentication_type", nullable = false, length = 30)
    private AuthenticationType authenticationType;

    @Column(name = "secret_environment_variable")
    private String secretEnvironmentVariable;

    @Column(name = "api_key_header_name")
    private String apiKeyHeaderName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_mapping")
    private ResponseMapping responseMapping;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TargetSystem() {
        // JPA
    }

    public TargetSystem(
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
            ResponseMapping responseMapping) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.adapterType = Objects.requireNonNull(adapterType, "adapterType must not be null");
        this.endpointUrl = endpointUrl;
        this.httpMethod = httpMethod;
        this.requestTemplate = requestTemplate;
        this.answerJsonPointer = answerJsonPointer;
        this.citationsJsonPointer = citationsJsonPointer;
        this.retrievedChunksJsonPointer = retrievedChunksJsonPointer;
        this.modelJsonPointer = modelJsonPointer;
        this.enabled = enabled;
        this.timeoutMilliseconds = timeoutMilliseconds;
        this.connectTimeoutMilliseconds = connectTimeoutMilliseconds;
        this.followRedirects = followRedirects;
        this.maximumConcurrency = maximumConcurrency;
        this.authenticationType =
                Objects.requireNonNull(authenticationType, "authenticationType must not be null");
        this.secretEnvironmentVariable = secretEnvironmentVariable;
        this.apiKeyHeaderName = apiKeyHeaderName;
        this.responseMapping = responseMapping;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Replaces every editable field. There is no partial-update path: the API layer always sends
     * the full desired state (mirrors {@link com.rageval.workbench.dataset.domain.EvaluationDataset}'s
     * all-or-nothing modification model, just without a READY-style lock since a target has no
     * "published" state).
     */
    public void update(
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
            ResponseMapping responseMapping) {
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.adapterType = Objects.requireNonNull(adapterType, "adapterType must not be null");
        this.endpointUrl = endpointUrl;
        this.httpMethod = httpMethod;
        this.requestTemplate = requestTemplate;
        this.answerJsonPointer = answerJsonPointer;
        this.citationsJsonPointer = citationsJsonPointer;
        this.retrievedChunksJsonPointer = retrievedChunksJsonPointer;
        this.modelJsonPointer = modelJsonPointer;
        this.enabled = enabled;
        this.timeoutMilliseconds = timeoutMilliseconds;
        this.connectTimeoutMilliseconds = connectTimeoutMilliseconds;
        this.followRedirects = followRedirects;
        this.maximumConcurrency = maximumConcurrency;
        this.authenticationType =
                Objects.requireNonNull(authenticationType, "authenticationType must not be null");
        this.secretEnvironmentVariable = secretEnvironmentVariable;
        this.apiKeyHeaderName = apiKeyHeaderName;
        this.responseMapping = responseMapping;
        this.updatedAt = Instant.now();
    }

    public void disable() {
        this.enabled = false;
        this.updatedAt = Instant.now();
    }

    public void enable() {
        this.enabled = true;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public AdapterType getAdapterType() {
        return adapterType;
    }

    public String getEndpointUrl() {
        return endpointUrl;
    }

    public TargetHttpMethod getHttpMethod() {
        return httpMethod;
    }

    public JsonNode getRequestTemplate() {
        return requestTemplate;
    }

    public String getAnswerJsonPointer() {
        return answerJsonPointer;
    }

    public String getCitationsJsonPointer() {
        return citationsJsonPointer;
    }

    public String getRetrievedChunksJsonPointer() {
        return retrievedChunksJsonPointer;
    }

    public String getModelJsonPointer() {
        return modelJsonPointer;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getTimeoutMilliseconds() {
        return timeoutMilliseconds;
    }

    public int getConnectTimeoutMilliseconds() {
        return connectTimeoutMilliseconds;
    }

    public boolean isFollowRedirects() {
        return followRedirects;
    }

    public int getMaximumConcurrency() {
        return maximumConcurrency;
    }

    public AuthenticationType getAuthenticationType() {
        return authenticationType;
    }

    public String getSecretEnvironmentVariable() {
        return secretEnvironmentVariable;
    }

    public String getApiKeyHeaderName() {
        return apiKeyHeaderName;
    }

    public ResponseMapping getResponseMapping() {
        return responseMapping;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
