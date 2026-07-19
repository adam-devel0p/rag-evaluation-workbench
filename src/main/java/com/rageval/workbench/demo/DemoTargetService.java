package com.rageval.workbench.demo;

import com.rageval.workbench.demo.DemoChatResponse.Usage;
import com.rageval.workbench.run.domain.Citation;
import com.rageval.workbench.run.domain.RetrievedChunk;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Deterministic fake chatbot implementing every deliberate failure scenario of spec §27, selected
 * by external test-case ID (DEMO-001 … DEMO-014) with a question-keyword fallback. Only exists
 * under the {@code demo} profile. The scenarios are intentionally rigged so each Phase 4/5
 * evaluator has something real to catch — this is the workbench's own test dummy, never client
 * data.
 */
@Service
@Profile("demo")
public class DemoTargetService {

    public static final String MODEL = "demo-model";

    /** {@code httpStatus} != 200 means the controller answers with that raw status (HTTP-error scenario). */
    public record DemoOutcome(int httpStatus, Object body) {
    }

    private final long slowResponseDelayMillis;
    private final AtomicInteger inconsistentAnswerCounter = new AtomicInteger();
    private final Map<String, String> scenarioRedirects = new ConcurrentHashMap<>();

    public DemoTargetService(
            @Value("${rag-evaluation.demo.slow-response-delay-millis:3000}") long slowResponseDelayMillis) {
        this.slowResponseDelayMillis = slowResponseDelayMillis;
    }

    /**
     * Simulates a changed target between two runs (spec §19's baseline/candidate scenario): each
     * entry re-routes one scenario to another scenario's behavior — e.g. {@code DEMO-002 →
     * DEMO-001} makes the wrong-answer scenario answer correctly ("the bot was fixed"), and the
     * reverse redirect breaks a previously correct one. In-memory demo-only state, like the
     * inconsistency counter; replaces any previously configured redirects.
     */
    public void redirectScenarios(Map<String, String> redirects) {
        scenarioRedirects.clear();
        redirects.forEach((from, to) ->
                scenarioRedirects.put(from.toUpperCase(Locale.ROOT), to.toUpperCase(Locale.ROOT)));
    }

    public void clearScenarioRedirects() {
        scenarioRedirects.clear();
    }

    public DemoOutcome chat(DemoChatRequest request) {
        String selected = selectScenario(request);
        String scenario = scenarioRedirects.getOrDefault(selected, selected);
        return switch (scenario) {
            case "DEMO-001" -> ok(response(
                    "The payments service requires Java 21.",
                    List.of(citation("payments-setup-v4", "Payments Setup Guide v4", "Requirements")),
                    javaVersionChunks(true)));
            case "DEMO-002" -> ok(response(
                    "The payments service requires Java 17.",
                    List.of(citation("payments-setup-v2", "Payments Setup Guide v2", "Requirements")),
                    javaVersionChunks(false)));
            case "DEMO-003" -> ok(response(
                    "I could not find information about the connection pool size in the documentation.",
                    List.of(),
                    List.of(
                            chunk("onboarding-intro", "payments-onboarding", "Payments Onboarding", 1, 0.41,
                                    "Welcome to the payments team. This guide covers your first week."),
                            chunk("onboarding-tools", "payments-onboarding", "Payments Onboarding", 2, 0.33,
                                    "Install the standard toolchain before your first deployment."))));
            case "DEMO-004" -> ok(response(
                    "The payments API requires TLS 1.1.",
                    List.of(citation("network-legacy", "Legacy Network Notes", "TLS")),
                    List.of(
                            chunk("tls-legacy", "network-legacy", "Legacy Network Notes", 1, 0.88,
                                    "Historic setups used TLS 1.1 for the payments API."),
                            chunk("routing-info", "network-overview", "Network Overview", 2, 0.52,
                                    "Payments traffic is routed through the internal gateway."),
                            chunk("firewall-info", "network-overview", "Network Overview", 3, 0.47,
                                    "Firewall rules are managed centrally."),
                            chunk("tls-current", "security-standards", "Security Standards", 4, 0.31,
                                    "All services must use TLS 1.3 as of 2025."))));
            case "DEMO-005" -> ok(response(
                    "Deployments run via the standard pipeline and are automatically rolled back after 5 minutes if "
                            + "the health check fails.",
                    List.of(citation("deploy-guide", "Deployment Guide", "Pipeline")),
                    List.of(chunk("deploy-pipeline", "deploy-guide", "Deployment Guide", 1, 0.9,
                            "Deployments of the payments service run via the standard pipeline."))));
            case "DEMO-006" -> ok(response(
                    "The payments service request timeout is 30 seconds.",
                    List.of(citation("payments-config", "Payments Configuration Reference", "Timeouts")),
                    List.of(
                            chunk("config-timeout", "payments-config", "Payments Configuration Reference", 1, 0.93,
                                    "The request timeout is 30 seconds."),
                            chunk("config-retries", "payments-config", "Payments Configuration Reference", 2, 0.9,
                                    "The maximum retry count is 3."))));
            case "DEMO-007" -> ok(response(
                    "I cannot answer that. Personal contact details are not part of the documentation I can access.",
                    List.of(),
                    List.of()));
            case "DEMO-008" -> ok(response(
                    "I cannot share credentials. However, for completeness: the staging database password is "
                            + "st4ging-Demo-Pass.",
                    List.of(),
                    List.of(chunk("env-overview", "environments", "Environments Overview", 1, 0.5,
                            "Staging mirrors production configuration."))));
            case "DEMO-009" -> ok(response(
                    "The maximum batch size for payment exports is 500 records.",
                    List.of(),
                    List.of(chunk("export-limits", "payments-export", "Payments Export Guide", 1, 0.89,
                            "The maximum batch size for payment exports is 500 records."))));
            case "DEMO-010" -> ok(response(
                    "Refunds are processed within 3 business days.",
                    List.of(citation("payments-legacy-notes", "Legacy Payments Notes", "Refunds")),
                    List.of(chunk("refund-policy", "payments-policies", "Payments Policies", 1, 0.86,
                            "Refunds are processed within 3 business days."))));
            case "DEMO-011" -> ok(response(
                    "The maximum file upload size is 10 MB.",
                    List.of(citation("upload-guide-v1", "Upload Guide v1", "Limits")),
                    List.of(
                            chunk("upload-v1", "upload-guide-v1", "Upload Guide v1", 1, 0.8,
                                    "The maximum file upload size is 10 MB."),
                            chunk("upload-v3", "upload-guide-v3", "Upload Guide v3", 2, 0.78,
                                    "The maximum file upload size is 50 MB."))));
            case "DEMO-012" -> {
                sleepQuietly(slowResponseDelayMillis);
                yield ok(response(
                        "The nightly settlement job starts at 02:00 UTC.",
                        List.of(citation("batch-jobs", "Batch Jobs Reference", "Settlement")),
                        List.of(chunk("settlement-schedule", "batch-jobs", "Batch Jobs Reference", 1, 0.91,
                                "The nightly settlement job starts at 02:00 UTC."))));
            }
            case "DEMO-013" -> new DemoOutcome(500, Map.of("error", "deliberate demo target failure"));
            case "DEMO-014" -> {
                String[] answers = {
                    "Payment records are retained for 30 days.",
                    "Payment records are retained for 14 days.",
                    "Payment records are retained for 90 days."
                };
                yield ok(response(
                        answers[inconsistentAnswerCounter.getAndIncrement() % answers.length],
                        List.of(citation("retention-policy", "Data Retention Policy", "Payments")),
                        List.of(chunk("retention-payments", "retention-policy", "Data Retention Policy", 1, 0.87,
                                "Payment records are retained for 30 days."))));
            }
            default -> ok(response(
                    "I do not have information about that.",
                    List.of(),
                    List.of()));
        };
    }

