# Data Handling and Privacy

This document describes data-handling behavior that is **actually implemented in the running
code** as of Phase 8 (MVP complete) — not aspirational policy. Every claim below is implemented
and most are locked in by tests. It is written for the consultant who must explain to a client
where their data goes. See `CLAUDE.md` and `docs/spec.md` §23 for the binding requirements.

All target data — questions, answers, citations, retrieved chunks, raw responses — is treated as
potentially confidential client data.

## Where data lives

Everything is stored in the local PostgreSQL instance you run (Docker Compose named volume
`rageval-postgres-data`). There is no cloud storage, no telemetry, no external reporting. HTML
reports are rendered server-side on demand from that database and are not written to disk.

## No external LLM calls

Client content (questions, chatbot answers, retrieved-chunk content, reference answers, expected
facts) is sent to exactly one model integration: the locally running **Ollama** server at
`spring.ai.ollama.base-url` (`OLLAMA_BASE_URL`, default `http://localhost:11434`). No external or
paid LLM API is on the classpath or configured — the only Spring AI model starter in `pom.xml` is
`spring-ai-starter-model-ollama`, and `OllamaJudgeClient` is the single code path that sends
content to a model. The only other outbound HTTP client is the target adapter calling the chatbot
you explicitly configured.

Spring AI's prompt/completion observation export **stays disabled**:
`spring.ai.chat.observations.log-prompt=false` and
`spring.ai.chat.observations.log-completion=false` are pinned in `application.properties` (they
are also the Spring AI 2.0.0 defaults — property names and `false` defaults re-verified in
Phase 8 against the resolved `ChatObservationProperties` bytecode), so enabling content export
requires an explicit, visible opt-in in configuration review.

## Logging: identifiers and sizes, never content

- The target adapter logs per attempt only: target-system ID, HTTP status, latency, body byte
  count, or the exception class name on transport failure. Never questions, answers, citations,
  chunk content, bodies, or header values.
- Authorization/API-key headers are never logged on any path — verified by
  `StandardHttpTargetSystemAdapterWireMockTest#secretHeaderIsInjectedButNeverAppearsInLogOutput`,
  which captures the adapter's log output during a real (WireMock-backed) authenticated call and
  asserts the secret never appears, while proving the header genuinely was sent.
- Judge prompts and outputs are never logged; `OllamaJudgeClient` exception messages carry
  exception class names and the configured model name only.
- **Development-only opt-in (spec §23):** `rag-evaluation.logging.verbose-content=true` makes the
  target adapter and the judge client additionally log full payloads and prompts at DEBUG level.
  Enabling it prints a prominent startup warning (`ContentLoggingProperties`); it exists for
  local debugging of request templates and judge prompts and must never be enabled anywhere else.
- **Structured log context (spec §24):** every log line carries the MDC block
  `[cid=… run=… exec=… target=… eval=…]` — `correlationId` for HTTP requests (reused from an
  inbound `X-Correlation-Id` header, echoed on the response, attached to every RFC 9457
  `ProblemDetail`), and `runId`/`executionId`/`targetSystemId`/`evaluatorKey` on run-worker
  threads.

## Metrics carry no client content

Micrometer metrics (`/actuator/metrics`, `/actuator/prometheus`) use only bounded tag values:
outcome/status names, registered evaluator keys, exception class names. No question text, no
client content, no test-case/run/execution IDs as tags (spec §24; tag policy centralized in
`WorkbenchMetrics`, asserted by `HardeningIntegrationTest`).

## Secrets: name only, never the value

`TargetSystem` persists `secretEnvironmentVariable` (the *name* of an environment variable) and
`apiKeyHeaderName`. It has no column capable of holding a secret value.
`SecretResolver.resolve(TargetSystem)` reads the environment at invocation time; the value lives
only in the transient per-call `TargetSystemConfiguration`, is never persisted, never logged, and
appears in no API response (`TargetSystemResponse` has no field for it). If the referenced
variable is unset, the connection test fails fast with a 400 naming the variable rather than
sending a blank credential. `.env.example` documents variable names only; `.env` is git-ignored.

