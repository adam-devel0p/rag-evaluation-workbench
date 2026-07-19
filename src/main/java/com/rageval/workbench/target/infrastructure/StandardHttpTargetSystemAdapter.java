package com.rageval.workbench.target.infrastructure;

import com.rageval.workbench.common.json.JsonPointerExtractor;
import com.rageval.workbench.common.json.PlaceholderValues;
import com.rageval.workbench.common.json.RequestTemplateSubstitutor;
import com.rageval.workbench.configuration.ContentLoggingProperties;
import com.rageval.workbench.configuration.TargetProperties;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.RetrievedChunk;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.CitationFieldMapping;
import com.rageval.workbench.target.domain.ResponseMapping;
import com.rageval.workbench.target.domain.RetrievedChunkFieldMapping;
import com.rageval.workbench.target.domain.TargetInvocationOutcome;
import com.rageval.workbench.target.domain.TargetInvocationRequest;
import com.rageval.workbench.target.domain.TargetInvocationResult;
import com.rageval.workbench.target.domain.TargetSystemAdapter;
import com.rageval.workbench.target.domain.TargetSystemConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Calls an external target over HTTP using {@link RestClient}. A fresh {@link HttpClient} /
 * {@link RestClient} is built per invocation because connect timeout and redirect policy are
 * per-target configuration, not global — the cost is negligible next to a network round trip.
 *
 * <p>Retry policy follows spec §8.1 exactly: HTTP 400/401/403/404, invalid JSON, and a missing
 * answer field are never retried. HTTP 429/502/503/504, connection resets, and timeouts are
 * retried up to {@link TargetProperties.Retry#maximumAttempts()} total attempts with exponential
 * backoff. Any other non-2xx status (e.g. 500) is treated as non-retryable by omission — the spec
 * enumerates the retryable set exhaustively and 500 is not in it.
 */
@Component
public class StandardHttpTargetSystemAdapter implements TargetSystemAdapter {

    private static final Logger log = LoggerFactory.getLogger(StandardHttpTargetSystemAdapter.class);

    private static final List<Integer> NON_RETRYABLE_STATUSES = List.of(400, 401, 403, 404);
    private static final List<Integer> RETRYABLE_STATUSES = List.of(429, 502, 503, 504);

    private final JsonMapper jsonMapper;
    private final RequestTemplateSubstitutor requestTemplateSubstitutor;
    private final JsonPointerExtractor jsonPointerExtractor;
    private final TargetProperties targetProperties;
    private final ContentLoggingProperties contentLogging;

    public StandardHttpTargetSystemAdapter(
            JsonMapper jsonMapper,
            RequestTemplateSubstitutor requestTemplateSubstitutor,
            JsonPointerExtractor jsonPointerExtractor,
            TargetProperties targetProperties,
            ContentLoggingProperties contentLogging) {
        this.jsonMapper = jsonMapper;
        this.requestTemplateSubstitutor = requestTemplateSubstitutor;
        this.jsonPointerExtractor = jsonPointerExtractor;
        this.targetProperties = targetProperties;
        this.contentLogging = contentLogging;
    }

    @Override
    public AdapterType adapterType() {
        return AdapterType.STANDARD_HTTP;
    }

    @Override
    public TargetInvocationResult invoke(TargetSystemConfiguration configuration, TargetInvocationRequest request) {
        JsonNode requestPayload = requestTemplateSubstitutor.substitute(
                configuration.requestTemplate(),
                new PlaceholderValues(
                        request.question(), request.conversationId(), request.testCaseId(), request.userContext()));
        byte[] bodyBytes = jsonMapper.writeValueAsBytes(requestPayload);

        RestClient restClient = RestClient.builder().requestFactory(buildRequestFactory(configuration)).build();
        HttpMethod httpMethod = HttpMethod.valueOf(configuration.httpMethod().name());

        int maxAttempts = Math.max(1, targetProperties.retry().maximumAttempts());
        AttemptOutcome outcome = null;
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            outcome = attemptOnce(restClient, httpMethod, configuration, bodyBytes);
            if (!outcome.retryable() || attempt == maxAttempts) {
                break;
            }
            sleepBackoff(attempt);
        }
        return toResult(outcome, requestPayload, attempt);
    }

    private ClientHttpRequestFactory buildRequestFactory(TargetSystemConfiguration configuration) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(configuration.connectTimeoutMilliseconds()))
                .followRedirects(configuration.followRedirects() ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(Duration.ofMillis(configuration.timeoutMilliseconds()));
        return factory;
    }

    private AttemptOutcome attemptOnce(
            RestClient restClient, HttpMethod httpMethod, TargetSystemConfiguration configuration, byte[] bodyBytes) {
        long startNanos = System.nanoTime();
        try {
            RestClient.RequestBodySpec spec =
                    restClient.method(httpMethod).uri(URI.create(configuration.endpointUrl()));
            spec = spec.headers(headers -> applyHeaders(headers, configuration));
            if (httpMethod != HttpMethod.GET) {
                spec = spec.contentType(MediaType.APPLICATION_JSON).body(bodyBytes);
            }
            RawAttempt raw = spec.exchange((httpRequest, response) -> new RawAttempt(
                    response.getStatusCode().value(),
                    readBounded(response.getBody(), targetProperties.maximumResponseBytes())));
            long latencyMillis = elapsedMillis(startNanos);
            log.debug(
                    "target invocation attempt targetSystemId={} httpStatus={} latencyMs={} bodyBytes={}",
                    configuration.targetSystemId(), raw.status(), latencyMillis, raw.body().length);
            // Dev-only opt-in (spec §23): payloads contain client content and are never logged
            // unless rag-evaluation.logging.verbose-content is explicitly enabled. The request
            // body is logged as substituted JSON — authorization headers are not part of it and
            // are never logged on any path.
            if (contentLogging.isVerboseContent() && log.isDebugEnabled()) {
                log.debug(
                        "target request body: {}\ntarget response body: {}",
                        new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8),
                        new String(raw.body(), java.nio.charset.StandardCharsets.UTF_8));
            }
            return interpret(raw, configuration, latencyMillis);
        } catch (ResourceAccessException ex) {
            long latencyMillis = elapsedMillis(startNanos);
            log.debug(
                    "target invocation attempt targetSystemId={} failed after latencyMs={}: {}",
                    configuration.targetSystemId(), latencyMillis, ex.getClass().getSimpleName());
            return classifyTransportFailure(ex, latencyMillis);
        }
    }

    private void applyHeaders(HttpHeaders headers, TargetSystemConfiguration configuration) {
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        switch (configuration.authenticationType()) {
            case BEARER_TOKEN -> {
                if (configuration.resolvedSecretValue() != null) {
                    headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.resolvedSecretValue());
                }
            }
            case API_KEY_HEADER -> {
                if (configuration.resolvedSecretValue() != null && configuration.apiKeyHeaderName() != null) {
                    headers.set(configuration.apiKeyHeaderName(), configuration.resolvedSecretValue());
                }
            }
            case NONE -> {
                // no authentication header
            }
        }
    }

    private byte[] readBounded(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new ResponseTooLargeException(
                        "Response exceeded the configured maximum of " + maxBytes + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private AttemptOutcome interpret(RawAttempt raw, TargetSystemConfiguration configuration, long latencyMillis) {
        int status = raw.status();
        if (NON_RETRYABLE_STATUSES.contains(status)) {
            return AttemptOutcome.terminalError(
                    TargetInvocationOutcome.TARGET_ERROR, status, "HTTP_" + status,
                    "Target returned HTTP " + status, latencyMillis, null);
        }
        if (RETRYABLE_STATUSES.contains(status)) {
            return AttemptOutcome.retryableError(
                    TargetInvocationOutcome.TARGET_ERROR, status, "HTTP_" + status,
                    "Target returned HTTP " + status, latencyMillis);
        }
        if (status < 200 || status >= 300) {
            return AttemptOutcome.terminalError(
                    TargetInvocationOutcome.TARGET_ERROR, status, "HTTP_" + status,
                    "Target returned HTTP " + status, latencyMillis, null);
        }

        JsonNode rawResponse;
        try {
            rawResponse = raw.body().length == 0 ? jsonMapper.createObjectNode() : jsonMapper.readTree(raw.body());
        } catch (JacksonException ex) {
            return AttemptOutcome.terminalError(
                    TargetInvocationOutcome.INVALID_RESPONSE, status, "INVALID_JSON",
                    "Response body was not valid JSON", latencyMillis, null);
        }

        Optional<String> answer = jsonPointerExtractor.extractText(rawResponse, configuration.answerJsonPointer());
        if (answer.isEmpty() || answer.get().isBlank()) {
            return AttemptOutcome.terminalError(
                    TargetInvocationOutcome.INVALID_RESPONSE, status, "MISSING_ANSWER",
                    "Response did not contain a non-blank answer at the configured pointer", latencyMillis, rawResponse);
        }

        ResponseMapping mapping = configuration.responseMapping();
        List<Citation> citations = extractCitations(rawResponse, configuration.citationsJsonPointer(), mapping);
        List<RetrievedChunk> chunks = extractChunks(rawResponse, configuration.retrievedChunksJsonPointer(), mapping);
        String model = jsonPointerExtractor.extractText(rawResponse, configuration.modelJsonPointer()).orElse(null);
        Integer inputTokens = mapping != null
                ? jsonPointerExtractor.extractInt(rawResponse, mapping.inputTokensJsonPointer()).orElse(null)
                : null;
        Integer outputTokens = mapping != null
                ? jsonPointerExtractor.extractInt(rawResponse, mapping.outputTokensJsonPointer()).orElse(null)
                : null;

        return AttemptOutcome.success(
                status, rawResponse, answer.get(), citations, chunks, model, inputTokens, outputTokens, latencyMillis);
    }

    private AttemptOutcome classifyTransportFailure(ResourceAccessException ex, long latencyMillis) {
        Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
        if (cause instanceof ResponseTooLargeException) {
            return AttemptOutcome.terminalError(
                    TargetInvocationOutcome.INVALID_RESPONSE, null, "RESPONSE_TOO_LARGE", cause.getMessage(),
                    latencyMillis, null);
        }
        if (cause instanceof HttpTimeoutException) {
            return AttemptOutcome.retryableError(
                    TargetInvocationOutcome.TIMED_OUT, null, "TIMEOUT", cause.getMessage(), latencyMillis);
        }
        if (cause instanceof ConnectException || isConnectionReset(cause)) {
            return AttemptOutcome.retryableError(
                    TargetInvocationOutcome.TARGET_ERROR, null, "CONNECTION_ERROR", cause.getMessage(), latencyMillis);
        }
        return AttemptOutcome.terminalError(
                TargetInvocationOutcome.TARGET_ERROR, null, "CONNECTION_ERROR", cause.getMessage(), latencyMillis, null);
    }

    private boolean isConnectionReset(Throwable cause) {
        return cause instanceof IOException
                && cause.getMessage() != null
                && cause.getMessage().toLowerCase(Locale.ROOT).contains("reset");
    }

    private void sleepBackoff(int attempt) {
        long backoffMillis = (long) (targetProperties.retry().initialBackoff().toMillis()
                * Math.pow(targetProperties.retry().backoffMultiplier(), attempt - 1));
        try {
            Thread.sleep(backoffMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private long elapsedMillis(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    private TargetInvocationResult toResult(AttemptOutcome outcome, JsonNode requestPayload, int attemptCount) {
        return new TargetInvocationResult(
                outcome.outcome(),
                requestPayload,
                outcome.answer(),
                outcome.citations(),
                outcome.retrievedChunks(),
                outcome.targetModel(),
                outcome.inputTokens(),
                outcome.outputTokens(),
                outcome.latencyMillis(),
                outcome.rawResponse(),
                outcome.httpStatus(),
                outcome.errorType(),
                outcome.errorMessage(),
                attemptCount);
    }

    private List<Citation> extractCitations(JsonNode root, String pointer, ResponseMapping mapping) {
        JsonNode array = resolveArray(root, pointer);
        if (array == null) {
            return List.of();
        }
        CitationFieldMapping fieldMapping = mapping != null ? mapping.citationFieldMapping() : null;
        List<Citation> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(new Citation(
                    itemText(item, fieldMapping != null ? fieldMapping.documentIdPointer() : null, "/documentId"),
                    itemText(item, fieldMapping != null ? fieldMapping.documentTitlePointer() : null, "/documentTitle"),
                    itemText(item, fieldMapping != null ? fieldMapping.sectionPointer() : null, "/section"),
                    itemText(item, fieldMapping != null ? fieldMapping.urlPointer() : null, "/url"),
                    itemText(item, fieldMapping != null ? fieldMapping.quotedTextPointer() : null, "/quotedText"),
                    itemMetadata(item)));
        }
        return List.copyOf(result);
    }

    private List<RetrievedChunk> extractChunks(JsonNode root, String pointer, ResponseMapping mapping) {
        JsonNode array = resolveArray(root, pointer);
        if (array == null) {
            return List.of();
        }
        RetrievedChunkFieldMapping fieldMapping = mapping != null ? mapping.retrievedChunkFieldMapping() : null;
        List<RetrievedChunk> result = new ArrayList<>();
        for (JsonNode item : array) {
            result.add(new RetrievedChunk(
                    itemText(item, fieldMapping != null ? fieldMapping.chunkIdPointer() : null, "/chunkId"),
                    itemText(item, fieldMapping != null ? fieldMapping.documentIdPointer() : null, "/documentId"),
                    itemText(item, fieldMapping != null ? fieldMapping.documentTitlePointer() : null, "/documentTitle"),
                    itemText(item, fieldMapping != null ? fieldMapping.contentPointer() : null, "/content"),
                    itemInt(item, fieldMapping != null ? fieldMapping.rankPointer() : null, "/rank"),
                    itemDouble(item, fieldMapping != null ? fieldMapping.scorePointer() : null, "/retrievalScore"),
                    itemMetadata(item)));
        }
        return List.copyOf(result);
    }

    private JsonNode resolveArray(JsonNode root, String pointer) {
        if (pointer == null || pointer.isBlank()) {
            return null;
        }
        JsonNode node = root.at(pointer);
        return (node != null && node.isArray()) ? node : null;
    }

    private String itemText(JsonNode item, String configuredPointer, String defaultPointer) {
        String pointerToUse = (configuredPointer != null && !configuredPointer.isBlank()) ? configuredPointer : defaultPointer;
        return jsonPointerExtractor.extractText(item, pointerToUse).orElse(null);
    }

    private Integer itemInt(JsonNode item, String configuredPointer, String defaultPointer) {
        String pointerToUse = (configuredPointer != null && !configuredPointer.isBlank()) ? configuredPointer : defaultPointer;
        return jsonPointerExtractor.extractInt(item, pointerToUse).orElse(null);
    }

    private Double itemDouble(JsonNode item, String configuredPointer, String defaultPointer) {
        String pointerToUse = (configuredPointer != null && !configuredPointer.isBlank()) ? configuredPointer : defaultPointer;
        return jsonPointerExtractor.extractDouble(item, pointerToUse).orElse(null);
    }

    private Map<String, Object> itemMetadata(JsonNode item) {
        JsonNode metadataNode = item.at("/metadata");
        if (metadataNode == null || !metadataNode.isObject()) {
            return Map.of();
        }
        return jsonMapper.treeToValue(metadataNode, new TypeReference<Map<String, Object>>() {
        });
    }

    private record RawAttempt(int status, byte[] body) {
    }

    /** Thrown by {@link #readBounded} when a response exceeds {@code maximum-response-bytes}; caught and reclassified as {@link ResourceAccessException} by {@code RestClient}. */
    private static final class ResponseTooLargeException extends IOException {
        ResponseTooLargeException(String message) {
            super(message);
        }
    }

    private record AttemptOutcome(
            TargetInvocationOutcome outcome,
            Integer httpStatus,
            JsonNode rawResponse,
            String answer,
            List<Citation> citations,
            List<RetrievedChunk> retrievedChunks,
            String targetModel,
            Integer inputTokens,
            Integer outputTokens,
            String errorType,
            String errorMessage,
            long latencyMillis,
            boolean retryable) {

        static AttemptOutcome success(
                Integer status,
                JsonNode rawResponse,
                String answer,
                List<Citation> citations,
                List<RetrievedChunk> chunks,
                String model,
                Integer inputTokens,
                Integer outputTokens,
                long latencyMillis) {
            return new AttemptOutcome(
                    TargetInvocationOutcome.SUCCEEDED, status, rawResponse, answer, citations, chunks, model,
                    inputTokens, outputTokens, null, null, latencyMillis, false);
        }

        static AttemptOutcome terminalError(
                TargetInvocationOutcome outcome,
                Integer status,
                String errorType,
                String errorMessage,
                long latencyMillis,
                JsonNode rawResponse) {
            return new AttemptOutcome(
                    outcome, status, rawResponse, null, List.of(), List.of(), null, null, null, errorType,
                    errorMessage, latencyMillis, false);
        }

        static AttemptOutcome retryableError(
                TargetInvocationOutcome outcome,
                Integer status,
                String errorType,
                String errorMessage,
                long latencyMillis) {
            return new AttemptOutcome(
                    outcome, status, null, null, List.of(), List.of(), null, null, null, errorType, errorMessage,
                    latencyMillis, true);
        }
    }
}
