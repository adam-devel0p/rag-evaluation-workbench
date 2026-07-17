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

## Later phases

To be filled in as Phases 1–8 land (schema design rationale, orchestration model, evaluator
wrapping decisions actually implemented, comparison semantics, etc). Each phase gets its own
`## Phase N notes` section above this one with the reasoning behind decisions; the Session Log
below is the short chronological index into that detail.

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