## Response size limits

The adapter reads responses through a bounded stream
(`rag-evaluation.target.maximum-response-bytes`, default 2 MiB) and aborts before buffering more —
an oversized response never fully lands in memory and is recorded as `INVALID_RESPONSE`
(`RESPONSE_TOO_LARGE`), never silently truncated.

## URL validation and redirects

`TargetUrlValidator` rejects any endpoint URL whose scheme is not `http`/`https`. Loopback hosts
are deliberately allowed (local workbench; the demo target lives on localhost). Redirects are
never followed unless the target explicitly sets `followRedirects: true`.

## What is stored per execution, and how to store less

By default a `TestExecution` keeps the full captured evidence: normalized answer, citations,
retrieved chunks, raw response JSON, request payload, latency, tokens, HTTP status, error info.
That default exists because replay (re-judging stored responses without re-calling the chatbot)
and the evidence-first report depend on it.

Two switches reduce capture (spec §23), applied centrally in `RunStateService` so no adapter can
bypass them (proven by `StorageConfigurationIntegrationTest`):

- `rag-evaluation.storage.store-raw-responses=false` — the raw response body is never persisted;
  normalized fields still are. Lost: re-extracting anything from the original payload later.
- `rag-evaluation.storage.store-retrieved-chunk-content=false` — chunks are persisted without
  their content text (IDs, titles, ranks, scores kept). Retrieval metrics still work;
  content-dependent LLM evaluators (groundedness, context relevance) report missing required
  data instead of judging.

Both apply at capture time; already-persisted rows are unaffected, and a replay copies whatever
its source stored.

## Deletion and retention

- `DELETE /api/v1/evaluation-runs/{id}` removes the run and every dependent row — executions,
  metric results, human reviews, findings, imported traces — via the database-level `ON DELETE
  CASCADE` foreign keys (proven end to end by `HardeningIntegrationTest`). A replay run that
  referenced a deleted execution keeps its own copied data; only the source link is nulled.
  QUEUED/RUNNING runs must be cancelled first (409 otherwise).
- **Retention:** there is no automatic expiry — data stays until you delete it. Retention is an
  engagement-level decision: delete runs via the API when an engagement ends, or wipe everything
  with `scripts/reset-local-data.sh` (removes the Postgres volume after explicit confirmation;
  Liquibase recreates an empty schema on the next start; downloaded Ollama models are kept —
  they contain no client data).

## Local-only deployment mode

The documented deployment mode is **local-only**: `compose.yaml` binds PostgreSQL and Ollama to
`127.0.0.1`, and the `local` profile sets `server.address=127.0.0.1`, so neither the data stores
nor the HTTP API — which serves full client content in reports and review contexts and has **no
authentication layer** — is reachable from the network. Removing that binding means choosing a
shared deployment; the application deliberately provides no access control (out of MVP scope),
so you must put your own in front.

## Untrusted-content handling in judge prompts

Every judge system prompt marks the chatbot answer and retrieved context as untrusted data inside
explicit BEGIN/END delimiters, forbids following instructions embedded in them, and requires
answering only in the defined JSON schema (`UntrustedContent`). Retrieved context passed to a
judge is capped at `rag-evaluation.evaluator.maximum-context-characters` (default 30000);
truncation is recorded in evidence metadata and flags the result for human review — never silent.

## Sample limitation language

Suitable for a statement of work or report appendix. **This is engineering documentation, not
legal advice; it claims no certification or regulatory compliance.**

> Evaluation data (questions, chatbot answers, citations, and retrieved document excerpts) is
> processed and stored exclusively on the consultant's machine in a local PostgreSQL database.
> No content is sent to third-party or cloud AI services; automated judging uses a language
> model running locally on the same machine. Data is retained only for the duration of the
> engagement and is deleted afterwards on request. This describes the tool's engineering
> behavior; it is not a compliance certification (e.g. GDPR, SOC 2, ISO 27001), and any formal
> data-processing commitments must be agreed separately.
