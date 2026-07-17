package com.rageval.workbench.common.json;

import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Safely resolves a configured JSON Pointer expression against a response tree. Distinguishes "no
 * pointer configured" and "pointer configured but resolves to nothing" from an actually-present
 * value — callers (adapter response extraction, connection-test diagnostics) need that
 * distinction to report which mappings did or didn't work, rather than silently treating a typo'd
 * pointer the same as an intentionally-absent optional field.
 */
@Component
public final class JsonPointerExtractor {

    public Optional<JsonNode> extract(JsonNode root, String pointerExpression) {
        if (root == null || pointerExpression == null || pointerExpression.isBlank()) {
            return Optional.empty();
        }
        JsonNode result = root.at(pointerExpression);
        if (result == null || result.isMissingNode() || result.isNull()) {
            return Optional.empty();
        }
        return Optional.of(result);
    }

    public Optional<String> extractText(JsonNode root, String pointerExpression) {
        return extract(root, pointerExpression).map(JsonNode::asString);
    }

    public Optional<Integer> extractInt(JsonNode root, String pointerExpression) {
        return extract(root, pointerExpression).filter(JsonNode::isNumber).map(JsonNode::asInt);
    }

    public Optional<Double> extractDouble(JsonNode root, String pointerExpression) {
        return extract(root, pointerExpression).filter(JsonNode::isNumber).map(JsonNode::asDouble);
    }
}