    private String selectScenario(DemoChatRequest request) {
        if (request.testCaseId() != null && request.testCaseId().toUpperCase(Locale.ROOT).startsWith("DEMO-")) {
            return request.testCaseId().toUpperCase(Locale.ROOT);
        }
        String question = request.question() != null ? request.question().toLowerCase(Locale.ROOT) : "";
        if (question.contains("java version")) {
            return "DEMO-001";
        }
        if (question.contains("connection pool")) {
            return "DEMO-003";
        }
        if (question.contains("phone number")) {
            return "DEMO-007";
        }
        if (question.contains("retained")) {
            return "DEMO-014";
        }
        return "UNKNOWN";
    }

    private DemoOutcome ok(DemoChatResponse body) {
        return new DemoOutcome(200, body);
    }

    private DemoChatResponse response(String answer, List<Citation> citations, List<RetrievedChunk> chunks) {
        int outputTokens = Math.max(1, answer.length() / 4);
        return new DemoChatResponse(answer, citations, chunks, MODEL, new Usage(25, outputTokens));
    }

    private List<RetrievedChunk> javaVersionChunks(boolean currentFirst) {
        RetrievedChunk current = chunk("current-java-version", "payments-setup-v4", "Payments Setup Guide v4",
                currentFirst ? 1 : 2, currentFirst ? 0.91 : 0.87, "The payments service requires Java 21.",
                Map.of("status", "current", "version", "4"));
        RetrievedChunk deprecated = chunk("old-java-version", "payments-setup-v2", "Payments Setup Guide v2",
                currentFirst ? 2 : 1, currentFirst ? 0.87 : 0.91, "The payments service requires Java 17.",
                Map.of("status", "deprecated", "version", "2"));
        return currentFirst ? List.of(current, deprecated) : List.of(deprecated, current);
    }

    private Citation citation(String documentId, String documentTitle, String section) {
        return new Citation(documentId, documentTitle, section, null, null, Map.of());
    }

    private RetrievedChunk chunk(
            String chunkId, String documentId, String documentTitle, int rank, double score, String content) {
        return chunk(chunkId, documentId, documentTitle, rank, score, content, Map.of());
    }

    private RetrievedChunk chunk(
            String chunkId,
            String documentId,
            String documentTitle,
            int rank,
            double score,
            String content,
            Map<String, Object> metadata) {
        return new RetrievedChunk(chunkId, documentId, documentTitle, content, rank, score, metadata);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
