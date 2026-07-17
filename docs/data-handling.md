# Data Handling and Privacy

This document describes data-handling behavior that is actually implemented in the running code,
not aspirational policy. It is rewritten (not appended to) as each phase changes what is true. See
`CLAUDE.md` and `docs/spec.md` §23 for the binding requirements.

## What is logged today

The target adapter (`StandardHttpTargetSystemAdapter`) logs one `DEBUG`-level line per invocation
attempt:

```
target invocation attempt targetSystemId=<uuid> httpStatus=<int> latencyMs=<long> bodyBytes=<int>
```

or, on a transport failure:

```
target invocation attempt targetSystemId=<uuid> failed after latencyMs=<long>: <exception class name>
```

This is identifiers and sizes only — never the question, the answer, citations, retrieved-chunk
content, request/response bodies, or header values. There is no verbose-content logging opt-in
yet; one will be added (dev-profile-gated) alongside Phase 3's run execution, which is where full
prompts/answers first exist in a persisted, run-scoped context worth optionally inspecting.

`CorrelationIdFilter` assigns a correlation ID to every HTTP request (reusing an inbound
`X-Correlation-Id` header if present) and puts it in the SLF4J MDC for the duration of the request,
so any log line emitted while handling a request can be tied back to it. `GlobalExceptionHandler`
echoes the same ID back in every `ProblemDetail` response as a `correlationId` extension property.

## Authorization headers are never logged

`StandardHttpTargetSystemAdapter` builds the `Authorization` / API-key header directly from the
resolved secret value and hands it to `RestClient` without ever passing it through a log
statement. This is verified by
`StandardHttpTargetSystemAdapterWireMockTest#secretHeaderIsInjectedButNeverAppearsInLogOutput`,
which attaches a Logback `ListAppender` to the adapter's logger for the duration of a real
(WireMock-backed) call with an actual secret value, and asserts the secret string never appears in
any captured log message — while separately verifying (via WireMock's request-matching) that the
header genuinely was sent.

## Secrets: name only, never the value

`TargetSystem` persists `secretEnvironmentVariable` (the name of an environment variable) and
`apiKeyHeaderName` (the header name to send it under, for `API_KEY_HEADER` auth). It has no column
capable of holding a secret value. `SecretResolver.resolve(TargetSystem)` reads
`System.getenv(secretEnvironmentVariable)` at invocation time and returns it as a transient
`TargetSystemConfiguration.resolvedSecretValue()` — this value lives only for the duration of one
adapter call, is never persisted, and (per above) is never logged. `TargetSystemResponse` (the API
DTO) exposes `secretEnvironmentVariable`/`apiKeyHeaderName` (harmless — they're just names) and has
no field that could carry a resolved value.

If a target requires authentication and the referenced environment variable isn't set,
`POST /api/v1/targets/{id}/test-connection` fails fast with a 400 `ProblemDetail` naming the
missing variable, rather than sending a blank/null credential to the target.

## URL validation

`TargetUrlValidator` rejects any `endpointUrl` whose scheme is not `http` or `https` (RFC 9457
`ProblemDetail`, 400). `localhost` and other loopback/private hosts are explicitly **allowed** —
this is a local evaluation workbench, and pointing it at a target on the same machine (e.g. the
`demo` profile's target, arriving in Phase 3) is the normal setup, not an SSRF concern to guard
against. Redirects are never followed unless a target explicitly sets `followRedirects: true`
(enforced via the JDK `HttpClient`'s `Redirect.NEVER` policy by default).

## Response size limits

`StandardHttpTargetSystemAdapter` reads a target's response through a bounded stream
(`rag-evaluation.target.maximum-response-bytes`, default 2 MiB) and aborts before buffering more
than that — an oversized response never fully lands in memory, and is reported as an
`INVALID_RESPONSE` outcome (`RESPONSE_TOO_LARGE`), not silently truncated or retried.

## Not yet implemented (later phases)

- Configurable storage of raw target responses and retrieved-chunk content
  (`rag-evaluation.storage.store-raw-responses`, `store-retrieved-chunk-content`) — arrives with
  Phase 3's `TestExecution` persistence, where those fields are first written.
- Dev-only opt-in for verbose content logging.
- Run deletion (what's removed, how it's triggered) — Phase 8.
- Disabling Spring AI prompt/completion observation export — Phase 5, when Spring AI's chat client
  is first wired up.
- Local-only deployment mode statement and sample limitation language — Phase 8, alongside the
  full README.

This project never sends target content to a paid external LLM API; the only model integration is
Ollama, which is expected to run locally (Phase 5).
