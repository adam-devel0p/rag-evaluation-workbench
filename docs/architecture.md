# Architecture

This document records significant architectural decisions as the project is built, phase by
phase. It is a stub after Phase 0 — most of the real content lands from Phase 1 onward.

## Phase 0 findings

### Repository state at start

The repository was **not** empty: a Spring Initializr skeleton already existed (pom.xml, Maven
Wrapper, one application class, one context-loads test, a `TestcontainersConfiguration` wiring
Postgres + Ollama Testcontainers, empty `src/main/resources/db/changelog/`). Per the working
agreement this was verified and adjusted in place rather than regenerated.

Initializr derived the base package from the artifact ID as `com.rageval.rag_evaluation_workbench`
(underscore, non-compliant with the spec's `com.rageval.workbench`). Fixed by moving
`RagEvaluationWorkbenchApplication`, `RagEvaluationWorkbenchApplicationTests`,
`TestcontainersConfiguration`, and `TestRagEvaluationWorkbenchApplication` into
`com.rageval.workbench` and deleting the old package directories. The application class name was
already correct.

### Resolved dependency versions

Confirmed via `./mvnw dependency:tree`:

- `org.springframework.boot:spring-boot-starter-parent:4.1.0` — all `spring-boot-starter-*`
  artifacts resolve to **4.1.0** exactly.
- `org.springframework.ai:spring-ai-bom:2.0.0` (imported in `dependencyManagement`) — all
  `spring-ai-*` artifacts (client-chat, model, ollama, commons, autoconfigure-*, retry,
  template-st) resolve to **2.0.0** exactly. No version drift observed.
- Ollama starter dependency is `spring-ai-starter-model-ollama` (the current, non-deprecated
  name) — already correct in the Initializr-generated `pom.xml`.
- Jackson: `tools.jackson.core:jackson-databind:3.1.4` is on the classpath (Jackson 3, package
  `tools.jackson.*`), confirming the CLAUDE.md warning. `com.fasterxml.jackson.core:jackson-annotations:2.21`
  is also present as a transitive (annotations-only) dependency — expected, since Jackson 3 still
  reuses the `com.fasterxml.jackson.annotation` package for annotations; only the core/databind
  packages relocated to `tools.jackson`. Do not import `com.fasterxml.jackson.databind.ObjectMapper`
  anywhere — use `tools.jackson.databind.json.JsonMapper`.
- Hibernate: `org.hibernate.orm:hibernate-core:7.4.1.Final`.
- Liquibase: `org.liquibase:liquibase-core:5.0.3`.

### Liquibase changelog placeholder

`spring-boot-starter-liquibase` fails application context startup if
`classpath:/db/changelog/db.changelog-master.yaml` does not exist (Spring Boot's default
`spring.liquibase.change-log` path). The generated skeleton had an empty `db/changelog/`
directory, which broke the context-loads test. Added a placeholder
`db.changelog-master.yaml` with an empty `databaseChangeLog: []` list so the build is green
before Phase 1 introduces the real schema changesets. This file will be replaced with real
`include`s in Phase 1 — it currently owns no schema.

### Spring AI 2.0.0 evaluator API surface

Inspected via `javap` against the resolved jars (not assumed from memory or older Spring AI
versions), since CLAUDE.md flags this as a hard requirement.

**`org.springframework.ai.evaluation.Evaluator`** (module `spring-ai-commons`) — the generic
Spring AI evaluator contract:

```java
public interface Evaluator {
    EvaluationResponse evaluate(EvaluationRequest evaluationRequest);
    default String doGetSupportingData(EvaluationRequest evaluationRequest);
}
```

**`EvaluationRequest`** (`spring-ai-commons`) — three constructors, all converging on:

```java
new EvaluationRequest(String userText, List<org.springframework.ai.document.Document> dataList, String responseContent)
```

`Document` (from `spring-ai-commons`) has `id`, `text`, `media`, `metadata`, `score` — a clean
mapping target for our `RetrievedChunk` record (`chunkId`→id is lossy since `Document.id` is a
single string; we will carry `documentId`/`content`/`metadata` through and use `chunkId` as the
`Document` id).

**`EvaluationResponse`** (`spring-ai-commons`):

```java
public class EvaluationResponse {
    boolean isPass();
    float getScore();
    String getFeedback();
    Map<String, Object> getMetadata();
}
```

**Surprise / architectural consequence:** the built-in response shape is a single
pass/score/feedback-string/metadata-map tuple — there is **no structured per-claim breakdown**.
This confirms the spec's own hedge in §13.2/§13.5 ("do not force it into the architecture if a
custom structured evaluator gives better evidence"). Our `GroundednessEvaluator` needs per-claim
`SUPPORTED / PARTIALLY_SUPPORTED / CONTRADICTED / NOT_IN_CONTEXT` verdicts with supporting chunk
IDs — `FactCheckingEvaluator`'s single free-text `feedback` string cannot carry that. Decision
(to confirm in Phase 5): build `GroundednessEvaluator` and `AnswerRelevanceEvaluator` as custom
`EvaluationStrategy` implementations using our own `ChatClient` + structured-output call (mapped
to the records in spec §13), and optionally run Spring AI's `FactCheckingEvaluator` /
`RelevancyEvaluator` alongside as a cheap secondary signal (stored as extra evidence), not as the
primary evidence source.

**`FactCheckingEvaluator`** (module `spring-ai-client-chat`, package
`org.springframework.ai.chat.evaluation`):

```java
public class FactCheckingEvaluator implements Evaluator {
    protected FactCheckingEvaluator(ChatClient.Builder, String evaluationPrompt);
    public static FactCheckingEvaluator forBespokeMinicheck(ChatClient.Builder);
    public static Builder builder(ChatClient.Builder);
    public EvaluationResponse evaluate(EvaluationRequest);
}
```

Requires a `ChatClient.Builder` (i.e. a configured chat model — Ollama in our case). Has a
special-cased factory for the "Bespoke-Minicheck" fact-checking model; otherwise takes a custom
evaluation prompt string via the builder. No constructor is public — must go through
`builder(...)` or `forBespokeMinicheck(...)`.

**`RelevancyEvaluator`** (same module/package):

```java
public class RelevancyEvaluator implements Evaluator {
    public RelevancyEvaluator(ChatClient.Builder);
    public static Builder builder();
}
```

Public single-arg constructor plus a builder that also accepts a custom `PromptTemplate`.

Both evaluators need a `ChatClient.Builder` wired to the Ollama chat model at construction time —
this will come from Spring AI's autoconfigured `ChatClient.Builder` bean (backed by
`OllamaChatModel`), which is only meaningfully usable when Ollama is reachable. Per CLAUDE.md,
these must degrade to a `FAILED` evaluator-metric-result (never a chatbot failure) when Ollama is
down, not block application startup — this is enforced at the `EvaluationStrategy` wrapper level,
not by these Spring AI classes themselves (they will simply throw/fail their HTTP call).

### Package layout

Only the base package and application class exist so far. The full module layout under
`com.rageval.workbench` (`common`, `target`, `dataset`, `run`, `evaluation`, `classification`,
`review`, `report`, `comparison`, `configuration`) is created starting Phase 1, driven by the
persistence model.

## Phase 1 notes

### JSONB mapping: Hibernate's built-in Jackson 3 format mapper, not a hand-rolled converter

Verified (not assumed) by inspecting the resolved `hibernate-core-7.4.1.Final.jar` with `javap`:
Hibernate 7.4.1 ships `org.hibernate.type.format.jackson.Jackson3JsonFormatMapper`, which targets
`tools.jackson.databind.json.JsonMapper` (Jackson 3) directly, alongside the classic
`JacksonJsonFormatMapper` (Jackson 2). `JacksonIntegration` auto-detects which one to register as
the default `hibernate.type.json_format_mapper` based on what's actually on the classpath. Checked
`./mvnw dependency:tree`: the project has `tools.jackson.core:jackson-databind:3.1.4` but no
`com.fasterxml.jackson.core:jackson-databind` (2.x) — only `jackson-annotations:2.21`, which
Jackson 3 itself depends on for annotation compatibility, not a competing databind. So Hibernate's
auto-detection resolves to `Jackson3JsonFormatMapper` with zero extra configuration.

Consequence: entity fields annotated `@JdbcTypeCode(SqlTypes.JSON)` map directly to Java
records / `List<record>` / `Map<String,Object>` / `tools.jackson.databind.JsonNode` — no manual
`AttributeConverter`, no `String` intermediate column type. Confirmed working end-to-end (not just
compiling) by `PersistenceIntegrationTest`, which persists and reloads every JSONB-mapped field
across the full entity graph.

### JSON field type choices

Three different Java shapes were used for JSONB columns, chosen per how the data is actually
consumed downstream:

- **Structured records** (`ExpectedFact`, `Citation`, `RetrievedChunk`, `EvaluationEvidence`) for
  fields with a spec-defined shape — used as `List<Record>` fields on `EvaluationTestCase`,
  `TestExecution`, `EvaluationMetricResult`, `EvaluationFinding`. Gives compile-time safety
  wherever these are read/written.
- **`tools.jackson.databind.JsonNode`** for fields that are genuinely opaque, arbitrary-shaped JSON
  that Phase 2's request-templating/JSON-Pointer code needs to walk as a tree:
  `TargetSystem.requestTemplate`, `TestExecution.requestPayload`, `TestExecution.rawResponse`.
  Matches the CLAUDE.md instruction to write Jackson-tree-traversal code, not string-substitution,
  against these fields.
- **`Map<String,Object>` / `List<String>`** for simple flexible data with no tree-walking need:
  `EvaluationTestCase.userContext`, `EvaluationRun.evaluatorConfiguration`,
  `EvaluationTestCase.tags` / `expectedDocumentIds`, `EvaluationRun.enabledEvaluatorKeys`.

### `@Version` placement follows the spec's own field lists, not a blanket rule

