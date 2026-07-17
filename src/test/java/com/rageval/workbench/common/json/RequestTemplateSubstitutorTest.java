package com.rageval.workbench.common.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RequestTemplateSubstitutorTest {

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RequestTemplateSubstitutor substitutor = new RequestTemplateSubstitutor(jsonMapper);

    @Test
    void replacesExactPlaceholderTextNodes() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("question", "{{question}}");
        template.put("conversationId", "{{conversationId}}");
        template.put("testCaseId", "{{testCaseId}}");

        JsonNode result = substitutor.substitute(
                template, new PlaceholderValues("What version?", "conv-1", "tc-1", null));

        assertThat(result.at("/question").asString()).isEqualTo("What version?");
        assertThat(result.at("/conversationId").asString()).isEqualTo("conv-1");
        assertThat(result.at("/testCaseId").asString()).isEqualTo("tc-1");
    }

    @Test
    void splicesUserContextAsActualJsonObjectNotAQuotedString() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("userContext", "{{userContext}}");

        JsonNode result = substitutor.substitute(
                template, new PlaceholderValues("q", null, null, Map.of("role", "developer")));

        JsonNode userContext = result.at("/userContext");
        assertThat(userContext.isObject()).isTrue();
        assertThat(userContext.at("/role").asString()).isEqualTo("developer");
    }

    @Test
    void leavesNonPlaceholderStringsUntouched() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("staticField", "this is not a placeholder {{question}} embedded");

        JsonNode result = substitutor.substitute(template, new PlaceholderValues("real question", null, null, null));

        assertThat(result.at("/staticField").asString()).isEqualTo("this is not a placeholder {{question}} embedded");
    }

    @Test
    void recursesThroughNestedObjectsAndArrays() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.putObject("nested").put("question", "{{question}}");
        template.putArray("list").add("{{conversationId}}").add(42);

        JsonNode result = substitutor.substitute(template, new PlaceholderValues("q", "conv-9", null, null));

        assertThat(result.at("/nested/question").asString()).isEqualTo("q");
        assertThat(result.at("/list/0").asString()).isEqualTo("conv-9");
        assertThat(result.at("/list/1").asInt()).isEqualTo(42);
    }

    @Test
    void missingConversationIdBecomesNullNode() {
        ObjectNode template = jsonMapper.createObjectNode();
        template.put("conversationId", "{{conversationId}}");

        JsonNode result = substitutor.substitute(template, new PlaceholderValues("q", null, null, null));

        assertThat(result.at("/conversationId").isNull()).isTrue();
    }

    @Test
    void nullTemplateProducesEmptyObject() {
        JsonNode result = substitutor.substitute(null, new PlaceholderValues("q", null, null, null));

        assertThat(result.isObject()).isTrue();
        assertThat(result.properties()).isEmpty();
    }
}
