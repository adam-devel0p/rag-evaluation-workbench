package com.rageval.workbench.evaluation.infrastructure;

import com.rageval.workbench.common.observability.WorkbenchMetrics;
import com.rageval.workbench.configuration.ContentLoggingProperties;
import com.rageval.workbench.configuration.LlmProperties;
import com.rageval.workbench.evaluation.llm.JudgeClient;
import com.rageval.workbench.evaluation.llm.JudgeException;
import com.rageval.workbench.evaluation.llm.JudgeMalformedOutputException;
import com.rageval.workbench.evaluation.llm.JudgeRequest;
import com.rageval.workbench.evaluation.llm.JudgeResponse;
import com.rageval.workbench.evaluation.llm.JudgeUnavailableException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * The production {@link JudgeClient}: one structured call against the locally configured Ollama
 * model via Spring AI. The response schema is enforced twice — Ollama's native structured output
 * (the converter's JSON schema passed as the request {@code format}) plus the converter's format
 * instructions in the prompt — and parsed into the requested record by
 * {@link BeanOutputConverter} (Jackson 3).
 *
 * <p>Bean creation never contacts Ollama: the application starts fine with Ollama entirely
 * unreachable, and a failed call surfaces as {@link JudgeUnavailableException} (server or model
 * unreachable) or {@link JudgeMalformedOutputException} (unparseable output), which the
 * evaluation service records as a metric-status FAILED result. Concurrent calls are bounded by
 * {@code OLLAMA_MAX_CONCURRENCY}.
 *
 * <p>Messages are passed as explicit {@code SystemMessage}/{@code UserMessage} objects, not
 * through the ChatClient text-template path, so untrusted content can never be interpreted as a
 * prompt template.
 */
@Component
public class OllamaJudgeClient implements JudgeClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaJudgeClient.class);

    private final ChatClient chatClient;
    private final LlmProperties properties;
    private final Semaphore concurrencyPermits;
    private final WorkbenchMetrics metrics;
    private final ContentLoggingProperties contentLogging;

    public OllamaJudgeClient(
            ChatModel chatModel,
            LlmProperties properties,
            WorkbenchMetrics metrics,
            ContentLoggingProperties contentLogging) {
        this.chatClient = ChatClient.create(chatModel);
        this.properties = properties;
        this.concurrencyPermits = new Semaphore(Math.max(1, properties.maximumConcurrency()), true);
        this.metrics = metrics;
        this.contentLogging = contentLogging;
    }

    @Override
    public String modelName() {
        return properties.model();
    }

    @Override
    public <T> JudgeResponse<T> judge(JudgeRequest request, Class<T> responseType) {
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(responseType);
        OllamaChatOptions.Builder options = OllamaChatOptions.builder();
        options.model(properties.model());
        options.temperature(properties.temperature());
        options.format(converter.getJsonSchemaMap());

        // Dev-only opt-in (spec §23): prompts contain client content and are never logged unless
        // rag-evaluation.logging.verbose-content is explicitly enabled.
        if (contentLogging.isVerboseContent() && log.isDebugEnabled()) {
            log.debug("judge call system prompt:\n{}\njudge call user prompt:\n{}",
                    request.systemPrompt(), request.userPrompt());
        }
        long start = System.nanoTime();
        String content;
        try {
            concurrencyPermits.acquire();
            try {
                content = chatClient
                        .prompt()
                        .messages(
                                new SystemMessage(request.systemPrompt()),
                                new UserMessage(request.userPrompt() + "\n\n" + converter.getFormat()))
                        .options(options)
                        .call()
                        .content();
            } finally {
                concurrencyPermits.release();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JudgeException("Interrupted while waiting for a judge-call permit", ex);
        } catch (RuntimeException ex) {
            JudgeException classified = classify(ex);
            metrics.recordLlmJudgeCall(
                    classified.getClass().getSimpleName(), Duration.ofNanos(System.nanoTime() - start));
            throw classified;
        }
        long durationMilliseconds = (System.nanoTime() - start) / 1_000_000;
        if (contentLogging.isVerboseContent() && log.isDebugEnabled()) {
            log.debug("judge call raw model output:\n{}", content);
        }

        Duration duration = Duration.ofMillis(durationMilliseconds);
        if (content == null || content.isBlank()) {
            throw recordedAsFailure(
                    new JudgeMalformedOutputException(
                            "The judge model returned an empty response instead of the required schema"),
                    duration);
        }
        T output;
        try {
            output = converter.convert(content);
        } catch (RuntimeException ex) {
            throw recordedAsFailure(
                    new JudgeMalformedOutputException(
                            "The judge model's response could not be mapped onto " + responseType.getSimpleName(), ex),
                    duration);
        }
        if (output == null) {
            throw recordedAsFailure(
                    new JudgeMalformedOutputException(
                            "The judge model's response converted to null instead of " + responseType.getSimpleName()),
                    duration);
        }
        metrics.recordLlmJudgeCall(null, duration);
        return new JudgeResponse<>(output, properties.model(), durationMilliseconds);
    }

    private JudgeException recordedAsFailure(JudgeException exception, Duration duration) {
        metrics.recordLlmJudgeCall(exception.getClass().getSimpleName(), duration);
        return exception;
    }

    /**
     * Maps transport-level failures (Ollama down, host unknown, model not pulled → 404) to
     * {@link JudgeUnavailableException}; everything else stays a generic judge failure. Exception
     * messages deliberately carry no prompt content.
     */
    private JudgeException classify(RuntimeException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException
                    || cause instanceof UnknownHostException
                    || cause instanceof SocketTimeoutException
                    || cause instanceof ResourceAccessException) {
                return new JudgeUnavailableException(
                        "The Ollama evaluator model is unreachable (base URL connection failed)", ex);
            }
            if (cause instanceof RestClientResponseException response && response.getStatusCode().value() == 404) {
                return new JudgeUnavailableException(
                        "The Ollama server answered 404 for model \"" + properties.model()
                                + "\" — the model is probably not pulled",
                        ex);
            }
        }
        return new JudgeException("The judge call failed: " + ex.getClass().getSimpleName(), ex);
    }
}