CLAUDE.md says "`@Version` optimistic locking on mutable aggregates," which could read as "every
entity." Spec section 6 is more precise: `TargetSystem`, `EvaluationDataset`, and `EvaluationRun`
explicitly list `version` in their required-fields block; `EvaluationTestCase`, `TestExecution`,
`EvaluationMetricResult`, `HumanReview`, and `EvaluationFinding` do not. Followed the spec's
explicit lists: `EvaluationTestCase` is part of the `EvaluationDataset` aggregate (a `READY`
dataset rejects test-case edits outright — see `EvaluationDataset.isModifiable()` — so the
dataset's own version is the concurrency boundary, not an independent one on each test case).
`TestExecution`, `EvaluationMetricResult`, `HumanReview`, and `EvaluationFinding` are effectively
append-only/immutable once created (no update endpoints for them in spec §21 — `HumanReview`
creation is itself the "update" mechanism, layered on top without touching the automated result),
so optimistic locking doesn't apply.

### FK delete behavior

- `evaluation_test_case.dataset_id` → `evaluation_dataset.id`: `ON DELETE CASCADE`. Test cases are
  owned by their dataset; there's no standalone lifecycle for one without the other.
- `evaluation_run.target_system_id` / `evaluation_run.dataset_id`: default `NO ACTION` (no
  `onDelete` specified). Matches spec §21: "Delete should be rejected when historical runs depend
  on the target. Prefer disabling it." The FK gives a hard backstop; Phase 2's target/dataset
  delete endpoints are expected to check for dependent runs first and return a friendlier error.
- `test_execution.evaluation_run_id` → `evaluation_run.id`: `ON DELETE CASCADE`. Executions have no
  meaning outside their run; deleting a run (spec §23's run-deletion requirement, Phase 8) should
  take its executions with it.
- `test_execution.test_case_id`: default `NO ACTION`, same reasoning as target/dataset above —
  historical executions shouldn't be orphaned by a test-case edit/removal path introduced later.
- `test_execution.source_execution_id` (self-referential, used by `REPLAY` runs): `ON DELETE SET
  NULL`. Avoids blocking deletion of an original run's executions just because a later replay run
  references them.
- `evaluation_metric_result.test_execution_id`, `human_review.metric_result_id`,
  `evaluation_finding.evaluation_run_id` / `evaluation_finding.test_execution_id`: all `ON DELETE
  CASCADE` — these are all pure child data with no independent meaning.

### `EvaluationFinding.testExecutionId` kept non-nullable

Spec §6.8 lists `testExecutionId` as a required field with no hedge about run-level findings. Kept
it `NOT NULL` rather than speculatively making it nullable for a hypothetical future run-wide
finding type (e.g. an aggregate "high latency across the run" finding). If Phase 6 needs run-level
findings not tied to one execution, that's a deliberate, documented schema change then — not a
guess now.

### Package placement judgment calls

- Spring Data JPA repository interfaces live in each module's `infrastructure` package, not
  `domain` — they're a JPA-specific persistence detail, not the entity/value-object model itself.
- `Severity` lives in `dataset.domain` (it's first defined there, on `EvaluationTestCase`) and is
  reused by `classification.domain.EvaluationFinding` via a plain cross-package import — CLAUDE.md
  warns against over-applying DDD/creating meaningless interfaces, so no shared `common.domain`
  module was invented just to host one shared enum.
- `EvaluationFinding` lives under `classification.domain` (matches the flat `classification`
  package CLAUDE.md's tree already names), not under `evaluation`, since it's produced by Phase 4's
  failure-classification rules rather than by an individual evaluator.

### Also fixed while here

Added `spring.jpa.open-in-view=false` to `application.properties`. Not spec-mandated, but Phase 0's
build log showed Spring Boot's default-open-in-view warning on every startup; disabling it is the
standard fix and costs nothing at this phase (no view-layer code exists yet to depend on the
lazy-loading-in-view behavior it enables).

## Phase 2 notes

### Jackson 3 tree API surface actually used (verified via `javap` against the resolved jar, not assumed)

Confirmed against `jackson-databind-3.1.4.jar` before writing `RequestTemplateSubstitutor` /
`JsonPointerExtractor`, since CLAUDE.md flags Jackson 3 as a rename-heavy migration:

- `TextNode` is gone — Jackson 3 renamed it **`StringNode`**, created via
  `JsonNodeFactory.instance.stringNode(String)` or `ObjectMapper.valueToTree`. `JsonNode.isTextual()`
  is deprecated in favor of `isString()`; `asText()` still exists but `asString()` is the
  non-deprecated accessor.
- `ObjectMapper`/`JsonMapper` keeps `createObjectNode()`, `createArrayNode()`, `readTree(...)`
  (throws unchecked `JacksonException`, not a checked `IOException`), `writeValueAsBytes(Object)`,
  `treeToValue(JsonNode, TypeReference<T>)` (the `TypeReference` type itself moved to
  `tools.jackson.core.type`).
- `JsonNode.at(String)` / `at(JsonPointer)` work as in Jackson 2 for pointer resolution.

### Request-template substitution: exact-text-node replacement, not string interpolation

`RequestTemplateSubstitutor` walks the parsed `JsonNode` tree recursively and only ever replaces a
text node whose *entire* value equals one of the four placeholder tokens
(`{{question}}`/`{{conversationId}}`/`{{testCaseId}}`/`{{userContext}}`). This was a hard CLAUDE.md
requirement (no unsafe global string substitution over serialized JSON) verified by
`RequestTemplateSubstitutorTest#leavesNonPlaceholderStringsUntouched`, which asserts a string that
merely *contains* `{{question}}` as a substring is left alone. `{{userContext}}` is spliced in via
`JsonMapper.valueToTree(Map)` when it is the complete value of a node, producing a real nested
JSON object rather than a quoted string — also covered by a dedicated test.

### `TargetInvocationOutcome` is its own enum, not a reuse of `ExecutionStatus`

`target.domain` needed a small terminal-outcome vocabulary (`SUCCEEDED`/`TIMED_OUT`/
`TARGET_ERROR`/`INVALID_RESPONSE`) for `TargetInvocationResult`. `run.domain.ExecutionStatus` has a
superset of these values already, but reusing it would make `target` depend on `run` for a status
enum when the real dependency direction is the other way (run orchestration invokes target
adapters, not vice versa). Kept `TargetInvocationOutcome` as its own 4-value enum in `target.domain`;
Phase 3's run orchestrator will map it onto `ExecutionStatus` when persisting a `TestExecution`.
`Citation`/`RetrievedChunk`, by contrast, *are* reused directly from `run.domain` in
`TargetInvocationResult` — both are plain immutable value records with one canonical shape (spec
§6.5/§8), not entities, so this is data reuse rather than a real module-layering violation.

### Retry policy: exact allow-list per spec §8.1, nothing inferred

`StandardHttpTargetSystemAdapter` retries only HTTP 429/502/503/504, connection resets, and
timeouts — verified against the *exact* wording of spec §8.1, which lists these four status codes
and nothing else as retryable. HTTP 500 is therefore **not** retried, even though it's a server
error, because it's absent from the spec's retryable set; `StandardHttpTargetSystemAdapterWireMockTest#http500IsNonRetryableSinceItIsNotInTheRetryableSet`
locks this in. `rag-evaluation.target.retry.maximum-attempts` (default 2, per spec §25's suggested
config) is the *total* attempt count including the first try, not the retry count — documented on
`TargetProperties.Retry` itself since "2 attempts" vs "2 retries" is exactly the kind of ambiguity
that causes silent behavior drift later.

A fresh `java.net.http.HttpClient` (wrapped in Spring's `JdkClientHttpRequestFactory`) is built per
invocation rather than cached, because connect timeout and redirect policy are per-target
configuration (`TargetSystem.connectTimeoutMilliseconds`/`followRedirects`), not global — the
allocation cost is negligible next to a network round trip. Redirects default to
`HttpClient.Redirect.NEVER`; a target must opt in to `NORMAL` via `followRedirects: true`, matching
"do not follow redirects automatically unless explicitly configured."

### Response size limit is enforced by bounded streaming, not post-hoc length checking

`readBounded` reads the response `InputStream` in 8 KB chunks and throws
`ResponseTooLargeException` (an `IOException`, so `RestClient` wraps it in the same
`ResourceAccessException` path as real transport failures) the moment the running total exceeds
`rag-evaluation.target.maximum-response-bytes`. An oversized response therefore never fully lands
in memory — this matters because the response size limit exists specifically to bound worst-case
memory use against an untrusted/misbehaving target, and reading the whole body first to check its
length would defeat that purpose.

### `TargetSystemConfiguration` carries a resolved secret value; `TargetSystem` never does

`SecretResolver.resolve(TargetSystem)` reads `System.getenv(secretEnvironmentVariable)` and
`TargetSystemConfigurationFactory` folds that transient value into `TargetSystemConfiguration`,
which lives only for the duration of one adapter call and is never persisted or logged (see
`docs/data-handling.md`). This keeps the adapter package free of environment-variable access and
means `TargetSystemConfiguration` — not the JPA entity — is the one place a real secret value
exists in memory.

### Response mapping is one JSONB column, not thirteen flat columns

Spec §8.1 asks for per-field JSON Pointer overrides for citations (5 fields) and chunks (6 fields)
plus input/output token pointers — 13 optional configuration values. Rather than 13 new
`target_system` columns, these are grouped into one `ResponseMapping` record (with nested
`CitationFieldMapping`/`RetrievedChunkFieldMapping`) stored as a single nullable JSONB column,
consistent with the Phase 1 convention of using structured records for JSONB fields with a
spec-defined shape. `null` at any level means "use the standard normalized field names," so the
common case (no override needed) requires zero configuration.

### Dataset/target "full replace" update model, no `PATCH`

Both `EvaluationTestCase.update(...)` and `TargetSystem.update(...)` (new methods this phase)
replace every editable field in one call rather than supporting partial field patching. This
mirrors spec §21's `PUT` (not `PATCH`) endpoints and avoids the ambiguity of "was this field
omitted, or intentionally set to null" that partial-update semantics would introduce — the API
DTOs (`TestCaseRequest`, `TargetSystemRequest`) are the same shape for create and update.

### `EvaluationDataset.markReady()` now guards `DRAFT → READY` itself

Phase 1's `markReady()` unconditionally set `status = READY`, which was fine before any code path
could call it from a non-`DRAFT` state. Phase 2 introduces the actual API endpoint, so the guard
(`ConflictException` unless currently `DRAFT`) was added directly to the entity method rather than
duplicated in `DatasetService` — the state machine's invariant belongs on the aggregate, not on
every caller.

### Imported-trace adapter deliberately deferred to Phase 3

See the note in `docs/implementation-plan.md`'s Phase 2 section: this session's explicit scope
listed every Phase 2 deliverable except the imported-trace adapter and `POST
/api/v1/imported-traces`, and building it now would mean guessing at how a trace maps to a
`TestExecution` repetition — context that only exists once Phase 3's run orchestrator is real.
Moved to the top of the Phase 3 checklist instead of left as a silently-skipped item.

### CSV import covers only "basic fields," matching spec §8.2's own basic/rich split

Dataset CSV import (`DatasetImportService.importCsv`) accepts exactly nine flat columns
(`externalId, question, referenceAnswer, answerable, shouldRefuse, severity, tags,
expectedDocumentIds, notes`) and always produces test cases with empty `requiredFacts`/
`forbiddenClaims`/`userContext` — those are structured data (a list of `ExpectedFact` records with
a match type, or an arbitrary JSON object) that cannot be reasonably flattened into CSV cells. Spec
§8.2 draws exactly this line for imported-trace CSV ("Also support CSV for basic fields" vs. "JSON
is the canonical rich import format"); the same split was applied here rather than inventing a
CSV micro-syntax for nested structures. The hand-rolled RFC 4180 parser (quoted fields, embedded
commas, `""` as an escaped quote) avoids adding a CSV library dependency not in CLAUDE.md's fixed
technology list for a ~60-line parsing need.

### Validation collects every row/field violation before persisting anything

Both `DatasetImportService.importJson`/`importCsv` and `TargetSystemService`'s create/update path
validate the *entire* payload up front (all test cases' regex patterns, all required fields, all
row-level CSV parsing) and throw one `ValidationException` carrying every `FieldViolation` found,
rather than failing fast on the first problem. Combined with `@Transactional` (default rollback on
any `RuntimeException`), this gives the "all-or-nothing, clear row-level errors" behavior spec §9
asks for without a manual two-phase commit — nothing is written until validation fully passes, and
the caller sees every problem in one response instead of fixing errors one at a time.

### `ProblemDetail` + correlation ID wiring introduced now, not deferred whole to Phase 8

CLAUDE.md's Phase 2 task explicitly asked for "RFC 9457 `ProblemDetail` with structured field
errors," so `GlobalExceptionHandler` (`@RestControllerAdvice`) and `CorrelationIdFilter` were built
this phase rather than waiting for Phase 8's "centralized exception handling" line item. Phase 8
will extend this (Actuator integration, more exception types as new failure modes appear) but the
mechanism — and the `errors`/`correlationId` extension properties every `ProblemDetail` carries —
is already in place and exercised by every controller integration test.

## Phase 3 notes

### Orchestration model: one worker task per run, not per execution

The bounded executor (`rag-evaluation.execution.worker-count` = 2, `queue-capacity` = 100,
`ThreadPoolTaskExecutor` with `AbortPolicy`) receives **one task per run**; the worker processes
that run's executions sequentially. Rationale: spec §15 bounds *runs in flight*, repetition/
consistency analysis (§17) benefits from executions of one run not racing each other, and
cross-run parallelism against the same target is still governed by
`TargetSystem.maximumConcurrency` via `TargetConcurrencyLimiter` (one fair semaphore per target
id, permits sized on first use after startup — a changed `maximumConcurrency` applies from the
next application start). No `CompletableFuture`, no common ForkJoinPool anywhere.

### Every state change is its own short transaction (`RunStateService`)

The worker never holds a JPA entity across steps: each step (`markRunning`,
`createPendingExecutionsIfAbsent`, `markExecutionRunning`, `recordInvocationResult`,
`finalizeRun`) reloads by id inside its own transaction. This is what makes "no in-memory-only
job state" true — a crash between any two steps leaves a picture startup recovery can reason
about — and it sidesteps `@Version` conflicts between the worker and the cancel endpoint (both
always operate on a fresh load).

### State machine enforced on the entity, not in services

`EvaluationRun.transitionTo` checks an allowed-transition table and throws `ConflictException`
otherwise, so an illegal transition cannot be persisted no matter which service attempts it.
Notable edges: resumable states (INTERRUPTED/CANCELLED/FAILED/PARTIALLY_COMPLETED) → QUEUED
(resume clears `completedAt`/`failureReason`; `startedAt` of the first start is preserved);
QUEUED → INTERRUPTED doubles as the compensation when the executor queue rejects a submission
(the run was accepted but never executed — client resumes later; API answers 409 with that hint).
COMPLETED is terminal.

### Cancellation is cooperative and immediate-by-status

`POST /{id}/cancel` persists CANCELLED right away (any of CREATED/QUEUED/RUNNING). A worker
observes the persisted status before each execution and cancels the remaining PENDING rows; an
invocation already in flight completes and keeps its real result (deliberate: killing it would
discard evidence about the target). A cancelled-while-QUEUED run is dropped by the worker at
pickup (`markRunning` returns false when status ≠ QUEUED).

### Resume semantics: incomplete ≠ failed

Resume re-schedules only PENDING and CANCELLED executions (CANCELLED rows are reset to PENDING).
TIMED_OUT / TARGET_ERROR / INVALID_RESPONSE executions are *completed* — they are evidence about
the chatbot and are never silently re-run (re-running would overwrite the very instability the
report should show). A resumable run with zero incomplete executions answers 409.

### Startup recovery covers QUEUED too, deliberately beyond the spec's letter

Spec §15 says "mark stale RUNNING runs INTERRUPTED". The executor queue is in-memory, so a run
that was QUEUED when the process died is equally orphaned — it would stay QUEUED forever with no
way to resume (start rejects non-CREATED). Recovery therefore marks both RUNNING and QUEUED runs
INTERRUPTED and marks their stale RUNNING executions CANCELLED ("interrupted by application
restart"), which resume then reschedules.

### Terminal status derivation

All executions SUCCEEDED → COMPLETED; at least one SUCCEEDED → PARTIALLY_COMPLETED (spec §15:
"enough results exist to produce a report"); none → FAILED with a reason. The demo e2e test locks
in the PARTIALLY_COMPLETED case (13 of 14 scenarios succeed, the HTTP-error scenario fails).

### Imported traces are served through the adapter contract, not short-circuited

`POST /api/v1/imported-traces` (JSON canonical, `/csv` for basic fields) validates row-level
(unknown `externalTestCaseId` in the selected dataset, non-positive/duplicate repetition, blank
question — reported together as `traces[i].field`), then creates a REPLAY-mode run in CREATED
state, one pending execution per trace, and one `imported_trace` row (migration 011) bound 1:1 to
its execution. Starting the run goes through the exact same orchestrator; the
`ImportedTraceTargetSystemAdapter` (registered for `AdapterType.IMPORTED_TRACE`, discovered by the
Phase 2 registry as planned) resolves the trace by execution id and returns it as a normal
`TargetInvocationResult`. Consequences: cancellation/resume/Phase 4 evaluation treat imported
runs identically to live ones, and the captured latency is reported as-is (for imported data the
recorded latency *is* the measurement). The adapter lives in `run.infrastructure`, not `target`,
because it reads run-owned data — dependency direction stays run → target.

To make the repetition matching possible, `TargetInvocationRequest` gained an `executionId`
component (nullable; the standard HTTP adapter ignores it, the connection test passes null).
That is the one deliberate touch to passing Phase 2 code, plus extracting the private RFC 4180
CSV parser out of `DatasetImportService` into `common.csv.CsvParser` so trace CSV import reuses
it instead of a second hand-rolled copy.

`RunMode.REPLAY` starts here conceptually: an imported-trace run evaluates captured interactions
without any live call. Phase 7's replay-of-a-previous-run will additionally set
`TestExecution.sourceExecutionId` (already in the schema since Phase 1).

### Executions also persist `attempt_count` (migration 010)

Spec §8.1 requires persisting at minimum the final attempt count and last error; Phase 2's
adapter already reported `attemptCount` but Phase 1's schema had no column for it. Added as a
nullable INT (executions cancelled before any invocation have none). `recordFailure` now also
persists `requestPayload`/`rawResponse` so a failed execution keeps whatever evidence existed.

### Demo target (§27) is a self-contained `demo` package under the `demo` profile

`POST /demo-target/chat` (controller + `DemoTargetService`, both `@Profile("demo")`) implements
all 14 deliberate scenarios keyed by external test-case ID `DEMO-001`..`DEMO-014` (question-keyword
fallback), returning the normalized schema built from the canonical `Citation`/`RetrievedChunk`
records plus an additive `usage` object so token-pointer extraction is exercised. The slow
scenario's delay is `rag-evaluation.demo.slow-response-delay-millis` (default 3000);
inconsistency across repetitions rotates three answers via an in-memory counter (acceptable
in-memory state: demo-only, not job state). `examples/demo-dataset.json` covers every scenario
with matching required facts/forbidden claims/expected documents. A new top-level `demo` package
keeps the boundary obvious; it is absent outside the profile, and
`RunControllerIntegrationTest.demoTargetEndpointIsAbsentOutsideDemoProfile` proves the path
404s in the non-demo context.

### Fixed while here: unknown paths no longer answer 500

That 404 test exposed a Phase 2 bug: `GlobalExceptionHandler`'s `Exception` catch-all swallowed
Spring's `NoResourceFoundException`, turning every unknown-URL request into a 500. Added an
explicit `NoResourceFoundException` → 404 `ProblemDetail` handler.

### API surface added this phase

`POST/GET /api/v1/evaluation-runs`, `GET /{id}`, `POST /{id}/start|cancel|resume`,
`GET /{id}/executions` (paged; response DTO deliberately omits `requestPayload`/`rawResponse` —
client content stays out of list payloads per the privacy defaults), `POST /api/v1/imported-traces`
(+`/csv`). Create/start/resume answer 202 with the current persisted run state; §21's
`/metrics`, `/findings`, `/summary`, `/replay` arrive with Phases 4–7.

## Phase 4 notes

### Package cycle avoided by putting the pipeline interface in `run` and its implementation in `classification`

The worker must call evaluation after each invocation, but `evaluation` already depends on
`run.domain` (the context carries `EvaluationRun`/`TestExecution`), so a direct
`RunWorker → EvaluationService` call would create a `run ↔ evaluation` package cycle. Resolution:
`run.application.EvaluationPipeline` is a small interface owned by the run module
(evaluate one execution / complete run evaluation / known keys / validate configuration), and its
implementation `DeterministicEvaluationPipeline` lives in `classification.application` — the one
package that already legitimately depends on both `evaluation` and `run`, making it the natural
composition point. Dependency directions stay acyclic:
`classification → evaluation → run → target`, `* → dataset/common`. For the same reason the
`/metrics` + `/summary` endpoints live in `evaluation.api` and `/findings` in
`classification.api`, not in `run.api`.

### Metric statuses carry the three-way verdict; SKIPPED keeps the applicability reason

`COMPLETED` + `passed=false` = the chatbot failed the metric; `FAILED` + `errorMessage` = the
evaluator itself failed (never counted against the chatbot — enforced in
`TestCaseOutcomeCalculator` and the aggregation denominators, both unit-tested); `SKIPPED` = not
applicable or missing required data, with the `EvaluationApplicability` reason persisted as the
explanation (e.g. "no retrieved chunks are available; from black-box data this cannot be
distinguished from the target simply not exposing retrieval"). A crashing evaluator is caught per
strategy inside `EvaluationService.runOne`, so one broken evaluator cannot take down the others or
the run.

### Answer-quality evaluators skip failed executions instead of failing them

Spec §11.1: "Do not evaluate answer quality if transport failed." Implemented as applicability:
every content evaluator answers `MISSING_REQUIRED_DATA` when the execution has no successful
non-blank answer. Two deliberate exceptions: `TransportEvaluator` is always applicable (it *is*
the judgment of the failure), and `RefusalEvaluator` stays applicable for `shouldRefuse` cases so
it can record the spec's fourth classification (`EMPTY_OR_ERROR`) — an error response where a
refusal was required is itself a distinguished outcome, though the finding side leaves the cause
to the TARGET_SYSTEM_ERROR rule.

### Confidence encodes evaluator-mechanism certainty; the 0.70 threshold routes review

Exact string/regex/ID matching carries confidence 1.0. Phrase-heuristic verdicts carry less:
correct refusal 0.7, missed-refusal-phrase 0.6, refusal-then-long-continuation 0.5, answerability
signals 0.7/0.5. The central §14 subset in `EvaluationService` then flags for human review:
confidence below `human-review-confidence-threshold` (0.70), any failed metric on a HIGH/CRITICAL
test case, and any failed metric on a test case tagged security/permission-sensitive. This makes
"phrase-based refusal detection is crude" an explicit, threshold-driven review trigger rather than
hidden optimism. Remaining §14 triggers (evaluator disagreement, truncated context, missing
reference answer) arrive with the Phase 5 LLM evaluators that make them computable.

### Latency: per-execution verdict in the evaluator, percentiles in aggregation

The latency evaluator judges one execution against `maximumAcceptableLatencyMilliseconds`
(default 2000 ms, per-run overridable, <= 0 disables). Median/p90/p95/max are run-level facts and
live in `RunSummaryService`, computed by `Percentiles` — linear interpolation between closest
ranks (R-7, NumPy's default), unit-tested against hand-computed values including the even-size
median.

### Retrieval metrics ride on evidence metadata

`ExpectedDocumentEvaluator` stores Hit@1/3/5, first relevant rank, and reciprocal rank in a
`retrieval-metrics` evidence entry (typed key constants on the evaluator class). Aggregation and
classification read those constants instead of re-deriving retrieval math from raw chunks —
one implementation, one set of unit tests, no drift. The metric's score *is* the reciprocal rank.

### Evaluator configuration: application defaults + per-run overrides, validated at creation

`EvaluatorProperties` (`rag-evaluation.evaluator.*`) holds defaults; `EvaluatorSettingsFactory`
overlays `EvaluationRun.evaluatorConfiguration` (documented override keys on the class). Citation
policy resolves per test case: tag-scoped override map first (in the test case's own tag order),
then the run/application default — spec §11.6's "by tag or dataset". Malformed configuration and
unknown `enabledEvaluatorKeys` are rejected with 400 at run creation through the pipeline
interface, so a queued run can never fail on unresolvable settings. Default citation policy is
OPTIONAL: silently requiring citations of every arbitrary client bot would manufacture failures;
a run opts in per tag or globally.

### Classification: honest UNKNOWN is a rule, and §16's rule outputs got real columns

`FailureClassificationService` implements the §16 rules over completed metric results (rules are
exercised in unit tests against evidence produced by the real evaluators, so rules and evaluators
cannot drift apart). Where black-box data proves only a symptom, the finding says "Root cause:
undetermined from black-box data": the ranking rule explicitly names OUTDATED_DOCUMENT as the
indistinguishable alternative and demands human confirmation; a mandatory-metric failure no rule
explains becomes UNKNOWN (confidence 0.2, human confirmation required) rather than a guessed
category. Because §16 requires each rule to produce confidence, supporting metric IDs, and a
human-confirmation flag — and §6.8's field list predates that contract — migration 012 adds
`confidence`, `requires_human_confirmation`, and `supporting_metric_result_ids` (JSONB) to
`evaluation_finding` as additive columns; burying rule outputs in evidence metadata would have
made them invisible to Phase 6's review workflow. Findings are delete-and-regenerated per run
(`classifyRun`), so resumes never accumulate stale duplicates. Finding severity is the test
case's severity — the impact weight of the question, the only severity signal black-box data has.

### Repetition consistency (spec §17, deterministic part) landed with classification

`RepetitionConsistencyAnalyzer` compares successful repetitions by fact-level signature —
per-fact match outcomes, matched forbidden claims, refusal classification, cited document IDs,
first relevant retrieval rank — exactly the deterministic comparison §17 prefers; wording
variation is invisible by construction (unit-tested). A material difference yields an
INCONSISTENT_RESPONSE finding anchored to the first successful repetition, and the summary's
consistency rate (consistent / comparable multi-repetition test cases) comes from the same
analyzer. The semantic comparison option stays with Phase 5.

### Aggregation is computed on demand, not persisted

Spec §6 defines no aggregate entity, and a stored aggregate can silently go stale as reviews land
(Phase 6) or replays add runs. `GET /summary` computes `RunSummary` from persisted
executions/metric results/reviews each time; every rate is reported next to its counts and a
zero-denominator rate is `null`, never a fake number. Test-case outcome follows the spec §18
default rule via `TestCaseOutcomeCalculator` (mandatory evaluators only: default
transport/required-facts/forbidden-claims; failure beats evaluator-error beats not-evaluated;
a test case is the worst of its repetitions). Groundedness/reference-correctness/completeness
rates are deliberately absent until Phase 5 computes them — not approximated from deterministic
data.

### Evaluation timing: inline per execution, plus a completion sweep

The worker evaluates each execution right after its result is persisted (incremental progress,
crash-safe), and `completeRunEvaluation` sweeps any terminal-but-unevaluated executions before
finalization — which is what makes resumed runs whole when a crash hit between invocation and
evaluation. `evaluateExecution` is idempotent (skips executions that already have metric
results), so replay/resume never double-evaluates. Evaluation and classification failures are
logged and swallowed in the pipeline: they must never demote a run that successfully collected
target evidence.

## Phase 5 notes

### Judge abstraction: one interface, an Ollama implementation, a deterministic fake

All eight LLM evaluators speak `evaluation.llm.JudgeClient` — `judge(JudgeRequest, Class<T>)`
returning a schema-mapped record plus model name and duration. The production implementation
(`evaluation.infrastructure.OllamaJudgeClient`) is the *single* code path that sends content to a
model; the normal test suite injects `FakeJudgeClient` (test sources), a scriptable deterministic
fake that also records every request so prompt-hardening tests can assert on exactly what would be
sent. Real-model tests (`*OllamaIT`) run only behind the `-Pollama-it` profile (failsafe include;
surefire's default name patterns never pick them up), so the default `./mvnw clean verify` never
touches a real model.

### Spring AI 2.0.0 surface actually used (verified via `javap`, extending the Phase 0 findings)

- `OllamaOptions` no longer exists — Spring AI 2.0 renamed it **`OllamaChatOptions`** with a
  self-typed builder hierarchy (`model(String)`/`temperature(Double)` live on parent builder
  classes; call them as statements, not a fluent chain, to dodge the erased self-type).
- `ChatClient`'s request spec takes an options **builder** (`options(B extends
  ChatOptions.Builder<?>)`), not built options.
- `BeanOutputConverter` is Jackson 3-based (`tools.jackson...JsonMapper`) and exposes
  `getJsonSchemaMap()` — passed as Ollama's native structured-output `format`, so the schema is
  enforced server-side *and* re-parsed client-side (`convert`); a mismatch throws and becomes
  `JudgeMalformedOutputException`.
- Messages are passed as explicit `SystemMessage`/`UserMessage` objects, never through the
  ChatClient text-template path — untrusted content can't be misinterpreted as a prompt template.
- Spring Boot 4 relocated the health API: `org.springframework.boot.health.contributor.*` (used
  by `EvaluatorModelHealthIndicator`), not the Boot 3 `actuate.health` package.

### Custom structured evaluators; Spring AI's built-in evaluators are not wrapped

Confirmed the Phase 0 decision against the resolved jars: `FactCheckingEvaluator` and
`RelevancyEvaluator` still answer with a single pass/score/feedback-string tuple. That cannot
carry per-claim verdicts with supporting/contradicting chunk IDs (groundedness), per-chunk
decisions (context relevance), or our untrusted-content prompt hardening — their prompt templates
are their own. Wrapping them would have meant a second Ollama call per metric for evidence we
already produce better ourselves, so **all eight evaluators are fully custom** structured-output
evaluators on `JudgeClient`. This is the spec's own hedge ("do not force it into the
architecture"), exercised.

### Derived verdicts: the judge supplies evidence, Java computes the score

Where the spec defines objective aggregation, it is computed deterministically from the judge's
per-item decisions rather than trusted from the model: groundedness pass/fail (no CONTRADICTED /
NOT_IN_CONTEXT claim) and score (supported fraction, partial support at half weight), and context
relevance's relevant/retrieved ratio, relevant-in-top-3, and irrelevant-top-ranked (from persisted
chunk ranks). "No score without evidence" thus holds even against a sloppy judge; the judge's own
numbers are still persisted in the raw judge-output evidence. A groundedness call that returns
zero claim decisions is a vacuous pass flagged for human review — never a manufactured chatbot
failure.

### Semantic refusal runs on every successful answer

`INCORRECT_REFUSAL` (refusing an ordinary answerable question) is only observable on cases where
no refusal was expected, so the evaluator is applicable to every successful answer, and the
pass/fail mapping depends on `shouldRefuse`/`answerable` (unanswerable cases accept both honest
uncertainty and refusal). Phrase-based `refusal` (Phase 4) still runs only on `shouldRefuse`
cases; where both exist and disagree, both results are flagged for review.

### Spec §14 triggers: local ones in evaluators, cross-evaluator ones after the loop

Truncated context, contradictory documents (CONTEXT_VS_CONTEXT pairs), missing reference answer
(reference-correctness/completeness judging on required facts alone), and
partial-refusal-with-disclosure are flagged by the evaluator that observes them. Low confidence,
HIGH/CRITICAL severity, and security/permission tags stay in `EvaluationService`'s central check
(Phase 4). The two triggers that need *all* verdicts — deterministic/LLM disagreement and two LLM
evaluators disagreeing — run in `CrossEvaluatorReviewTriggers` after the evaluator loop, over
explicit same-aspect pairs only (refusal↔semantic-refusal, required-facts↔reference-correctness,
required-facts↔completeness, groundedness↔ANSWER_VS_CONTEXT contradiction,
reference-correctness↔ANSWER_VS_REFERENCE contradiction). A blanket any-two-metrics-differ rule
was deliberately rejected: relevant-but-ungrounded is a legitimate combination, not a
disagreement. This required `EvaluationService` to collect results and `saveAll` at the end
instead of saving per strategy (stated Phase 4 touch), and gave `EvaluationMetricResult` a
`flagForHumanReview(reason)` mutator that appends the reason to the explanation — flags are never
silent. Evaluator failures (`FAILED`) now always set `requiresHumanReview` (spec §14's
malformed-output trigger): a missing automated verdict must reach a reviewer.

### Evaluator identity on the strategy interface, not a parallel registry

`EvaluationStrategy` gained default methods `evaluatorType()` / `promptVersion()` /
`evaluatorModel()` (deterministic defaults: DETERMINISTIC / null / null), so `EvaluationService`
persists LLM metadata without touching the nine Phase 4 evaluators. The judge model name comes
from the injected `JudgeClient`, meaning a replayed run's results always carry the model that
actually judged them.

### Graceful degradation is classification, not availability checking

Nothing pings Ollama before evaluating: the judge call simply fails and `OllamaJudgeClient`
classifies the failure (connect/host/timeout errors and model-404 → `JudgeUnavailableException`,
unparseable output → `JudgeMalformedOutputException`), which `EvaluationService`'s existing
catch-all records as metric-status FAILED — never a chatbot failure, and outcomes/denominators
already exclude it (Phase 4 rules). Client-side retry is disabled
(`spring.ai.retry.max-attempts=0` — verified via bytecode that Spring AI feeds this value to
`RetryPolicy.maxRetries`, so `1` would still mean one retry with a 2 s default backoff, and that
`ResourceAccessException`/connect-refused IS in the retryable set): with 8 judge calls per
execution, default retry backoff turned a dead Ollama into minutes of stalled run time — exactly
the failure mode the first full-verify run caught (6 pre-existing run ITs timing out).
`EvaluatorModelHealthIndicator` (component `evaluatorModel`) reports DOWN with the
reason (unreachable vs. model-not-pulled) via `OllamaApi.listModels()`; the application itself
keeps working — deterministic evaluation is unaffected, proven end to end by
`LlmDegradationIntegrationTest` (Ollama base URL pointed at a closed port).

### Existing integration tests pinned to a closed Ollama port

Every Postgres-backed IT that triggers evaluation now sets
`spring.ai.ollama.base-url=http://localhost:1`: on a developer machine with a real local Ollama,
the default suite would otherwise make real model calls (violating the determinism/no-real-model
rule). The Phase 4 vertical-slice test additionally passes `enabledEvaluatorKeys` = the nine
deterministic keys, because it is the Phase 4 zero-Ollama checkpoint with exact metric/outcome
expectations; the Phase 5 paths get their own ITs (`LlmEvaluationIntegrationTest` over the fake
judge end to end, `LlmDegradationIntegrationTest` for unavailability).

### Calibration is test-scoped data plus a production comparison service

`src/test/resources/calibration/calibration-cases.json` holds 22 hand-labelled examples, two per
spec §14 category, each with per-metric human labels; `CalibrationDatasetTest` keeps the file
honest (≥20 cases, all 11 categories, labels present). `LabelAgreementService`
(`evaluation.application`) computes agreement rate and the confusion counts over
(automated, human) label pairs — its report text hard-codes the "small labelled calibration
sample, not model accuracy" framing so the number can't be quietly upgraded to a quality claim.
The real-model calibration comparison runs in `LlmJudgeOllamaIT` behind `-Pollama-it` and logs the
report instead of asserting a quality bar: judge quality is a per-engagement, per-model property
the consultant calibrates, not a build gate.

## Phase 6 notes

### Review module sits on top; the one existing downward edge was inverted

Phase 4 left one edge pointing the wrong way for Phase 6: `evaluation.application.RunSummaryService`
imported `review.infrastructure.HumanReviewRepository`. With the review workflow now needing
metric results, executions, test cases, *and* findings, `review` must be an upper-layer module
(`review → classification → evaluation → run → target`), so that edge was inverted with the same
pattern Phase 4 used for `run.application.EvaluationPipeline`: `evaluation.application.HumanReviewLookup`
is a one-method interface owned by evaluation ("which of these metric results have a review"),
implemented by `review.application.HumanReviewLookupAdapter`. This is a deliberate touch to
passing Phase 4 code (constructor swap only, behavior identical) stated per the working
agreement. `report` is the new top of the stack — it reads every module and nothing depends on it.

### Pending-review queue: dynamic JPQL, not derived queries or `:param is null` guards

`ReviewQueueRepository` builds the pending query (`requiresHumanReview = true` and no
`HumanReview` row) by appending only the non-null filter predicates (run, severity, evaluator
key, metric status) and binding just those parameters. A single static JPQL with
`(:severity is null or t.severity = :severity)` guards was rejected because PostgreSQL's
parameter-type inference is unreliable for null-bound enum/uuid parameters, and a Spring Data
derived query can't express the cross-aggregate join (metric result × execution × test case) plus
optional filters. Ordering is a severity `case` rank (CRITICAL first) then flag time, so the queue
is a triage list. "Status" filtering deliberately means *metric* status: `COMPLETED` = a chatbot
verdict to double-check, `FAILED` = the evaluator broke and a human must fill the gap — the same
never-conflate rule the rest of the system enforces.

### Reviews are append-only; CONFIRMED confirms the findings the result supports

A review never mutates the automated result (unit-tested: repository save of the metric result is
never called; integration-tested: status/passed/score/explanation identical after review). A
metric result can accumulate several reviews (UNCERTAIN → later NEEDS_DOMAIN_EXPERT →
CONFIRMED); consumers treat the latest as effective, and the report prints them all. A CONFIRMED
decision additionally sets `humanConfirmed` on findings of the same execution that list the
reviewed result in `supportingMetricResultIds` and await confirmation — the reviewer has looked
at exactly the evidence the finding rests on. REJECTED/UNCERTAIN never confirm anything.

### Recommendation templates are rows, not resources baked into the report template

Migration 013 creates `recommendation_template` (one row per §6.8 failure category, all 18
seeded with fixed UUIDs) and `PUT /api/v1/recommendation-templates/{category}` edits the text.
The report reads the *current* text at render time, so an edited recommendation shows up on the
next render without re-running or re-classifying anything — the integration test proves it by
editing RETRIEVAL_MISS and re-rendering. Findings still persist their rule-generated
evidence-specific recommendation; the report shows both ("recommended change (template)" +
"(specific)"). If a template row is somehow missing, the report says so and falls back to the
per-finding text — it never invents one.

### Three additive LLM-evidence classification rules (and the ones deliberately not written)

Phase 4's §16 rules only consumed deterministic results. Now that Phase 5 results exist, three
rules were added to `FailureClassificationService`, all additive and inert when no LLM results
are present (so every deterministic-only test and the degradation path are untouched):
groundedness COMPLETED+failed → UNSUPPORTED_CLAIM (§16's own example, naming
MISSING_DOCUMENTATION as the black-box-indistinguishable alternative); context-relevance
COMPLETED+failed → IRRELEVANT_CONTEXT; reference-correctness failed *while groundedness passed* →
INCORRECT_ANSWER with the "grounded in outdated context but wrong against reference" reading and
OUTDATED_DOCUMENT named for human confirmation. Judge-derived findings cap their confidence at
0.6 and always require human confirmation. Deliberately *not* rule-generated:
MISSING_DOCUMENTATION and OUTDATED_DOCUMENT as primary categories (black-box data cannot prove a
document's absence or staleness — they appear as named alternatives inside findings needing
confirmation) and EVALUATOR_DISAGREEMENT findings (disagreement already flags both results into
the review queue via `CrossEvaluatorReviewTriggers`; duplicating it as a finding would double-
report tooling signals as target problems). Templates still exist for every category so a future
rule or a human-assigned category always has a recommendation.

### Report: one service assembles everything; the template renders, decides nothing

`GET /reports/runs/{id}` (plain `@Controller`, `text/html`, no JavaScript — the IT asserts the
page contains no `<script`) renders `run-report.html` from a single `RunReport` record built by
`RunReportService`: run/target/dataset metadata, the §18 summary (sections 5–8), reliability by
question type (answerable / refusal-expected / unanswerable — the axis tags don't cover),
finding details, category groups, human-review state, prioritized recommendations, acceptance
criteria, evaluator versions (from persisted metric results, so a replay shows what actually
judged it), and the resolved evaluator settings. Every §22 major-finding field is rendered by a
single Thymeleaf fragment reused across sections 10 and 11. Categories are never blended:
section 11 shows one representative example per category with a concept explanation
distinguishing incorrect / unsupported / incomplete / retrieval-failure / unknown; sections
12–15 are category-set views (retrieval, refusal+unsupported, consistency, latency/errors) as
compact tables. Section 3 (limitations) is partly dynamic: evaluator-error counts ("tool
failures, never chatbot failures"), truncated-context counts, LLM-judge caveats only when LLM
results exist, and a non-COMPLETED run status warning. Human-review section shows automated
verdict and human decision side by side — an override never hides the automated result.

### Report content vs. logging privacy

The report deliberately contains full questions/answers/chunks/citations — it is the deliverable
the consultant hands over, rendered server-side on demand from the local database. The privacy
rules (spec §23) govern *logs and metrics*, which remain content-free; nothing in the report
path logs content, and `RunReportService` never touches the log with payloads.

## Phase 7 notes

### Replay rides the existing orchestrator; the "adapter" is a copy step, not an adapter

`POST /api/v1/evaluation-runs/{id}/replay` creates a CREATED REPLAY run (one pending execution
per terminal source execution, `sourceExecutionId` set) that is then started/cancelled/resumed
through the exact same state machine and worker as every other run. Unlike imported traces —
which are genuinely external data served through a real `TargetSystemAdapter` — replay reads
run-owned `TestExecution` rows, so forcing it through the adapter registry would have meant
either a fake `TargetSystem` row or special-casing adapter resolution. Instead the worker
branches per execution: a non-null `sourceExecutionId` routes to
`RunStateService.copyFromSourceExecution` (copy the captured result, then evaluate as normal)
and never touches the concurrency limiter or the target. Consequence made explicit in the
worker: target configuration — including the secret env var — is only resolved when at least
one pending execution actually needs a live call, so a pure replay run works even after the
target's credentials are gone.

### Replay copy semantics

Source executions with captured evidence (SUCCEEDED and completed-with-error TIMED_OUT /
TARGET_ERROR / INVALID_RESPONSE) are replayed; PENDING/RUNNING/CANCELLED sources have nothing to
re-evaluate and are skipped at replay creation (a source run still CREATED/QUEUED/RUNNING answers
409). Failed sources keep their failure status/error details in the copy — replay re-judges the
captured behavior, it never launders a target failure. The captured `latencyMilliseconds` is
copied (for replayed data the recorded latency *is* the measurement — same rule as imported
traces); `startedAt`/`completedAt` are the replay's own timestamps. `sourceExecutionId` always
points at the *original* execution (`source.sourceExecutionId ?? source.id`), so replaying a
replay references the same originals instead of building a chain — and a source execution deleted
after replay creation (FK is `ON DELETE SET NULL`) marks the replay execution CANCELLED with a
reason, never a chatbot failure (CANCELLED executions are excluded from evaluation and every
denominator). Evaluator keys/configuration default to the source run's and can be overridden in
the request body — re-judging with updated evaluators is the point of replay; results carry the
versions that actually judged them (Phase 5 metadata), which is what the comparison's version
warnings read.

### Comparison reuses RunSummary; classification is deliberately conservative

`RunComparisonService` computes both sides via `RunSummaryService.summarize`, so comparison can
never drift from the single-run aggregation rules (evaluator errors outside every denominator,
zero-denominator rates as `null`). Cases are matched by external test-case ID; IMPROVED/REGRESSED
require a conclusive PASSED/FAILED on *both* sides — any INCONCLUSIVE/NOT_EVALUATED side yields
INCONCLUSIVE, because an evaluator-infrastructure difference must never be presented as a chatbot
change (the same never-conflate rule again). Rate deltas exist only when both rates exist — a
zero-denominator side never produces a fake 0-to-100 jump. "High-severity findings" counts
persisted findings of severity HIGH/CRITICAL per run (spec §19's own wording), not failed
test cases — that count already exists separately in each summary.

### Dataset mismatch is an explicit, warned opt-in — not a mapping table

Spec §19 allows "same dataset or explicitly mapped compatible dataset version". No dataset-version
mapping entity exists (nothing else needs one); "explicitly mapped" is implemented as the
`allowDatasetMismatch=true` query parameter: without it, differing dataset IDs answer 400 with an
explanation; with it, cases are matched by externalId and the comparison carries a permanent
warning that nothing verified the underlying questions are the same. A speculative mapping table
would have been guessed schema for a workflow the consultant hasn't needed yet.

### Warnings are the §19 honesty mechanism, in API and report alike

`RunComparison.warnings` carries every reason the comparison is not scientifically clean:
evaluator version/prompt/model differences per key (from persisted metric results, so they
reflect what actually judged each run), an evaluator producing results in only one run,
dataset mismatch, non-terminal or failed/interrupted run states, differing repetition counts,
differing target systems (a cross-system comparison is not a before/after). The HTML report
(`comparison-report.html`, no JavaScript, same visual language as the run report) renders the
warnings box *above* every number, and the footer repeats that warned deltas are not a controlled
experiment. The comparison is still shown — the consultant decides what a caveated comparison is
worth — but it is never silently clean.

### Demo target gained runtime scenario redirects (demo-only, additive)

To demo/test §19 a target has to *change* between two runs. `DemoTargetService` now holds an
in-memory redirect map (`PUT/DELETE /demo-target/scenario-redirects`, demo profile only, same
acceptable-in-memory-state category as the DEMO-014 rotation counter): each entry re-routes one
scenario to another scenario's behavior, e.g. `DEMO-002 → DEMO-001` makes the wrong-answer
scenario answer correctly ("the bot was fixed") and the reverse redirect breaks a previously
correct one. `RunComparisonIntegrationTest` drives exactly that: baseline demo run → redirects →
candidate run → DEMO-002 IMPROVED, DEMO-001 REGRESSED, DEMO-014 REGRESSED naturally via its
rotation, DEMO-013 UNCHANGED, zero version warnings, and the HTML report asserted on top.

## Phase 8 notes

### springdoc 3.0.3 verified against Boot 4.1.0 — and the Jackson collision it caused

Spec §29 demanded real verification, not assumption. Facts established: springdoc's 2.8.x line
targets Boot 3; the 3.x line is the Boot 4 line (3.0.3's published parent POM builds against
`spring-boot-starter-parent:4.0.5` — checked on Maven Central, not guessed). Compatibility with
this project's Boot 4.1.0 was then proven empirically: full suite green with springdoc on the
classpath and `HardeningIntegrationTest#openApiDocumentIsServedBySpringdoc` asserting
`/v3/api-docs` lists the real endpoints.

The first full verify after adding it failed with 30 broken tests: springdoc (via swagger-core)
brings Jackson **2** databind onto the classpath, and Hibernate's `JacksonIntegration`
auto-detection then preferred the Jackson 2 format mapper over Jackson 3 — every JSONB column
typed as `tools.jackson.databind.JsonNode` failed with "Could not deserialize string to java
type". Fix: `spring.jpa.properties.hibernate.type.json_format_mapper=jackson3` (the
`Jackson3JsonFormatMapper.SHORT_NAME`, verified via `javap`), pinning entity JSON mapping to
Jackson 3 regardless of what else the classpath carries. Documented in `docs/api.md` since it is
exactly the kind of silent-drift trap CLAUDE.md's Jackson-3 warning is about.

### Metrics: one component owns every tag decision

All spec §24 meters are created through `common.observability.WorkbenchMetrics`, so the
no-high-cardinality-tags rule (§23/§24) is enforced in one reviewable place: allowed tag values
are outcome/status enum names, registered evaluator keys, and exception class simple names —
never IDs or content. Producers call it from where the facts are known: `RunWorker` (live target
invocations only — replay/imported executions deliberately do not count as target invocations),
`RunStateService` (terminal executions, terminal runs), `EvaluationService` (evaluator
executions/failures/duration), `OllamaJudgeClient` (judge calls, success and every failure path).
The review-backlog gauge is registered by `review.application.ReviewBacklogGauge` — the count
query belongs to the review module, the metric name/description stays in `WorkbenchMetrics`, and
the dependency direction stays review → common. `HardeningIntegrationTest` asserts the meters
appear and that `rageval.evaluator.executions` carries exactly the `evaluator` and `status` tags.

### Structured logs via MDC + one console pattern

`CorrelationIdFilter` already put `correlationId` into MDC (Phase 2); Phase 8 adds
`runId`/`targetSystemId`/`executionId` in `RunWorker` (set/removed in `try/finally` — worker
threads are pooled) and `evaluatorKey` in `EvaluationService.runOne`. The console pattern renders
them as a fixed `[cid=… run=… exec=… target=… eval=…]` block so every line from a worker thread
carries its identifiers without any content logging.

### Storage limits applied at the single persistence choke point

`rag-evaluation.storage.store-raw-responses` / `store-retrieved-chunk-content` are enforced in
`RunStateService.recordInvocationResult` — the one method every capture path (live adapter and
imported-trace alike) writes through — rather than in adapters, so no future adapter can bypass
them. Chunk stripping keeps IDs/titles/ranks/scores (retrieval metrics stay computable) and nulls
only the content text. Replay copies what the source stored: the switches govern capture, not
history. `StorageConfigurationIntegrationTest` proves the end-to-end behavior including that
evaluation still runs.

### Run deletion rides the Phase 1 FK design

`DELETE /api/v1/evaluation-runs/{id}` is a JPA delete of the run row; the `ON DELETE CASCADE`
FKs laid down in Phase 1 (executions → metric results → reviews; findings; imported traces) do
the rest in the database, and `source_execution_id`'s `ON DELETE SET NULL` means deleting a
source run never breaks a replay run's own copied data. QUEUED/RUNNING runs answer 409 (cancel
first) — deleting rows under an active worker would turn its next state write into a spurious
failure. Proven by `HardeningIntegrationTest` including the human-review cascade.

### Controllers lost their repository shortcuts (ArchUnit made it a rule)

Spec §28.5's "controllers do not depend directly on repositories" was violated by two Phase 4/6
controllers (`FindingController`, `RunEvaluationController`) that did existence checks + paged
reads straight off repositories. Introduced `FindingQueryService` / `MetricResultQueryService`
(read-only application services) and moved the checks there — stated touch to passing code,
behavior identical. `ArchitectureTest` now locks in the three spec rules: no controller →
repository dependency, no `..domain..` → `..api..` dependency, and no controller method whose
return type involves a `@Entity` class anywhere in its generic signature
(`JavaType#getAllInvolvedRawTypes`, so `PageResponse<SomeEntity>` can't sneak through).

### Health stays honestly DOWN without Ollama; tests re-enable metrics export

Decision kept from Phase 5: `evaluatorModel` reports DOWN when Ollama is unreachable, which makes
the aggregate `/actuator/health` answer 503 even though the workbench is fully functional for
deterministic evaluation. Phase 8 documents this (README observability section) instead of
papering over it with a status remap — an operator wiring a probe should see the honest state and
read why in the details. Separately, Boot disables metrics export in tests by default;
`@AutoConfigureMetrics` (relocated in Boot 4.1 to
`org.springframework.boot.micrometer.metrics.test.autoconfigure`) turns the real Prometheus
endpoint back on for the hardening IT.

### New ProblemDetail handlers for client errors that were 500s

Malformed UUIDs in path variables (`MethodArgumentTypeMismatchException`), missing required query
parameters (`MissingServletRequestParameterException` — e.g. `/api/v1/run-comparisons` without
IDs), and non-body parameter validation (`HandlerMethodValidationException`) previously fell into
the `Exception` catch-all and answered 500. All three now answer RFC 9457 400s with field
violations and the correlation ID.

### Local profile = compose datasource + loopback bind

`application-local.properties` points at the compose Postgres (rageval/rageval@localhost:5432)
and sets `server.address=127.0.0.1` — the documented local-only deployment mode (spec §23): the
API serves client content with no auth layer, so by default it is simply not reachable from the
network; compose likewise publishes Postgres/Ollama on 127.0.0.1 only. Spec §25 suggests YAML
config files; the project stays on `.properties` (consistent with every prior phase) — same
typed `@ConfigurationProperties` underneath, format is cosmetic.

### Verbose content logging is a loud, explicit dev-only opt-in

`rag-evaluation.logging.verbose-content=true` (`ContentLoggingProperties`) makes exactly two
places — the target adapter and the judge client — log payloads/prompts at DEBUG, and enabling it
prints a startup WARN explaining that client content will land in the log. The Phase 8 grep audit
confirmed no other log statement touches content; the WireMock redaction test keeps guarding the
secret-header path.

## Session Log

Append one dated entry per session, newest at the bottom. Each entry: what changed, key decisions
and why, anything deferred, and the exact next step. This plus the "Current status" header in
`docs/implementation-plan.md` is what lets a fresh session (or a different person) resume with no
lost context — read both before starting work.

### Session 0 — 2026-07-17 — Phase 0: Inspect & set up

- Repository was not empty: verified and adjusted the existing Spring Initializr skeleton in
  place rather than regenerating it.
- Renamed base package `com.rageval.rag_evaluation_workbench` → `com.rageval.workbench`
  (Initializr derived it from the artifact ID); application class name was already correct.
- Added a placeholder Liquibase root changelog (`databaseChangeLog: []`) so
  `spring-boot-starter-liquibase` doesn't fail context startup — owns no schema yet, Phase 1
  replaces it with real changesets.
- Confirmed via `dependency:tree`: Spring Boot 4.1.0 and Spring AI 2.0.0 resolve exactly, no
  drift. Jackson 3 (`tools.jackson.*`) confirmed on the classpath.
- Inspected `Evaluator` / `EvaluationRequest` / `EvaluationResponse` / `FactCheckingEvaluator` /
  `RelevancyEvaluator` via `javap` against the resolved 2.0.0 jars — see "Spring AI 2.0.0
  evaluator API surface" above. Key finding: the built-in response shape (pass/score/single
  feedback string) is too shallow for the structured per-claim evidence Phase 5's
  `GroundednessEvaluator` needs; plan is a custom structured-output evaluator as primary, Spring
  AI's evaluators as an optional secondary signal.
- `./mvnw clean verify`: BUILD SUCCESS, 1/1 tests, run twice, no Ollama running.
- **Next step:** Phase 1 — persistence foundation. Start with the Liquibase schema under
  `src/main/resources/db/changelog/`, then domain entities per spec §6, then repositories, then
  Testcontainers PostgreSQL integration tests. Docker must be running locally for those tests.

### Session 1 — 2026-07-17 — Phase 1: Persistence foundation

- Reconciled first: re-ran `./mvnw clean verify` before starting (Docker confirmed running); it
  matched the Session 0 log exactly (BUILD SUCCESS, 1/1 test), so no reconciliation was needed.
- Liquibase: replaced the Phase 0 placeholder root changelog with one that includes 8
  logically-separated changelogs under `db/changelog/changes/` (one per aggregate table, in FK
  dependency order). Each table's changeset is split into create-table / foreign-keys / check
  constraints / indexes sub-changesets so failures are easy to localize. All check constraints use
  raw `sql` changes (enum-value `IN (...)` lists, positive-number bounds, score/confidence
  `[0,1]` ranges, a conditional constraint requiring `endpoint_url` when `adapter_type =
  'STANDARD_HTTP'`), since Liquibase's YAML column-constraint block doesn't support arbitrary
  check expressions. See "Phase 1 notes" above for the FK delete-behavior rationale per table.
- Domain entities: all 8 aggregates from spec §6 created as JPA entities (protected no-arg
  constructor for JPA, public constructor generating `UUID.randomUUID()` + setting
  `Instant.now()` timestamps, plain getters, a handful of state-transition methods where the spec
  implies one — e.g. `EvaluationDataset.markReady()`, `EvaluationRun.markStarted()`). Package
  placement: `target.domain`, `dataset.domain`, `run.domain`, `evaluation.domain`,
  `review.domain`, `classification.domain`, each paired with an `infrastructure` package holding
  the Spring Data JPA repository. Rationale for placement/​`@Version` choices in "Phase 1 notes".
- JSONB: verified Hibernate 7.4.1's built-in Jackson 3 format-mapper support via `javap` against
  the resolved jar (not assumed) before writing any mapping code — see "Phase 1 notes" for the
  evidence and the resulting `@JdbcTypeCode(SqlTypes.JSON)` usage across records / `JsonNode` /
  `Map` / `List<String>` fields.
- Testcontainers: added a Postgres-only `@TestConfiguration`
  (`persistence/PostgresTestcontainersConfiguration`) separate from the existing app-wide
  `TestcontainersConfiguration`, so the new persistence IT doesn't pay for an Ollama container it
  doesn't need. `PersistenceIntegrationTest` (4 tests) proves: context starts against the migrated
  schema with `ddl-auto=validate` (implicit — context startup fails otherwise), and every
  JSONB-mapped field round-trips correctly, including a full-graph test that chains
  TargetSystem → EvaluationDataset → EvaluationTestCase → EvaluationRun → TestExecution →
  EvaluationMetricResult → HumanReview + EvaluationFinding through `entityManager.flush()` +
  `clear()` before reloading.
- Also set `spring.jpa.open-in-view=false` (fixes a Phase 0 startup warning; see "Phase 1 notes").
- `./mvnw clean verify`: BUILD SUCCESS, 5/5 tests (1 context-loads + 4 persistence IT), Docker
  running, no local Ollama daemon required (both the existing context test and the new persistence
  test use Testcontainers-managed containers, not an external Ollama).
- `git status` after the run: only the expected new/modified files (see diff), nothing stray.
- **Deferred to Phase 2, deliberately not built now:** anything DTO/validation/API-boundary
  related, the `TargetSystemAdapter` abstraction and HTTP adapter, dataset JSON/CSV import,
  target CRUD/connection-test, WireMock tests. Also deferred: any additional `TargetSystem` columns
  Phase 2's HTTP adapter will need for non-standard response shapes (per-field JSON pointers for
  citations/chunks, input/output-token pointers) — spec §8.1 describes these as adapter
  configuration, not part of §6.1's core required-fields list, so they arrive as an additive
  Liquibase migration in Phase 2 rather than being guessed at here.
- **Next step:** Phase 2 — dataset and target configuration. Start with DTOs + validation (no JPA
  entities crossing the API boundary — architecture test coverage for this arrives with the
  Phase 2/6 ArchUnit checks), then dataset JSON/CSV import (transactional, all-or-nothing per spec
  §9), then target CRUD + connection-test, then the `TargetSystemAdapter` interface and the
  Standard HTTP adapter (Jackson-tree placeholder substitution — reuse the `JsonNode` mapping
  already in place on `TargetSystem.requestTemplate` — plus JSON Pointer response extraction),
  then WireMock adapter tests per spec §28.3.

### Session 2 — 2026-07-17 — Phase 2: Dataset and target configuration

- Reconciled first: re-ran `./mvnw clean verify` before starting (Docker confirmed running) —
  matched Session 1's log exactly (BUILD SUCCESS, 5/5 tests), so no reconciliation was needed.
- **Scope note:** this session's task list covered every Phase 2 checklist item except the
  imported-trace adapter (JSON+CSV) and `POST /api/v1/imported-traces`. Deliberately deferred that
  one item to the top of Phase 3 rather than guess at its `TestExecution`/repetition-matching shape
  before Phase 3's run orchestrator exists to define it — see "Phase 2 notes" above and the
  `docs/implementation-plan.md` Phase 2/3 checklists for the full rationale.
- **`TargetSystem` extended additively:** `connectTimeoutMilliseconds`, `followRedirects`, and a
  new nullable JSONB `responseMapping` column (input/output token pointers + non-standard
  citation/chunk per-item field mappings, spec §8.1) via migration `009`. Collapsed to one
  constructor rather than keeping a legacy-compatible overload (CLAUDE.md warns against
  backwards-compat shims); updated the two Phase 1 `PersistenceIntegrationTest` call sites to match
  and added `TargetSystem.update(...)`/`EvaluationTestCase.update(...)` full-replace methods.
  `EvaluationDataset.markReady()` now guards `DRAFT → READY` (throws `ConflictException`
  otherwise) — see "Phase 2 notes" for why this was safe to tighten now.
- **`common` package built out:** `common.error` (`ValidationException`/`NotFoundException`/
  `ConflictException`/`FieldViolation`, `GlobalExceptionHandler` → RFC 9457 `ProblemDetail`,
  `CorrelationIdFilter` → SLF4J MDC + `X-Correlation-Id` echo), `common.json`
  (`RequestTemplateSubstitutor`, `JsonPointerExtractor` — see "Phase 2 notes" for the Jackson 3
  API-surface findings behind both), `common.api.PageResponse` (controllers never return Spring
  Data's `Page` directly).
- **Target module:** `TargetSystemAdapter` domain interface (spec §8) +
  `StandardHttpTargetSystemAdapter` (`RestClient` over a per-invocation JDK `HttpClient`, exact
  retryable/non-retryable policy per spec §8.1, bounded-stream response-size enforcement,
  Jackson-tree request templating, JSON Pointer response extraction with configurable per-item
  citation/chunk field mappings). `TargetSystemAdapterRegistry` resolves by `AdapterType` so a
  second adapter (imported-trace, Phase 3) plugs in without touching call sites.
  `TargetSystemService` (CRUD, URL/secret validation, delete-rejected-with-dependent-runs via a new
  `EvaluationRunRepository.existsByTargetSystemId`), `TargetConnectionTestService` (invokes the
  adapter once for real against the configured target and reports which pointer mappings actually
  resolved), full REST surface under `/api/v1/targets` per spec §21.
- **Dataset module:** `DatasetService` (CRUD, test-case add/update gated on `isModifiable()`),
  `DatasetImportService` (JSON + CSV, both transactional/all-or-nothing, all violations collected
  before anything is persisted — see "Phase 2 notes"), `ExpectedFactValidator` (compiles every
  `REGEX`-mode pattern at import/edit time, never at evaluation time), full REST surface under
  `/api/v1/datasets` per spec §21 including `import/json`, `import/csv` (multipart), `mark-ready`,
  and per-test-case add/update.
- **Config:** `TargetProperties` (`@ConfigurationProperties`, `@ConfigurationPropertiesScan` added
  to the application class) for `maximum-response-bytes` and the retry policy — global, not
  per-target, per spec §25's suggested structure.
- **Tests:** added `wiremock-standalone:3.9.1` (test scope). 60/60 tests total this session (up
  from 5): unit tests for template substitution, JSON Pointer extraction, URL validation, and regex
  validation; a Mockito-based `DatasetImportServiceTest` for CSV parsing/validation without needing
  a database; an 11-scenario `StandardHttpTargetSystemAdapterWireMockTest` covering every case spec
  §28.3 lists (success, nested pointers, empty answer, invalid JSON, timeout-with-retry-exhaustion,
  401 non-retryable, 429-retried-then-succeeds, 500 non-retryable, missing optional arrays,
  oversized response, secret-header-injected-but-never-logged via a Logback `ListAppender`); two
  Testcontainers-backed MockMvc integration tests (`TargetSystemControllerIntegrationTest`,
  `DatasetControllerIntegrationTest`) exercising the full CRUD/import/connection-test/status-
  transition surface against a real Postgres + a real WireMock target. Made
  `PostgresTestcontainersConfiguration` public so both new IT packages could reuse Phase 1's
  Postgres-only Testcontainers wiring instead of each starting their own container.
- Rewrote `docs/data-handling.md` to describe only what's actually implemented (logging content,
  correlation IDs, secret handling, URL validation, response-size limits) instead of the Phase 0
  stub's aspirational bullet list; left a clearly-marked "not yet implemented" section for what
  genuinely arrives in later phases.
- `./mvnw clean verify`: BUILD SUCCESS, 60/60 tests, Docker running, no local Ollama daemon
  required.
- `git status` after the run: only the expected new/modified files, nothing stray (checked before
  writing this entry).
- **Next step:** Phase 3 — run orchestration. Start with the imported-trace adapter (JSON+CSV) +
  `POST /api/v1/imported-traces` deferred from this session (needs an `ImportedTraceRecord`
  entity/table — genuinely new persistence, not just an API layer), then the run state machine,
  bounded task executor, live run execution wiring `TargetSystemAdapterRegistry` +
  `TargetSystemConfigurationFactory` (both already built) into per-execution invocations, startup
  recovery for stale `RUNNING` runs, and the `demo` profile target + example dataset exercising
  every §27 scenario.

### Session 3 — 2026-07-17 — Phase 3: Run orchestration + demo target

- Reconciled first: re-ran `./mvnw clean verify` before starting (Docker confirmed running) —
  matched Session 2's log exactly (BUILD SUCCESS, 60/60 tests), so no reconciliation was needed.
- **Run state machine:** allowed-transition table enforced on `EvaluationRun` itself
  (`transitionTo` throws `ConflictException` on any illegal edge), covering all eight spec §6.4
  statuses. Resume edges (INTERRUPTED/CANCELLED/FAILED/PARTIALLY_COMPLETED → QUEUED) clear the
  previous outcome; QUEUED → INTERRUPTED doubles as the compensation for executor-queue rejection.
  Phase 1's `PersistenceIntegrationTest` needed a one-line touch (`markQueued()` before
  `markStarted()`) because CREATED → RUNNING is now correctly illegal — stated here per the
  working agreement.
- **Bounded executor + worker model:** `ThreadPoolTaskExecutor` (worker-count 2, queue-capacity
  100, AbortPolicy, graceful shutdown with 30s grace), one task per run, executions processed
  sequentially by that run's worker; per-target concurrency enforced across runs by
  `TargetConcurrencyLimiter` semaphores sized from `TargetSystem.maximumConcurrency`. Defaults per
  spec §25. See "Phase 3 notes" for why per-run (not per-execution) tasking was chosen.
- **No in-memory-only job state:** every status change is one short transaction in
  `RunStateService`, always reloading by id — crash-consistent at any point, and no `@Version`
  contention between worker and cancel endpoint.
- **Cancellation** persists CANCELLED immediately; a running worker observes it between executions
  and cancels remaining PENDING rows (in-flight invocations keep their real result). **Resume**
  re-schedules only PENDING/CANCELLED executions — completed-with-error executions are evidence
  and are never repeated; nothing-to-resume answers 409. **Startup recovery** marks stale RUNNING
  *and* QUEUED runs INTERRUPTED (queue is in-memory; an orphaned QUEUED run could never run
  again — deliberate extension of §15's letter, documented in Phase 3 notes) and stale RUNNING
  executions CANCELLED.
- **Imported traces (spec §8.2):** `POST /api/v1/imported-traces` (JSON canonical) + `/csv`
  (basic fields), all-or-nothing with row-level `traces[i].field` violations (unknown external
  test-case id in the selected dataset, non-positive/duplicate repetition, blank question).
  Creates a REPLAY-mode run + one pending execution per trace + one `imported_trace` row bound
  1:1 (migration 011); the run then starts/cancels/resumes through the normal orchestrator, with
  `ImportedTraceTargetSystemAdapter` (in `run.infrastructure`, plugged into Phase 2's registry as
  planned) serving stored data by execution id. `TargetInvocationRequest` gained a nullable
  `executionId` component for that lookup (deliberate Phase 2 touch, standard adapter ignores it);
  the RFC 4180 parser moved from `DatasetImportService` to `common.csv.CsvParser` for reuse.
- **Schema:** migration 010 adds `test_execution.attempt_count` (spec §8.1 — adapter reported it
  since Phase 2 but no column existed); `recordFailure` now persists request payload/raw response
  too. Migration 011 adds `imported_trace`.
- **Demo target (§27):** self-contained `demo` package, `@Profile("demo")`, all 14 scenarios
  keyed DEMO-001..DEMO-014 with question fallback, normalized schema + additive `usage` tokens;
  slow-scenario delay configurable (`rag-evaluation.demo.slow-response-delay-millis`).
  `examples/demo-dataset.json` exercises every scenario. Absence outside the profile is asserted
  by an integration test — which exposed and fixed a Phase 2 bug: the `Exception` catch-all in
  `GlobalExceptionHandler` turned unknown-URL 404s into 500s (`NoResourceFoundException` handler
  added).
- **Tests:** 83/83 green via `./mvnw clean verify`, Docker running, no local Ollama. New:
  `EvaluationRunStateMachineTest` (8 unit), `RunControllerIntegrationTest` (8 IT: full lifecycle
  with normalized persistence, partial failure → PARTIALLY_COMPLETED, validation, cancel +
  illegal-transition rejection, resume-never-repeats-success with WireMock request verification,
  nothing-to-resume 409, startup recovery, demo-endpoint absence),
  `ImportedTraceControllerIntegrationTest` (5 IT: JSON import→start→COMPLETED with trace data
  round-trip, row-level violations persist nothing, wrong adapter type, CSV happy path with
  quoted fields, CSV header/number errors), `DemoTargetIntegrationTest` (2 IT on a real random
  port, demo profile: full vertical slice — API-imported example dataset, real HTTP target at
  `/demo-target/chat`, run through the real orchestrator, per-scenario assertions incl. token
  extraction and TARGET_ERROR on the HTTP-500 scenario; answer rotation for the inconsistency
  scenario).
- `git status` after the run: only the expected new/modified files, nothing stray.
- **Next step:** Phase 4 — deterministic evaluation. Define the `EvaluationStrategy` interface
  (spec §10, independent of Spring AI), implement the nine deterministic evaluators (§11) against
  persisted `TestExecution` data, hook them into the run pipeline after target invocation
  (`RunWorker` currently finalizes right after invocation — evaluation slots in between),
  aggregation with error-excluding denominators (§18), evidence-based failure classification
  (§16), and the Phase 4 checkpoint: a useful report with zero Ollama dependency.

### Session 4 — 2026-07-17 — Phase 4: Deterministic evaluation ⭐ vertical-slice checkpoint

- Reconciled first: re-ran `./mvnw clean verify` before starting — matched Session 3's log
  exactly (BUILD SUCCESS, 83/83 tests), no reconciliation needed.
- **Strategy abstraction (spec §10):** `EvaluationStrategy` / `EvaluationContext` /
  `EvaluationResult` / `EvaluationApplicability` in `evaluation.domain`, Spring-AI-free.
  Applicability answers with a reason, never a bare boolean; `EvaluationResult` rejects blank
  explanations at construction — "no unexplained score" is enforced by the type, not by review.
- **All nine deterministic evaluators (§11)** in `evaluation.deterministic`, each with its
  evidence shape documented in "Phase 4 notes" above. Highlights: refusal matches multi-word
  phrases only (bare "cannot" is not a refusal — unit-tested), combines phrase detection with the
  test case's forbidden claims as the obvious-unsafe-disclosure check, and distinguishes all four
  §11.5 outcomes; expected-documents is exact-ID-only with Hit@K/first-rank/MRR in typed evidence
  metadata; citation-retrieval-consistency states in every explanation that it proves consistency,
  not citation support.
- **Pipeline wiring:** worker evaluates each execution right after persisting its result, then
  `completeRunEvaluation` (sweep + classification) runs before finalization. Cycle-free via the
  `run.application.EvaluationPipeline` interface implemented in `classification.application` —
  see Phase 4 notes for the dependency-direction reasoning. Run creation now validates
  `enabledEvaluatorKeys` and `evaluatorConfiguration` (400 with field violations on nonsense).
- **Aggregation (§18):** `RunSummaryService` computes `GET /api/v1/evaluation-runs/{id}/summary`
  on demand — per-metric counts with evaluator errors outside every pass-rate denominator,
  test-case outcomes via the spec's default mandatory-evaluator rule, pass rates by tag/severity/
  evaluator, Hit@K + MRR, latency median/p90/p95/max (`Percentiles`, R-7 interpolation),
  target error rate, human-review backlog, deterministic consistency rate. LLM-dependent rates
  deliberately absent until Phase 5.
- **Classification (§16):** rules for TARGET_SYSTEM_ERROR, HIGH_LATENCY, RETRIEVAL_MISS,
  RETRIEVAL_RANKING (with OUTDATED_DOCUMENT named as the undecidable alternative → human
  confirmation), INCOMPLETE_ANSWER, INCORRECT_ANSWER, FAILED_REFUSAL (+UNSAFE_DISCLOSURE),
  INCORRECT_CITATION, MISSING_CITATION, UNSUPPORTED_CLAIM (unanswerable answered confidently),
  INCONSISTENT_RESPONSE (deterministic §17 comparison), and the honest UNKNOWN fallback with the
  spec's "undetermined from black-box data" wording. Migration 012 adds the §16 rule outputs
  (confidence, requires_human_confirmation, supporting_metric_result_ids) to `evaluation_finding`.
- **API:** `GET /{id}/metrics` (paged), `GET /{id}/findings` (paged), `GET /{id}/summary`.
  §21's remaining run endpoints (`/replay`) arrive with Phase 7.
- **Deliberate Phase 1/3 touches (stated per the working agreement):** `EvaluationFinding`
  constructor extended for the migration-012 columns (PersistenceIntegrationTest call site
  updated + round-trip assertions added); `RunWorker`/`RunService` gained the pipeline
  dependency; repositories gained run-scoped/paged query methods.
- **Tests:** 161/161 green via `./mvnw clean verify`, Docker running, no local Ollama. 77 new
  unit tests across evaluators, percentiles, outcome/denominator logic, classification rules,
  and consistency analysis (classification rules are tested against evidence produced by the
  real evaluators, so rules and evaluators cannot drift apart). New
  `EvaluationVerticalSliceIntegrationTest` proves the Phase 4 checkpoint end to end: API-imported
  demo dataset → real HTTP demo target → run with 2 repetitions → deterministic evaluation →
  findings → summary, asserting per-scenario metric verdicts, all §16 finding categories the
  demo rigs, exact outcome counts (6 passed / 8 failed / 0 inconclusive of 14), and zero
  evaluator errors — with no Ollama anywhere.
- **Deferred deliberately:** SEMANTIC fact matching, LLM evaluators, semantic refusal, §14's
  LLM-dependent review triggers, calibration dataset + agreement service (spec §14 places them
  with the LLM evaluation work), report rendering (Phase 6), replay endpoint (Phase 7).
- **Next step:** Phase 5 — local LLM evaluation. Start from the Phase 0 API-surface findings
  ("Spring AI 2.0.0 evaluator API" above): Ollama config via env vars with the app starting fine
  without Ollama, the structured judge client abstraction + deterministic fake for tests, then
  the eight LLM evaluators as `EvaluationStrategy` implementations (custom structured-output
  primary, Spring AI evaluators optional secondary), untrusted-content prompt delimiters,
  configurable context truncation recorded in metadata, graceful degradation to metric-status
  FAILED when Ollama is down, and the `-Pollama-it` Maven profile.

### Session 5 — 2026-07-18 — Phase 5: Local LLM evaluation

- Reconciled first: Phase 4 fully checked off, `./mvnw clean verify` green (161/161), no Ollama.
  Re-verified the Phase 0 "Spring AI 2.0.0 evaluator API surface" findings against the resolved
  jars before building — they hold, with three additional 2.0.0 renames worth knowing:
  `OllamaOptions` → `OllamaChatOptions` (self-typed builder), `ChatClient.options(...)` takes a
  *builder*, and Boot 4 relocated the health API to
  `org.springframework.boot.health.contributor`. All in "Phase 5 notes" above.
- **Wrap vs. custom decision (the question this session had to answer): all eight LLM evaluators
  are fully custom** structured-output evaluators over our `JudgeClient` abstraction; Spring AI's
  `FactCheckingEvaluator` and `RelevancyEvaluator` are wrapped **nowhere**, not even as a
  secondary signal. Reasons (details in Phase 5 notes): their single pass/score/feedback-string
  response cannot carry per-claim verdicts with chunk IDs or per-chunk decisions; their fixed
  prompt templates cannot carry the spec §12 untrusted-content hardening; and as a secondary
  signal each would cost a second Ollama call per metric for evidence the custom evaluators
  already produce in structured form.
- **Judge infrastructure:** `evaluation.llm.JudgeClient` (+ `JudgeRequest`/`JudgeResponse`/
  `Judge*Exception`), `evaluation.infrastructure.OllamaJudgeClient` (ChatClient over the
  autoconfigured `OllamaChatModel`; schema enforced via Ollama's native structured-output
  `format` from `BeanOutputConverter.getJsonSchemaMap()` plus client-side re-parse; explicit
  `SystemMessage`/`UserMessage` so untrusted content never hits the template path; semaphore
  bound from `OLLAMA_MAX_CONCURRENCY`), `FakeJudgeClient` in test sources (scriptable, records
  requests for prompt-hardening assertions). Env-driven config: `LlmProperties`
  (`OLLAMA_EVALUATOR_MODEL`/`OLLAMA_TEMPERATURE`/`OLLAMA_MAX_CONCURRENCY`) +
  `spring.ai.ollama.base-url` ← `OLLAMA_BASE_URL`. Nothing contacts Ollama at startup.
- **Eight evaluators** in `evaluation.llm` (keys `llm-*`; key ordering makes claim extraction run
  before groundedness, which consumes its persisted claim evidence): claim extraction (no
  pass/fail; claims as evidence), groundedness (pass/score derived in Java from per-claim
  verdicts; "grounded ≠ correct if the source is outdated" in every explanation), reference
  correctness (context excluded unless `referenceCorrectnessUsesRetrievedContext`; NA without
  reference answer or required facts; facts-only judging flagged for review), completeness,
  answer relevance (relevance ≠ correctness), context relevance (ratios derived in Java from
  per-chunk decisions + persisted ranks; skipped without chunks), semantic refusal (runs on every
  successful answer so INCORRECT_REFUSAL is observable; pass mapping depends on
  shouldRefuse/answerable), contradiction (explicit pairs, four relations, never decides document
  authority; CONTEXT_VS_CONTEXT → review).
- **§14 triggers completed:** truncation/contradictory-docs/missing-reference/disclosure flagged
  by the observing evaluator; `CrossEvaluatorReviewTriggers` (post-loop, same-aspect pairs only)
  covers deterministic↔LLM and LLM↔LLM disagreement; FAILED evaluator results now always carry
  `requiresHumanReview`. `EvaluationStrategy` gained `evaluatorType()`/`promptVersion()`/
  `evaluatorModel()` defaults; `EvaluationService` persists them and now `saveAll`s per-execution
  results after the disagreement pass (stated Phase 4 touches, all additive).
- **Calibration:** 22 labelled examples (2 × each of the 11 §14 categories) under
  `src/test/resources/calibration/`, kept honest by `CalibrationDatasetTest`;
  `LabelAgreementService` produces agreement rate + TP/FP/TN/FN with the "small labelled
  calibration sample, not model accuracy" framing baked into the report text. Real-model
  calibration comparison logs (doesn't gate on) the numbers in `LlmJudgeOllamaIT`.
- **Degradation proven with Ollama unreachable** (`LlmDegradationIntegrationTest`, closed port):
  deterministic metrics COMPLETED, all 8 LLM metrics FAILED with `JudgeUnavailableException`
  recorded + review flag, test-case outcome still PASSED (never a chatbot failure), health
  component `evaluatorModel` DOWN with the reason. `LlmEvaluationIntegrationTest` covers the full
  happy path over the fake judge end to end.
- **Bug the first full verify caught:** Spring AI's retry autoconfig feeds `max-attempts` to
  `RetryPolicy.maxRetries` and retries `ResourceAccessException` — 8 judge calls × 2 s backoff
  stalled six pre-existing run ITs past their timeouts when Ollama was down. Fixed with
  `spring.ai.retry.max-attempts=0` (fail fast; FAILED metrics + replay are the recovery path).
  Existing evaluation-triggering ITs also pin `spring.ai.ollama.base-url=http://localhost:1` so a
  developer's real local Ollama can't make the default suite touch a model; the vertical-slice
  test now passes deterministic-only `enabledEvaluatorKeys` (it is the Phase 4 checkpoint).
- **Tests:** `./mvnw clean verify` — BUILD SUCCESS, **198/198**, run with NO Ollama on
  localhost:11434 (verified before the run). 37 new/updated unit tests + 3 new ITs.
  `-Pollama-it` not run this session (no local Ollama available); it is optional per the phase
  definition.
- `git status` after the run: only the expected new/modified files, nothing stray.
- **Next step:** Phase 6 — review and reports. Human-review workflow first (list pending
  reviews — `requiresHumanReview` flags and FAILED metrics are all persisted now — submit
  decision, override layered on top of the automated result without deleting it), then
  `EvaluationFinding` recommendation templates, then the server-rendered Thymeleaf HTML report
  (all 20 §22 sections, no JS, clearly showing which metrics were unavailable when Ollama was
  down), then `docs/evaluation-methodology.md` (must include the groundedness-vs-outdated-source
  caveat and the calibration-sample framing).

### Session 6 — 2026-07-18 — Phase 6: Review and reports

- Reconciled first: Phase 5 fully checked off; `./mvnw clean verify` re-run before starting —
  BUILD SUCCESS, 198/198, no Ollama (verified unreachable), Docker running.
- **Human-review API (spec §20):** `GET /api/v1/reviews/pending` — flagged, still-unreviewed
  metric results joined with execution + test case, filterable by run / severity / evaluator key /
  metric status, ordered CRITICAL-first then oldest (dynamic JPQL in `ReviewQueueRepository`;
  static null-guard JPQL rejected over Postgres parameter-type inference for null enum/uuid
  binds). `GET /api/v1/metric-results/{id}/review-context` serves the complete reviewable
  context (question, answer, chunks, citations, reference facts, evidence, explanation, prior
  reviews); `POST /api/v1/metric-results/{id}/reviews` records
  CONFIRMED/REJECTED/UNCERTAIN/NEEDS_DOMAIN_EXPERT with optional pass/score override **on the
  review row only** — the automated result is never mutated (unit + IT proven). CONFIRMED also
  confirms findings supported by the reviewed result. The "status" filter means *metric* status:
  COMPLETED = chatbot verdict to check, FAILED = evaluator broke — never conflated.
- **Module layering:** review is now an upper module (review → classification → evaluation →
  run); the one wrong-direction edge (RunSummaryService → HumanReviewRepository, from Phase 4)
  was inverted via the `HumanReviewLookup` interface in evaluation.application (stated Phase 4
  touch, constructor swap only). New `report` module is the top of the stack; nothing depends on
  it.
- **Recommendation templates (spec §22):** `recommendation_template` table, migration 013,
  seeded defaults for all 18 §6.8 categories; `GET/PUT /api/v1/recommendation-templates/{category}`.
  The report reads current text at render time — the IT edits RETRIEVAL_MISS and asserts the
  re-rendered report shows the edit. Findings keep their rule-generated specific
  recommendation; the report shows template + specific side by side.
- **Classification extended (additive, inert without LLM results):** groundedness-fail →
  UNSUPPORTED_CLAIM; context-relevance-fail → IRRELEVANT_CONTEXT; reference-wrong-but-grounded →
  INCORRECT_ANSWER naming OUTDATED_DOCUMENT ("grounded in outdated context but wrong against
  reference"). All judge-derived findings cap confidence at 0.6 and require human confirmation.
  Deliberately not rule-generated: MISSING_DOCUMENTATION / OUTDATED_DOCUMENT as primary
  categories (unprovable black-box) and EVALUATOR_DISAGREEMENT findings (already routed to
  review via the §14 triggers; a finding would double-report tooling signals) — reasoning in
  "Phase 6 notes".
- **Report (spec §22):** `GET /reports/runs/{id}`, Thymeleaf `run-report.html`, zero JavaScript
  (IT asserts no `<script`), all 20 sections; per-finding fragment shows every required field
  incl. template + specific recommendation and human-review status; incorrect / unsupported /
  incomplete / retrieval-failure / unknown stay separate categories with concept explanations;
  section 9 groups reliability by question type (answerable / refusal-expected / unanswerable);
  limitations section dynamically reports evaluator errors ("tool failures, never chatbot
  failures"), context truncation, and non-COMPLETED run status; human-review section prints
  automated verdict and human decision side by side.
- **`docs/evaluation-methodology.md`** written: per-evaluator measures / limitations /
  interpretation, the grounded-answer-can-be-wrong-if-source-outdated caveat, and the
  "agreement rate over a labelled sample, not model accuracy" calibration framing.
- **Tests:** `./mvnw clean verify` — BUILD SUCCESS, **208/208** (was 198), NO Ollama running
  (probed before the run). New: 5 classification-rule unit tests, 4 `HumanReviewServiceTest`
  unit tests, and `ReviewAndReportIntegrationTest` (full slice: import → demo target → run →
  queue filters → context → decision → automated-result-untouched proof → report with all 20
  headings + both verdicts → template-edit re-render → 404).
- `git status` after the run: only the expected new/modified files, nothing stray.
- **Next step:** Phase 7 — replay and comparison. `POST /api/v1/evaluation-runs/{id}/replay`
  creating a REPLAY run whose executions carry `sourceExecutionId` (schema ready since Phase 1)
  and re-evaluate stored responses through current evaluators without any live target call
  (`ImportedTraceTargetSystemAdapter` shows the adapter-side pattern; replay needs its own
  adapter or a service path that copies stored invocation results); then the baseline-vs-candidate
  comparison service + `GET /api/v1/run-comparisons`, the HTML comparison report
  (`GET /reports/comparisons?baselineRunId=..&candidateRunId=..`), and evaluator-version mismatch
  warnings sourced from the per-result evaluator version/prompt metadata persisted since Phase 5.

### Session 7 — 2026-07-19 — Phase 7: Replay and comparison

- Reconciled first: Phase 6 fully checked off; `./mvnw clean verify` re-run before starting —
  BUILD SUCCESS (exit 0), no Ollama (probed unreachable), Docker running.
- **Replay (spec §6.4/§21):** `POST /api/v1/evaluation-runs/{id}/replay` creates a CREATED
  REPLAY run — one pending execution per terminal source execution (SUCCEEDED and
  completed-with-error; PENDING/RUNNING/CANCELLED have no captured evidence and are skipped),
  each with `sourceExecutionId` pointing at the *original* captured execution (replaying a replay
  references the same originals, never a chain). Started via the normal `/start`; the worker
  branches per execution: a non-null `sourceExecutionId` copies the captured result
  (`RunStateService.copyFromSourceExecution`) instead of invoking the target — no adapter, no
  concurrency permit, and target configuration/secrets are only resolved when at least one
  execution actually needs a live call. Copied failures keep their failure status and error
  details; captured latency is copied ("the recorded latency is the measurement", same rule as
  imported traces); a source deleted after replay creation (FK `ON DELETE SET NULL`) yields a
  CANCELLED replay execution, never a chatbot failure. Evaluator keys/config default to the
  source run's, overridable in the optional request body (re-judging with updated evaluators is
  the point). No schema change needed — `source_execution_id` existed since Phase 1. Deliberate
  touches to passing code (stated per the working agreement): `RunService.validateEvaluatorSelection`
  refactored to take keys+config instead of the DTO; `RunStateService.PendingExecution` gained
  `sourceExecutionId`; `RunWorker` resolves the target lazily. Decision *against* a replay
  `TargetSystemAdapter`: replay reads run-owned executions — forcing it through the adapter
  registry (keyed by the target's adapter type, which is STANDARD_HTTP for a replayed live run)
  would have needed fake targets or special-cased resolution; the per-execution branch is smaller
  and honest (see "Phase 7 notes").
- **Comparison (spec §19):** new `comparison` module (api + application; `report` now also
  depends on it — still top-down). `GET /api/v1/run-comparisons?baselineRunId&candidateRunId`
  returns `RunComparison`: per-case changes matched by external test-case ID
  (added/removed/improved/regressed/unchanged/inconclusive — IMPROVED/REGRESSED only between two
  conclusive PASSED/FAILED outcomes; an INCONCLUSIVE/NOT_EVALUATED side is always INCONCLUSIVE,
  never a claimed change), metric pass rates with their counts and a delta only when both rates
  exist, high-severity (HIGH/CRITICAL) finding counts, latency percentiles, and per-evaluator
  version sets. Both sides are computed by `RunSummaryService.summarize`, so comparison can never
  drift from the single-run denominator rules. Differing datasets answer 400 unless
  `allowDatasetMismatch=true` (then matched by externalId with a permanent warning) — deliberate
  decision *against* a dataset-version mapping table (unneeded speculative schema).
- **Version-mismatch warnings:** distinct evaluator version/prompt/model combinations per key are
  read from the persisted metric results of each run (what actually judged them, replay included);
  any mismatch — including an evaluator producing results in only one run — emits an explicit
  "not scientifically clean" warning. Further warnings: dataset mismatch, non-terminal/failed run
  states, differing repetition counts, differing target systems.
- **HTML comparison report:** `GET /reports/comparisons?baselineRunId=..&candidateRunId=..`
  (`comparison-report.html`, no JavaScript, same visual language as the run report): warnings box
  above every number, arrow-style executive summary ("required-facts: 64% → 82%" plus
  high-severity findings and improved/regressed/inconclusive counts per the spec example),
  case-change table, metric-rate table with counts, latency table (with a REPLAY caveat that its
  latencies are captured, not new), evaluator-version table.
- **Demo target (additive):** `PUT/DELETE /demo-target/scenario-redirects` (demo profile only)
  re-routes one scenario to another's behavior to simulate a changed chatbot between baseline and
  candidate — in-memory demo state like the DEMO-014 rotation counter.
- **Tests:** `RunComparisonLogicTest` (6 unit tests: change-classification matrix, external-ID
  matching with added/removed, null-rate delta rules, version warnings incl. only-in-one-run,
  no-warning case), `ReplayRunIntegrationTest` (3 ITs: replay produces metric results + findings
  with the WireMock request count unchanged — the no-target-call proof — plus sourceExecutionId →
  originals across replay-of-replay, override/validation, and 409s for still-executing/empty
  sources), `RunComparisonIntegrationTest` (2 ITs: full §19 slice over the demo dataset with
  deliberately redirected scenarios — DEMO-002 IMPROVED, DEMO-001 REGRESSED, DEMO-014 REGRESSED
  naturally via rotation, DEMO-013 UNCHANGED, zero version warnings, HTML report asserted
  script-free; plus identity/dataset-compatibility validation). One suite-only failure caught and
  fixed: the comparison IT initially shared Spring's cached demo context (and therefore the
  DEMO-014 rotation counter) with `DemoTargetIntegrationTest` — isolated via a deliberately
  distinct property value, documented in the test.
- `./mvnw clean verify`: BUILD SUCCESS, **219/219**, NO Ollama running (probed before the run),
  Docker running.
- `git status` after the run: only the expected new/modified files, nothing stray.
- **Next step:** Phase 8 — hardening. Secret-handling review + authorization-header redaction
  audit in logs, Actuator health/info/metrics/prometheus + custom Micrometer metrics per §24 (no
  high-cardinality tags), run deletion endpoint (cascades dependent data — FK cascades from
  Phase 1 do most of the work; the endpoint and its guard rails are what's missing),
  response-size limit verification on the adapter path, finishing touches on the centralized
  `ProblemDetail` handling, OpenAPI/springdoc if Boot-4.1-compatible (else `docs/api.md`), full
  `README.md` + `.env.example` + `compose.yaml` + `scripts/` + `examples/demo-commands.sh`, and
  the full §31 end-to-end workflow verification.
