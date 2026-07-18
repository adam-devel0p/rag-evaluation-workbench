package com.rageval.workbench.evaluation.calibration;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;

/**
 * Loader and model for the labelled calibration sample under
 * {@code src/test/resources/calibration/} (spec §14). Test-scope only — calibration is an
 * evaluator-quality exercise, not a production feature.
 */
public record CalibrationDataset(String description, List<CalibrationCase> cases) {

    public static final String RESOURCE = "/calibration/calibration-cases.json";

    /** The 11 categories spec §14 requires the sample to cover. */
    public static final List<String> REQUIRED_CATEGORIES = List.of(
            "correct-and-grounded",
            "correct-but-incomplete",
            "grounded-outdated-context-wrong-vs-reference",
            "unsupported-claim",
            "correct-refusal",
            "refusal-followed-by-disclosure",
            "retrieval-miss",
            "retrieval-ranking-problem",
            "contradictory-context",
            "missing-documentation",
            "irrelevant-answer");

    public record CalibrationChunk(String chunkId, String documentId, int rank, String content) {
    }

    public record CalibrationCase(
            String id,
            String category,
            String question,
            String referenceAnswer,
            boolean answerable,
            boolean shouldRefuse,
            List<CalibrationChunk> contextChunks,
            String answer,
            Map<String, Boolean> humanLabels,
            String notes) {
    }

    public static CalibrationDataset load() {
        try (InputStream stream = CalibrationDataset.class.getResourceAsStream(RESOURCE)) {
            Objects.requireNonNull(stream, "calibration dataset resource not found: " + RESOURCE);
            return JsonMapper.builder().build().readValue(stream, CalibrationDataset.class);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Failed to load the calibration dataset", ex);
        }
    }
}
