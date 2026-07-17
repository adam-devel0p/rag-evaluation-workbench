package com.rageval.workbench.common.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Replaces exact placeholder text nodes ({@code {{question}}}, {@code {{conversationId}}}, {@code
 * {{testCaseId}}}, {@code {{userContext}}}) inside a target's JSON request template. This walks the
 * Jackson tree and only ever compares/replaces whole text-node values — it never does string
 * substitution over serialized JSON, so a placeholder can never accidentally match inside an
 * unrelated string or corrupt JSON structure/escaping.
 *
 * <p>When {@code {{userContext}}} is the entire value of a node, it is spliced in as the actual
 * JSON object (via {@link JsonMapper#valueToTree}), not as a quoted string — the template author
 * writes {@code "userContext": "{{userContext}}"} and gets back {@code "userContext": {...}}.
 */
@Component
public class RequestTemplateSubstitutor {

    private final JsonMapper jsonMapper;

    public RequestTemplateSubstitutor(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public JsonNode substitute(JsonNode template, PlaceholderValues values) {
        if (template == null) {
            return jsonMapper.createObjectNode();
        }
        return substituteNode(template, values);
    }

    private JsonNode substituteNode(JsonNode node, PlaceholderValues values) {
        if (node.isObject()) {
            ObjectNode result = jsonMapper.createObjectNode();
            node.properties().forEach(entry -> result.set(entry.getKey(), substituteNode(entry.getValue(), values)));
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = jsonMapper.createArrayNode();
            node.forEach(child -> result.add(substituteNode(child, values)));
            return result;
        }
        if (node.isString()) {
            return substitutePlaceholder(node.asString(), values);
        }
        return node;
    }

    private JsonNode substitutePlaceholder(String text, PlaceholderValues values) {
        return switch (text) {
            case RequestTemplatePlaceholders.QUESTION ->
                    JsonNodeFactory.instance.stringNode(values.question() != null ? values.question() : "");
            case RequestTemplatePlaceholders.CONVERSATION_ID -> nullableString(values.conversationId());
            case RequestTemplatePlaceholders.TEST_CASE_ID -> nullableString(values.testCaseId());
            case RequestTemplatePlaceholders.USER_CONTEXT -> values.userContext() != null
                    ? jsonMapper.valueToTree(values.userContext())
                    : JsonNodeFactory.instance.nullNode();
            default -> JsonNodeFactory.instance.stringNode(text);
        };
    }

    private JsonNode nullableString(String value) {
        return value != null ? JsonNodeFactory.instance.stringNode(value) : JsonNodeFactory.instance.nullNode();
    }
}
