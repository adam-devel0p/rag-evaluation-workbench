package com.rageval.workbench.target.domain;

/**
 * Per-item JSON Pointers (relative to each citation array element) used when a target's citation
 * shape does not match the standard normalized {@code documentId}/{@code documentTitle}/
 * {@code section}/{@code url}/{@code quotedText} fields. Any {@code null} field falls back to the
 * standard field name.
 */
public record CitationFieldMapping(
        String documentIdPointer,
        String documentTitlePointer,
        String sectionPointer,
        String urlPointer,
        String quotedTextPointer) {
}
