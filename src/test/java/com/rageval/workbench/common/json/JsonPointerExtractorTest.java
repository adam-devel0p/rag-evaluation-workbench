package com.rageval.workbench.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JsonPointerExtractorTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final JsonPointerExtractor extractor = new JsonPointerExtractor();

    @Test
    void extractsTextAtPointer() {
        JsonNode root = jsonMapper.readTree("{\"answer\":\"Java 21\"}");
        assertThat(extractor.extractText(root, "/answer")).contains("Java 21");
    }

    @Test
    void extractsNestedTextAtPointer() {
        JsonNode root = jsonMapper.readTree("{\"result\":{\"payload\":{\"answer\":\"Java 21\"}}}");
        assertThat(extractor.extractText(root, "/result/payload/answer")).contains("Java 21");
    }

    @Test
    void returnsEmptyWhenPointerIsBlankOrNull() {
        JsonNode root = jsonMapper.readTree("{\"answer\":\"Java 21\"}");
        assertThat(extractor.extractText(root, null)).isEmpty();
        assertThat(extractor.extractText(root, "")).isEmpty();
        assertThat(extractor.extractText(root, "  ")).isEmpty();
    }

    @Test
    void returnsEmptyWhenPointerDoesNotResolve() {
        JsonNode root = jsonMapper.readTree("{\"answer\":\"Java 21\"}");
        assertThat(extractor.extractText(root, "/missing")).isEmpty();
    }

    @Test
    void returnsEmptyWhenValueIsExplicitlyNull() {
        JsonNode root = jsonMapper.readTree("{\"answer\":null}");
        assertThat(extractor.extractText(root, "/answer")).isEmpty();
    }

    @Test
    void extractsIntAndDouble() {
        JsonNode root = jsonMapper.readTree("{\"inputTokens\":12,\"score\":0.87}");
        assertThat(extractor.extractInt(root, "/inputTokens")).contains(12);
        assertThat(extractor.extractDouble(root, "/score")).contains(0.87);
    }

    @Test
    void doesNotCoerceNonNumericTextIntoANumber() {
        JsonNode root = jsonMapper.readTree("{\"inputTokens\":\"not-a-number\"}");
        assertThat(extractor.extractInt(root, "/inputTokens")).isEmpty();
    }
}
