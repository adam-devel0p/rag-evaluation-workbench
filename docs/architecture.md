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

## Later phases

To be filled in as Phases 3–8 land (orchestration model, evaluator wrapping decisions actually
implemented, comparison semantics, etc). Each phase gets its own `## Phase N notes` section above
this one with the reasoning behind decisions; the Session Log below is the short chronological
index into that detail.

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
