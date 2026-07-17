package com.rageval.workbench.common.csv;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 parser: quoted fields, embedded commas/newlines, and {@code ""} as an escaped
 * quote. Extracted from {@code DatasetImportService} in Phase 3 so the imported-trace CSV import
 * shares one implementation instead of a second hand-rolled copy; deliberately not a CSV library —
 * the need is ~60 lines and the fixed technology list doesn't include one.
 */
public final class CsvParser {

    private CsvParser() {
    }

    public static List<List<String>> parse(InputStream content) {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(content, StandardCharsets.UTF_8))) {
            List<String> currentRow = new ArrayList<>();
            StringBuilder currentField = new StringBuilder();
            boolean inQuotes = false;
            int c;
            boolean anyContent = false;
            while ((c = reader.read()) != -1) {
                char ch = (char) c;
                anyContent = true;
                if (inQuotes) {
                    if (ch == '"') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            currentField.append('"');
                        } else {
                            inQuotes = false;
                            if (next != -1) {
                                reader.reset();
                            }
                        }
                    } else {
                        currentField.append(ch);
                    }
                } else if (ch == '"') {
                    inQuotes = true;
                } else if (ch == ',') {
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                } else if (ch == '\n') {
                    currentRow.add(currentField.toString());
                    currentField.setLength(0);
                    rows.add(currentRow);
                    currentRow = new ArrayList<>();
                } else if (ch == '\r') {
                    // ignore; \n (or end of stream) terminates the row
                } else {
                    currentField.append(ch);
                }
            }
            if (anyContent) {
                currentRow.add(currentField.toString());
                rows.add(currentRow);
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to read CSV content", ex);
        }
        return rows;
    }
}
