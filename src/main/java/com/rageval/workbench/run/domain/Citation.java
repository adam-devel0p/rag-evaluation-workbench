package com.rageval.workbench.run.domain;

import java.util.Map;

/** A citation returned by the target system, persisted as an element of a JSONB array. */
public record Citation(
        String documentId,
        String documentTitle,
        String section,
        String url,
        String quotedText,
        Map<String, Object> metadata) {
}
