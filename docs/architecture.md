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

## Later phases

To be filled in as Phases 2–8 land (orchestration model, evaluator wrapping decisions actually
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
