package com.rageval.workbench.target.infrastructure;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.rageval.workbench.common.json.JsonPointerExtractor;
import com.rageval.workbench.common.json.RequestTemplateSubstitutor;
import com.rageval.workbench.configuration.TargetProperties;
import com.rageval.workbench.target.domain.AdapterType;
import com.rageval.workbench.target.domain.AuthenticationType;
import com.rageval.workbench.target.domain.TargetHttpMethod;
import com.rageval.workbench.target.domain.TargetInvocationOutcome;
import com.rageval.workbench.target.domain.TargetInvocationRequest;
import com.rageval.workbench.target.domain.TargetInvocationResult;
import com.rageval.workbench.target.domain.TargetSystemConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class StandardHttpTargetSystemAdapterWireMockTest {

    private static WireMockServer wireMockServer;

    private JsonMapper jsonMapper;
    private StandardHttpTargetSystemAdapter adapter;

    @BeforeAll
    static void startServer() {
        wireMockServer = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMockServer.start();
        com.github.tomakehurst.wiremock.client.WireMock.configureFor("localhost", wireMockServer.port());
    }

    @AfterAll
    static void stopServer() {
        wireMockServer.stop();
    }

    @BeforeEach
    void setUp() {
        wireMockServer.resetAll();
        jsonMapper = JsonMapper.builder().build();
        adapter = newAdapter(2_097_152L, 2);
    }

    private StandardHttpTargetSystemAdapter newAdapter(long maximumResponseBytes, int maximumAttempts) {
        return new StandardHttpTargetSystemAdapter(
                jsonMapper,
                new RequestTemplateSubstitutor(jsonMapper),
                new JsonPointerExtractor(),
                new TargetProperties(maximumResponseBytes, new TargetProperties.Retry(maximumAttempts, Duration.ofMillis(20), 1.5)));
    }

    private TargetSystemConfiguration configuration(String path) {
        return configuration(path, 3_000, AuthenticationType.NONE, null, null);
    }

    private TargetSystemConfiguration configuration(
            String path, int timeoutMillis, AuthenticationType authenticationType, String secretValue, String apiKeyHeaderName) {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        return new TargetSystemConfiguration(
                UUID.randomUUID(),
                "Test target",
                AdapterType.STANDARD_HTTP,
                wireMockServer.baseUrl() + path,
                TargetHttpMethod.POST,
                template,
                "/answer",
                "/citations",
                "/retrievedChunks",
                "/model",
                null,
                timeoutMillis,
                2_000,
                false,
                authenticationType,
                secretValue,
                apiKeyHeaderName);
    }

    private TargetInvocationRequest request() {
        return new TargetInvocationRequest(
                null, "tc-1", "Which Java version is required?", "conv-1", Map.of("role", "developer"));
    }

    @Test
    void successfulResponseExtractsAnswerCitationsChunksAndModel() {
        stubFor(post(urlEqualTo("/chat")).willReturn(okJson(
                """
                {"answer":"Java 21","citations":[{"documentId":"doc-1","documentTitle":"Setup"}],
                 "retrievedChunks":[{"chunkId":"c1","documentId":"doc-1","content":"text"}],"model":"demo-model"}
                """)));

        TargetInvocationResult result = adapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.SUCCEEDED);
        assertThat(result.answer()).isEqualTo("Java 21");
        assertThat(result.citations()).hasSize(1);
        assertThat(result.citations().get(0).documentId()).isEqualTo("doc-1");
        assertThat(result.retrievedChunks()).hasSize(1);
        assertThat(result.targetModel()).isEqualTo("demo-model");
        assertThat(result.attemptCount()).isEqualTo(1);
        assertThat(result.latencyMilliseconds()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void nestedJsonPointersResolveCorrectly() {
        stubFor(post(urlEqualTo("/chat")).willReturn(okJson(
                """
                {"result":{"payload":{"answer":"Java 21","model":"demo-model"}}}
                """)));

        TargetSystemConfiguration config = new TargetSystemConfiguration(
                UUID.randomUUID(), "Nested", AdapterType.STANDARD_HTTP, wireMockServer.baseUrl() + "/chat",
                TargetHttpMethod.POST, jsonMapper.createObjectNode(),
                "/result/payload/answer", null, null, "/result/payload/model", null,
                3_000, 2_000, false, AuthenticationType.NONE, null, null);

        TargetInvocationResult result = adapter.invoke(config, request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.SUCCEEDED);
        assertThat(result.answer()).isEqualTo("Java 21");
        assertThat(result.targetModel()).isEqualTo("demo-model");
    }

    @Test
    void emptyAnswerIsInvalidResponseAndNotRetried() {
        stubFor(post(urlEqualTo("/chat")).willReturn(okJson("{\"answer\":\"\"}")));

        TargetInvocationResult result = adapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.INVALID_RESPONSE);
        assertThat(result.errorType()).isEqualTo("MISSING_ANSWER");
        assertThat(result.attemptCount()).isEqualTo(1);
        verify(exactly(1), postRequestedFor(urlEqualTo("/chat")));
    }

    @Test
    void invalidJsonBodyIsInvalidResponseAndNotRetried() {
        stubFor(post(urlEqualTo("/chat"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("not json")));

        TargetInvocationResult result = adapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.INVALID_RESPONSE);
        assertThat(result.errorType()).isEqualTo("INVALID_JSON");
        assertThat(result.attemptCount()).isEqualTo(1);
        verify(exactly(1), postRequestedFor(urlEqualTo("/chat")));
    }

    @Test
    void timeoutIsRetriedUpToMaximumAttemptsThenFails() {
        stubFor(post(urlEqualTo("/chat")).willReturn(okJson("{\"answer\":\"late\"}").withFixedDelay(500)));

        TargetInvocationResult result = adapter.invoke(configuration("/chat", 100, AuthenticationType.NONE, null, null), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.TIMED_OUT);
        assertThat(result.errorType()).isEqualTo("TIMEOUT");
        assertThat(result.attemptCount()).isEqualTo(2);
        verify(exactly(2), postRequestedFor(urlEqualTo("/chat")));
    }

    @Test
    void http401IsNonRetryable() {
        stubFor(post(urlEqualTo("/chat")).willReturn(aResponse().withStatus(401)));

        TargetInvocationResult result = adapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.TARGET_ERROR);
        assertThat(result.httpStatus()).isEqualTo(401);
        assertThat(result.errorType()).isEqualTo("HTTP_401");
        assertThat(result.attemptCount()).isEqualTo(1);
        verify(exactly(1), postRequestedFor(urlEqualTo("/chat")));
    }

    @Test
    void http429IsRetriedThenSucceeds() {
        stubFor(post(urlEqualTo("/chat")).inScenario("retry-429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("retried"));
        stubFor(post(urlEqualTo("/chat")).inScenario("retry-429")
                .whenScenarioStateIs("retried")
                .willReturn(okJson("{\"answer\":\"Java 21\"}")));

        TargetInvocationResult result = adapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.SUCCEEDED);
        assertThat(result.answer()).isEqualTo("Java 21");
        assertThat(result.attemptCount()).isEqualTo(2);
        verify(exactly(2), postRequestedFor(urlEqualTo("/chat")));
    }

    @Test
    void http500IsNonRetryableSinceItIsNotInTheRetryableSet() {
        stubFor(post(urlEqualTo("/chat")).willReturn(aResponse().withStatus(500)));

        TargetInvocationResult result = adapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.TARGET_ERROR);
        assertThat(result.httpStatus()).isEqualTo(500);
        assertThat(result.attemptCount()).isEqualTo(1);
        verify(exactly(1), postRequestedFor(urlEqualTo("/chat")));
    }

    @Test
    void missingOptionalCitationAndChunkArraysYieldEmptyLists() {
        stubFor(post(urlEqualTo("/chat")).willReturn(okJson("{\"answer\":\"Java 21\"}")));

        TargetInvocationResult result = adapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.SUCCEEDED);
        assertThat(result.citations()).isEmpty();
        assertThat(result.retrievedChunks()).isEmpty();
        assertThat(result.targetModel()).isNull();
    }

    @Test
    void oversizedResponseIsRejectedAsInvalidResponse() {
        String hugeAnswer = "x".repeat(2_000);
        stubFor(post(urlEqualTo("/chat")).willReturn(okJson("{\"answer\":\"" + hugeAnswer + "\"}")));

        StandardHttpTargetSystemAdapter tightAdapter = newAdapter(100L, 1);
        TargetInvocationResult result = tightAdapter.invoke(configuration("/chat"), request());

        assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.INVALID_RESPONSE);
        assertThat(result.errorType()).isEqualTo("RESPONSE_TOO_LARGE");
    }

    @Test
    void secretHeaderIsInjectedButNeverAppearsInLogOutput() {
        String secretValue = "super-secret-token-value-12345";
        stubFor(post(urlEqualTo("/chat")).willReturn(okJson("{\"answer\":\"Java 21\"}")));

        Logger adapterLogger = (Logger) LoggerFactory.getLogger(StandardHttpTargetSystemAdapter.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        adapterLogger.addAppender(appender);
        adapterLogger.setLevel(Level.DEBUG);
        try {
            TargetInvocationResult result = adapter.invoke(
                    configuration("/chat", 3_000, AuthenticationType.BEARER_TOKEN, secretValue, null), request());

            assertThat(result.outcome()).isEqualTo(TargetInvocationOutcome.SUCCEEDED);
            verify(exactly(1), postRequestedFor(urlEqualTo("/chat")).withHeader("Authorization", equalTo("Bearer " + secretValue)));

            List<String> logMessages = appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertThat(logMessages).isNotEmpty();
            assertThat(logMessages).noneMatch(message -> message.contains(secretValue));
        } finally {
            adapterLogger.detachAppender(appender);
        }
    }

    @AfterEach
    void resetScenarios() {
        wireMockServer.resetScenarios();
    }
}
