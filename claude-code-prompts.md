# Claude Code prompt script — RAG Evaluation Workbench

How to use this file:

1. The full spec lives at `docs/spec.md`, the non-negotiable constraints at `CLAUDE.md`. Both are
   already in the repo.
2. Run **one phase per Claude Code session.** Start each phase in a fresh session so context stays
   clean. State does not live in the chat — it lives on disk, in two places every prompt below
   reads and writes:
   - **`docs/implementation-plan.md`** — a "Current status" header (last completed phase / next
     phase) plus a per-phase checkbox list. This is the fast resume pointer.
   - **`docs/architecture.md`** — a "Session Log" (one dated entry per session: what changed, key
     decisions and why, what's deferred, exact next step) plus longer `## Phase N notes` sections
     for the reasoning that doesn't fit in a changelog-style entry.

   A fresh session (or a different person) should be able to read just those two files and know
   exactly what's done and what to do next — never rely on chat history surviving between
   sessions.
3. After each phase: review the diff, run `./mvnw clean verify` yourself, confirm `git status` is
   clean (or that anything left uncommitted is deliberate), and **git commit**. These commits are
   your rollback points. Do not start the next session on top of an uncommitted or inconsistent
   state.
4. Copy the prompt for the phase, paste it in, and let Claude run its own `./mvnw` commands.

A standing instruction that's already in `CLAUDE.md` but worth repeating if Claude drifts:
> Do not modify passing code from earlier phases without explaining why. `./mvnw clean verify` must
> pass without Ollama running. No core-path TODOs. Keep the repo runnable and clean — no stray
> scratch files, no committed build output.

---

## Session 0 — Phase 0: Inspect & plan  ⭐ highest-leverage session

> **Status in this repo: DONE** (see the "Current status" header in `docs/implementation-plan.md`
> and the Session 0 entry in "Session Log" in `docs/architecture.md` for exactly what happened —
> package rename, a placeholder Liquibase changelog, confirmed Spring Boot 4.1.0 / Spring AI
> 2.0.0, evaluator API inspection via `javap`). Skip straight to **Session 1**. The prompt below
> is kept as the reusable template for bootstrapping this project in a new, unrelated repository.

```
Read CLAUDE.md and docs/spec.md in full.

Execute PHASE 0 ONLY.

If docs/implementation-plan.md already exists and its "Current status" header shows Phase 0
complete, STOP immediately and tell me — do not redo Phase 0 or touch passing code.

Otherwise:
- Inspect the repository. If it already contains a generated skeleton (pom.xml, an application
  class, etc.), verify and adjust it in place — do NOT regenerate from scratch, do NOT rewrite
  unrelated files. If it's genuinely empty, generate the Maven Spring Boot skeleton with the
  coordinates and package structure from CLAUDE.md, including the Maven Wrapper.
- Confirm the local Java 21 and Maven environment (print versions).
- CRITICAL: resolve the exact dependency coordinates before writing any real code. Confirm Spring
  Boot 4.1.0 and Spring AI 2.0.0 actually resolve (./mvnw dependency:tree — evidence, not
  assumption). If either does not resolve, STOP and tell me the closest resolvable versions with
  evidence (the actual error and what's available) — do not silently substitute.
- Confirm the base package is com.rageval.workbench and the app class is
  RagEvaluationWorkbenchApplication; fix if the generator named them differently (Initializr
  derives the package from the artifact ID, which produces an underscore — check for that
  specifically).
- Run ./mvnw clean verify and confirm it passes with NO Ollama running. If it fails, diagnose
  before doing anything else. A missing Liquibase master changelog is a likely first failure if
  spring-boot-starter-liquibase is present with an empty changelog directory — an empty
  placeholder changelog (databaseChangeLog: []) is fine at this stage; Phase 1 owns the real
  schema.
- Inspect the real Spring AI 2.0.0 classes we plan to wrap (FactCheckingEvaluator,
  RelevancyEvaluator, and the Evaluator/EvaluationRequest/EvaluationResponse contracts they
  implement) by decompiling the resolved jars (javap), not from memory or older Spring AI docs.
  Confirm they exist, note their actual constructors/signatures, and flag anything that changes
  how we planned to use them (e.g. if the response shape can't carry the structured evidence a
  metric needs).
- Create docs/implementation-plan.md as a living checklist covering Phases 0-8 with checkboxes,
  plus a "Current status" header at the top (last completed phase / next phase to run / build
  state).
- Create docs/architecture.md with a "Session Log" section (one dated entry per session) and stub
  docs/data-handling.md.

Before you stop:
1. Check off every completed Phase 0 sub-item in docs/implementation-plan.md and set the "Current
   status" header to "last completed phase: 0 / next: 1".
2. Append a dated Session 0 entry to the "Session Log" in docs/architecture.md: what you found,
   what you fixed, the resolved versions, any API surprises, and the exact next step for Phase 1.
3. Run git status and confirm nothing stray (build output, scratch files) is left untracked.
STOP with a summary. Do NOT start Phase 1.
```

**Why this matters:** Spring Boot 4.1.0 / Spring AI 2.0.0 are cutting-edge. Discovering a version or
API mismatch here costs five minutes; discovering it in Phase 5 costs a day. Do not skip the
resolution check.

---

## Session 1 — Phase 1: Persistence foundation

```
Read CLAUDE.md, docs/spec.md, and docs/implementation-plan.md in full. Then read the "Current
status" header in docs/implementation-plan.md and the Session Log in docs/architecture.md.
Confirm Phase 0 is fully checked off and the repo matches what the log claims (run ./mvnw clean
verify yourself first). If anything is inconsistent, stop and reconcile it before starting new
work.

Execute PHASE 1 ONLY: persistence foundation.
- Liquibase schema under src/main/resources/db/changelog/ — one root changelog with logically
  separated included changelogs (replace the Phase 0 placeholder). UUID PKs, FKs, useful indexes,
  JSONB columns, check constraints where practical, the unique constraint on (datasetId, testCase
  externalId), and every index the spec calls out explicitly (run status, execution by run +
  test case, metric results by execution + evaluator key).
- Domain entities for every aggregate in spec section 6 (TargetSystem, EvaluationDataset,
  EvaluationTestCase, EvaluationRun, TestExecution, EvaluationMetricResult, HumanReview,
  EvaluationFinding) under the com.rageval.workbench package structure from CLAUDE.md. UUID ids
  generated by the application, Instant/UTC timestamps, enums persisted as strings, @Version
  optimistic locking on mutable aggregates. Constructor injection everywhere, no Lombok.
- The structured value records the spec requires as JSONB payloads: ExpectedFact, Citation,
  RetrievedChunk, EvaluationEvidence.
- Spring Data JPA repositories.
- Hibernate JSON mapping compatible with the resolved Spring Boot 4.1 / Hibernate version,
  verified by an integration test (not assumed).
- Testcontainers PostgreSQL integration tests proving: Liquibase migrations apply cleanly,
  ddl-auto=validate passes against the migrated schema, and JSONB columns round-trip correctly.

Set spring.jpa.hibernate.ddl-auto: validate. Liquibase owns the schema — no Hibernate
auto-generation.

Before you stop:
1. Run ./mvnw clean verify (Docker must be running locally for Testcontainers). Fix failures
   before continuing.
2. Check off every completed Phase 1 sub-item in docs/implementation-plan.md and update the
   "Current status" header (last completed phase: 1 / next: 2).
3. Append a dated Session 1 entry to the Session Log in docs/architecture.md: schema design
   decisions and why, anything deferred, exact next step for Phase 2. Add a "## Phase 1 notes"
   section above it if there's reasoning too long for the log entry (e.g. JSONB mapping choice).
4. Run git status and confirm nothing stray is left untracked.
STOP with a summary. Do NOT start Phase 2.
```

**Watch-out:** make sure Docker Desktop is running before this session, or Testcontainers fails for
environmental reasons and Claude may thrash trying to "fix" correct code.

---

## Session 2 — Phase 2: Dataset & target configuration

```
Read CLAUDE.md, docs/spec.md, docs/implementation-plan.md, docs/architecture.md. Check the
"Current status" header and Session Log; confirm Phase 1 is fully checked off and
./mvnw clean verify is green before starting. If not, stop and reconcile first.

Execute PHASE 2 ONLY.
- DTOs (never expose JPA entities across the API boundary) + Spring Validation.
- Dataset JSON import and CSV import (transactional; no partial dataset created on validation
  failure). Validate regex patterns in required/forbidden facts at import time. Return RFC 9457
  ProblemDetail with structured field errors on failure.
- EvaluationDataset status transitions: DRAFT -> READY -> ARCHIVED. A dataset marked READY must
  not be silently modified — reject the modification (a "create a new dataset version" flow can
  come later, don't over-build it now).
- Target CRUD + POST .../test-connection + URL validation (http/https only, reject other schemes,
  allow localhost since this is a local workbench, do not follow redirects unless explicitly
  configured, never log authorization headers). Persist only the secret's environment-variable
  name and optional API-key header name — never the secret value.
- TargetSystemAdapter interface (spec section 8) and the Standard HTTP adapter using RestClient:
  JSON request-template placeholder replacement done via Jackson TREE traversal (NOT unsafe global
  string substitution over serialized JSON) — replace {{question}}, {{conversationId}},
  {{testCaseId}}, {{userContext}} exact placeholder text nodes, and when {{userContext}} is the
  complete value of a node, splice in the actual JSON object, not a quoted string. Response
  extraction via JSON Pointer (answer pointer mandatory, others optional), with configurable
  per-item field pointers for citations and retrieved chunks. Configurable connect/read timeouts.
  Measure latency in the adapter/evaluator service, never trust client-reported latency. The
  retryable/non-retryable policy exactly as spec 8.1 (never retry 400/401/403/404/invalid JSON/
  missing answer field; small configurable retry for connection reset, timeout-when-permitted,
  429, 502, 503, 504). Persist every attempt or at minimum final attempt count + last error.
- Target connection-test endpoint that actually validates the configured pointer mappings against
  a real (or WireMock-simulated) call.
- WireMock integration tests: success, nested pointers, empty answer, invalid JSON, timeout, 401,
  429, 500, retryable vs non-retryable, missing optional citation/chunk arrays, oversized response
  (respect maximum-response-bytes), and secret-header injection WITHOUT logging the secret value
  (assert on log output).
- Update docs/data-handling.md to describe what's now actually true: authorization headers are
  redacted in logs, secrets are stored by name only. Don't leave it aspirational.

Before you stop:
1. Run ./mvnw clean verify (covers Phase 0-2). Fix failures before continuing.
2. Check off every completed Phase 2 sub-item in docs/implementation-plan.md and update the
   "Current status" header (last completed phase: 2 / next: 3).
3. Append a dated Session 2 entry to the Session Log in docs/architecture.md: adapter design
   decisions, exact next step for Phase 3.
4. Run git status and confirm nothing stray is left untracked.
STOP with a summary. Do NOT start Phase 3.
```

---

## Session 3 — Phase 3: Run orchestration + demo target

```
Read CLAUDE.md, docs/spec.md, docs/implementation-plan.md, docs/architecture.md. Check the
"Current status" header and Session Log; confirm Phase 2 is fully checked off and
./mvnw clean verify is green before starting. If not, stop and reconcile first.

Execute PHASE 3 ONLY.
- Run state machine with every status from spec 6.4 (CREATED, QUEUED, RUNNING,
  PARTIALLY_COMPLETED, COMPLETED, FAILED, CANCELLED, INTERRUPTED) and persisted status
  transitions (no in-memory-only job state).
- Bounded task executor: configurable worker count + queue capacity, per-target maximum
  concurrency, graceful shutdown. NO unbounded CompletableFuture usage, NO common fork-join pool.
- POST run creation returns HTTP 202 fast (validate target + dataset first); a worker marks the
  run RUNNING, creates pending TestExecution records for every repetition of every test case, then
  invokes the target per execution and persists the normalized result plus target model, token
  counts, latency, HTTP status, and error info.
- Cancellation support. A resume endpoint that schedules only incomplete executions, never
  repeating ones that already succeeded.
- On application startup, mark stale RUNNING runs as INTERRUPTED — do not silently resume them.
- Imported-trace adapter (spec 8.2): JSON canonical format (rich) + CSV (basic fields), validating
  that imported external test-case IDs map to the selected dataset, with clear row-level
  validation errors. RunMode.REPLAY conceptually starts here even though full replay logic is
  Phase 7 — TestExecution.sourceExecutionId exists from Phase 1's schema.
- Demo target under the `demo` Spring profile (spec section 27) at POST /demo-target/chat,
  returning the normalized schema, with every deliberate scenario the spec lists: correct answer,
  incorrect version, retrieval miss, retrieval ranking failure, unsupported claim, incomplete
  answer, correct refusal, refusal followed by disclosure, missing citation, citation to an
  unretrieved document, contradictory chunks, slow response, HTTP error, and inconsistent answer
  across repetitions. Selected by question/external test-case ID. Disabled outside the demo
  profile — verify it's actually absent from the non-demo context.
- An example dataset under examples/ that exercises every one of those scenarios.

Before you stop:
1. Run ./mvnw clean verify (covers Phase 0-3, still no Ollama). Fix failures before continuing.
2. Check off every completed Phase 3 sub-item in docs/implementation-plan.md and update the
   "Current status" header (last completed phase: 3 / next: 4).
3. Append a dated Session 3 entry to the Session Log in docs/architecture.md: orchestration model
   decisions (executor sizing defaults, cancellation semantics), exact next step for Phase 4.
4. Run git status and confirm nothing stray is left untracked.
STOP with a summary. Do NOT start Phase 4.
```

---

## Session 4 — Phase 4: Deterministic evaluation  ⭐ first real milestone

```
Read CLAUDE.md, docs/spec.md, docs/implementation-plan.md, docs/architecture.md. Check the
"Current status" header and Session Log; confirm Phase 3 is fully checked off and
./mvnw clean verify is green before starting. If not, stop and reconcile first.

Execute PHASE 4 ONLY.
- EvaluationStrategy domain interface (spec section 10), independent of Spring AI: key(),
  version(), applicability(EvaluationContext) returning APPLICABLE / NOT_APPLICABLE /
  MISSING_REQUIRED_DATA (a real reason, not a boolean supports()), and evaluate(EvaluationContext)
  returning passed/score/confidence/explanation/evidence/requiresHumanReview. Evidence is the
  structured EvaluationEvidence record from spec 10 — no evaluator may return an unexplained
  score.
- All nine deterministic evaluators from spec section 11: TransportEvaluator, LatencyEvaluator
  (with a correct, unit-tested percentile implementation — median/p90/p95/max at run level, plus
  per-execution pass/fail against maximumAcceptableLatencyMilliseconds), RequiredFactsEvaluator
  (EXACT_TEXT/CASE_INSENSITIVE_TEXT/REGEX for now, SEMANTIC deferred to Phase 5; fail on any
  missing mandatory fact; score = matched mandatory / total mandatory; not-applicable when no
  facts), ForbiddenClaimsEvaluator, RefusalEvaluator (phrase-based only for now — don't mark every
  "cannot" as a correct refusal; distinguish correct refusal / attempted-refusal-then-unsupported-
  answer / direct-answer-when-refusal-required / empty-or-error), CitationPresenceEvaluator
  (required/optional/forbidden by tag or dataset), ExpectedDocumentEvaluator (Hit@1/3/5, first
  relevant rank, reciprocal rank — exact document ID match only, no fuzzy title matching),
  CitationRetrievalConsistencyEvaluator (flags citations to documents absent from retrieved
  chunks; explicitly does NOT claim this proves citation support), AnswerabilityEvaluator
  (deterministic signals only for now for answerable=false / shouldRefuse=true cases).
- Aggregation service (spec section 18): evaluator errors NEVER counted in the chatbot pass-rate
  denominator; per-metric applicable/completed/passed/failed/error/not-applicable/unreviewed/
  human-confirmed counts and pass rate; run-level pass rate overall, by tag, by severity, by
  evaluator; test-case outcome as PASSED/FAILED/INCONCLUSIVE/NOT_EVALUATED using the spec's
  default rule (passes only if all enabled mandatory evaluators that completed successfully
  pass; evaluator infra failures make it INCONCLUSIVE, never FAILED).
- Evidence-based failure classification rules from spec section 16, BEFORE any LLM classifier.
  Each rule produces category + explanation + supporting metric IDs + supporting evidence +
  confidence + whether human confirmation is required. When data only proves a symptom, say so
  explicitly ("Root cause: undetermined from black-box data") rather than guessing a category.
- Unit tests for every item spec 28.1 lists that applies to this phase (required-fact matching,
  forbidden-claim matching, regex validation, refusal phrase behavior, Hit@K, reciprocal rank,
  citation/chunk consistency, percentile calculations, aggregation denominator logic, failure
  classification rules). Integration test proving the full vertical slice through the demo target.

GOAL FOR THIS PHASE: the application must already produce a useful, evidence-backed result set
WITHOUT Ollama. Prove it end to end: import dataset -> configure demo target -> run with
repetitions -> deterministic eval -> aggregated summary, all in one integration test.

Before you stop:
1. Run ./mvnw clean verify (no Ollama). Fix failures before continuing.
2. Check off every completed Phase 4 sub-item in docs/implementation-plan.md and update the
   "Current status" header (last completed phase: 4 / next: 5).
3. Append a dated Session 4 entry to the Session Log in docs/architecture.md: aggregation/
   classification decisions, exact next step for Phase 5.
4. Run git status and confirm nothing stray is left untracked.
STOP with a summary.
```

**This is your key checkpoint.** Once this passes, everything after is additive and lower-risk. Tag
the commit (e.g. `git tag phase-4-vertical-slice`).

---

## Session 5 — Phase 5: Local LLM evaluation

```
Read CLAUDE.md, docs/spec.md, docs/implementation-plan.md, docs/architecture.md. Check the
"Current status" header and Session Log; confirm Phase 4 is fully checked off and
./mvnw clean verify is green (still no Ollama) before starting. If not, stop and reconcile first.

Also re-read the "Spring AI 2.0.0 evaluator API surface" notes recorded in docs/architecture.md
from Phase 0 before designing GroundednessEvaluator / AnswerRelevanceEvaluator — confirm those
findings still hold against the resolved jars before building on them.

Execute PHASE 5 ONLY.
- Environment-driven Ollama config: OLLAMA_BASE_URL, OLLAMA_EVALUATOR_MODEL, OLLAMA_TEMPERATURE,
  OLLAMA_MAX_CONCURRENCY. Do NOT require a specific model to compile or start the application —
  it must still start with Ollama entirely unreachable.
- A structured judge-call abstraction with a DETERMINISTIC FAKE implementation used by the normal
  test suite. Real-Ollama tests go behind the -Pollama-it Maven profile only; the default
  ./mvnw clean verify must never touch a real model.
- The eight LLM evaluators from spec section 13, each as an EvaluationStrategy, using structured
  output mapped to the Java records the spec defines (no fragile prose regex parsing):
  ClaimExtractionEvaluator, GroundednessEvaluator (per-claim SUPPORTED/PARTIALLY_SUPPORTED/
  CONTRADICTED/NOT_IN_CONTEXT with supporting/contradicting chunk IDs — build this as a custom
  structured evaluator per the Phase 0 findings; only wrap Spring AI's FactCheckingEvaluator as an
  optional secondary signal if it adds value, don't force it), ReferenceCorrectnessEvaluator
  (question + reference answer + required/forbidden facts vs. actual answer; NOT authoritative
  against retrieved context unless explicitly configured; not-applicable when reference answer is
  missing and required facts don't provide sufficient authority), CompletenessEvaluator (must not
  penalize harmless wording differences), AnswerRelevanceEvaluator (may wrap Spring AI's
  RelevancyEvaluator but must preserve our own structured evidence/version metadata; relevance
  does not imply correctness), ContextRelevanceEvaluator (per-chunk decision; relevant/retrieved
  ratio, relevant-in-top-3, irrelevant-top-ranked; skip entirely when there are no chunks),
  SemanticRefusalEvaluator (CORRECT_REFUSAL / PARTIAL_REFUSAL_WITH_DISCLOSURE / INCORRECT_REFUSAL
  / NO_REFUSAL), ContradictionEvaluator (answer-vs-reference, answer-vs-context,
  context-vs-context, answer-vs-itself; explicit conflicting-statement pairs; do not decide which
  document is authoritative unless document metadata proves it).
- System prompts must mark the chatbot response and retrieved context as UNTRUSTED data, forbid
  following any instructions embedded in them, forbid outside factual knowledge unless the metric
  explicitly permits it, and require returning only the defined schema. Use explicit delimiters
  around untrusted content. Cap context length via configurable limits; never truncate silently —
  record truncation in metadata and flag for human review when it may affect the result.
- Graceful Ollama unavailability end to end: deterministic evaluators keep working, LLM evaluator
  results become status FAILED (never a chatbot failure), health details identify evaluator-model
  unavailability, and this is exercised by a test that runs with Ollama unreachable.
- Calibration dataset under src/test/resources/calibration/ with at least 20 clearly labelled
  examples covering every category spec section 14 lists, and a service comparing automated vs.
  human labels producing agreement rate, true/false positives/negatives — labelled clearly as a
  small calibration sample, never called "model accuracy" without that context.
- Human-review-required triggers from spec section 14 wired into the evaluators/aggregation (low
  LLM confidence, deterministic/LLM disagreement, two LLM evaluators disagreeing, HIGH/CRITICAL
  severity, context truncation, contradictory documents, missing reference answer,
  security/permission-sensitive tag, malformed evaluator output).
- Update docs/data-handling.md: confirm and document that no client content is sent to any
  external/paid API — Ollama only — and that Spring AI's prompt/completion observation export
  stays disabled unless explicitly opted into.

CRITICAL: `./mvnw clean verify` must STILL pass with NO Ollama running. Verify that explicitly —
run it with Ollama stopped and show the output.

Before you stop:
1. Run ./mvnw clean verify (no Ollama) and, if you have Ollama + a model available locally,
   optionally ./mvnw clean verify -Pollama-it as a sanity check (not required for the phase to be
   considered done).
2. Check off every completed Phase 5 sub-item in docs/implementation-plan.md and update the
   "Current status" header (last completed phase: 5 / next: 6).
3. Append a dated Session 5 entry to the Session Log in docs/architecture.md: which evaluators
   wrap Spring AI vs. are fully custom and why, exact next step for Phase 6.
4. Run git status and confirm nothing stray is left untracked.
STOP with a summary. Do NOT start Phase 6.
```

**Watch-out:** confirm the "no Ollama" build is green before you even think about pulling a model.
The fake judge is what keeps the normal suite deterministic.

---

## Session 6 — Phase 6: Review & reports

```
Read CLAUDE.md, docs/spec.md, docs/implementation-plan.md, docs/architecture.md. Check the
"Current status" header and Session Log; confirm Phase 5 is fully checked off and
./mvnw clean verify is green (no Ollama) before starting. If not, stop and reconcile first.

Execute PHASE 6 ONLY.
- Human-review API (spec section 20): GET /api/v1/reviews/pending with filters by run, severity,
  evaluator, status; full context (question, answer, retrieved context, reference, evidence,
  evaluator explanation) readable per result; POST /api/v1/metric-results/{metricResultId}/reviews
  to submit a decision (CONFIRMED/REJECTED/UNCERTAIN/NEEDS_DOMAIN_EXPERT), override pass/fail and
  score, add a comment. An override must NEVER delete the automated result — persist both and
  surface both later in the report.
- EvaluationFinding persistence (spec 6.8) with every failure category from spec section 16, and
  rule-based recommendation templates tied to failure categories (not unconstrained LLM output) —
  make them manually editable, not hardcoded strings baked into the template only.
- The human-review-required triggers from spec section 14 actually surface findings/flags in this
  phase's API and report (they were computed starting Phase 5; this phase is where they become
  visible and actionable).
- Server-rendered Thymeleaf HTML report (spec section 22) — must work with NO JavaScript. All 20
  sections: title/metadata, scope, limitations, executive summary, outcome counts, metrics table,
  results by severity, results by tag, reliability by category, high/critical findings,
  representative failure examples, retrieval findings, refusal/unsupported-claim findings,
  consistency findings, latency/target errors, human-review status, prioritized recommendations,
  acceptance criteria, methodology, evaluator configuration/versions. Every major finding shows
  question, expected behavior, actual response, relevant reference facts, retrieved chunks,
  citations, failed metrics, observed problem, likely cause (if supported by evidence),
  severity, evidence, recommended change, acceptance criteria, and human-review status. The report
  must distinguish incorrect / unsupported / incomplete / retrieval-failure / unknown-root-cause
  answers as separate concepts, not one blended "failed" bucket.
- GET /reports/runs/{id} serving the rendered report.
- Integration test rendering the report from a full demo run (dataset import -> target -> run ->
  evaluation -> report), asserting key sections are present.
- Create docs/evaluation-methodology.md explaining what each evaluator actually measures, its
  known limitations (e.g. "a grounded answer can still be wrong if its source document is
  outdated"), and how it should and shouldn't be interpreted.

Before you stop:
1. Run ./mvnw clean verify (no Ollama). Fix failures before continuing.
2. Check off every completed Phase 6 sub-item in docs/implementation-plan.md and update the
   "Current status" header (last completed phase: 6 / next: 7).
3. Append a dated Session 6 entry to the Session Log in docs/architecture.md: report structure
   decisions, exact next step for Phase 7.
4. Run git status and confirm nothing stray is left untracked.
STOP with a summary. Do NOT start Phase 7.
```

---

## Session 7 — Phase 7: Replay & comparison

```
Read CLAUDE.md, docs/spec.md, docs/implementation-plan.md, docs/architecture.md. Check the
"Current status" header and Session Log; confirm Phase 6 is fully checked off and
./mvnw clean verify is green (no Ollama) before starting. If not, stop and reconcile first.

Execute PHASE 7 ONLY.
- Replay runs (EvaluationRun.runMode = REPLAY): re-run the evaluator pipeline against previously
  captured TestExecutions without calling the target chatbot again; each new TestExecution's
  sourceExecutionId references the original captured execution. POST
  /api/v1/evaluation-runs/{id}/replay creates one.
- Baseline vs. candidate comparison (spec section 19): compare two runs by test-case externalId
  (same dataset, or an explicitly mapped compatible dataset version); classify each case as
  added/removed/improved/regressed/unchanged/inconclusive; compare metric rates, high-severity
  finding counts, latency, and evaluator versions used in each run. When evaluator versions differ
  between the two runs, the comparison must surface an explicit warning — never present it as
  scientifically clean without that caveat.
- GET /api/v1/run-comparisons?baselineRunId=...&candidateRunId=... returning the structured
  comparison, and GET /reports/comparisons?baselineRunId=...&candidateRunId=... as an HTML
  comparison report in the same style summary the spec shows (e.g. "Correctness: 64% -> 82%").
- Integration tests: a replay run producing metric results without any target HTTP call, and a
  baseline/candidate comparison across two runs of the demo dataset with a deliberately changed
  demo-target scenario.

Before you stop:
1. Run ./mvnw clean verify (no Ollama). Fix failures before continuing.
2. Check off every completed Phase 7 sub-item in docs/implementation-plan.md and update the
   "Current status" header (last completed phase: 7 / next: 8).
3. Append a dated Session 7 entry to the Session Log in docs/architecture.md: comparison semantics
   decisions, exact next step for Phase 8.
4. Run git status and confirm nothing stray is left untracked.
STOP with a summary. Do NOT start Phase 8.
```

---

## Session 8 — Phase 8: Hardening + final verification

```
Read CLAUDE.md, docs/spec.md, docs/implementation-plan.md, docs/architecture.md. Check the
"Current status" header and Session Log; confirm Phase 7 is fully checked off and
./mvnw clean verify is green (no Ollama) before starting. If not, stop and reconcile first.

Execute PHASE 8.
- Security/secret-handling review across the whole codebase: authorization headers redacted in
  every log path, only secret env-var names persisted (never values), response-size limits
  enforced on the target adapter, no full prompt/answer/citation/chunk/document content logged by
  default anywhere (grep for it). Add a dev-only opt-in flag for verbose content logging.
- Observability: Actuator health/info/metrics/prometheus endpoints exposed. The application
  metrics from spec section 24 (target invocations/failures/latency, evaluation runs, test
  executions, evaluator executions/failures/duration, human-review backlog, LLM evaluator
  calls/failures), tagged carefully — no question text, client content, or test-case IDs as tags.
  Structured logs carrying runId/executionId/targetSystemId/evaluatorKey.
- Run + dependent-data deletion service/endpoint. Configurable raw-response storage and
  retrieved-chunk-content storage (rag-evaluation.storage.*). A documented local-only deployment
  mode. Confirm Spring AI prompt/completion observation export stays disabled unless explicitly
  enabled (re-verify the Phase 5 claim).
- Centralized exception handling via ProblemDetail (RFC 9457) with correlation/request IDs in
  both the error response and the logs.
- OpenAPI documentation via a springdoc version verified compatible with Spring Boot 4.1.0 — check
  this for real, don't assume. If no compatible stable version exists, do NOT force it: document
  the API in docs/api.md instead (with complete cURL examples for the end-to-end demo) and explain
  the limitation plainly.
- ArchUnit tests (spec 28.5), kept lightweight: controllers don't depend on repositories directly,
  domain packages don't depend on web/api packages, no controller returns a JPA entity.
- compose.yaml (PostgreSQL + Ollama, named volumes, health checks — app itself does not need to
  run in Docker). scripts/pull-models.sh (reads the model name from an env var, fails clearly if
  Ollama is unreachable, does not auto-download a large model on every startup),
  scripts/start-local.sh, scripts/reset-local-data.sh. .env.example with no real secrets.
  examples/demo-commands.sh covering the full workflow in spec section 31, safe and executable,
  using jq only behind an installed-check with a fallback explanation.
- Rewrite docs/data-handling.md for real (not append) — it should describe what the running code
  actually does at this point: logging behavior, secret handling, configurable raw-response/chunk
  storage, deletion, retention, no external LLM calls, local-only mode, and sample limitation
  language (explicitly not a legal/compliance claim).
- Full README.md per spec section 30 with copy-pasteable commands for every step: what the service
  does/doesn't do, architecture overview, requirements, starting Postgres+Ollama, pulling the
  evaluator model, running the app, running tests, running the optional Ollama integration tests,
  using the demo profile, the full evaluation workflow (import dataset, configure target, test
  connection, create run, start run, poll status, open report, submit human review, replay run,
  compare runs), data-handling limitations, known MVP limitations. Verify every command in it
  actually works against the real implementation before finalizing — run them.

Then run the DEFINITION OF DONE checklist (spec section 34) end to end and report each item's
status explicitly (done / not done / partially, with evidence).

Before you stop:
1. Run ./mvnw clean verify one final time and confirm BUILD SUCCESS with no Ollama running.
2. Check off every remaining sub-item in docs/implementation-plan.md across all phases and set the
   "Current status" header to "last completed phase: 8 (MVP complete) / next: none — see deferred
   features".
3. Append a final dated Session 8 entry to the Session Log in docs/architecture.md summarizing the
   whole build and listing genuinely deferred features.
4. Run git status and confirm the working tree is clean (or that anything left is deliberate and
   explained).
Finish with the spec section 35 final report: architecture summary, file tree, exact build/test
commands, test results, startup commands, demo workflow commands, required Ollama model config,
deferred features, unresolved blockers with concrete evidence, and the next three most valuable
steps if this were to continue past the MVP.
```

---

## If Claude drifts mid-phase

Short correction prompts that work well:

- `Stop. Re-read CLAUDE.md. You're violating the constraint on <X>. Fix that before continuing.`
- `That API doesn't exist in Spring AI 2.0.0. Inspect the resolved dependency source (javap or the
  extracted jar) and use the real signature — don't guess from older Spring AI versions.`
- `You changed passing Phase N code without explaining why. Justify it or revert it.`
- `./mvnw clean verify must pass without Ollama. Run it now and show me the output.`
- `You left a TODO on a core path. Implement it or tell me why it's genuinely blocked.`
- `Before you go further: update docs/implementation-plan.md's Current status header and append a
  Session Log entry in docs/architecture.md for what you've done so far, in case this session gets
  cut short.`
- `git status is showing stray files. Clean those up or tell me why they need to stay.`
