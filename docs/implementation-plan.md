# Implementation Plan — RAG Evaluation Workbench

Living checklist. Update after every phase (check items off, add notes). See `docs/spec.md` for
full requirements and `CLAUDE.md` for non-negotiable constraints.

## Current status

**Last completed phase:** 7 — Replay and comparison
**Next phase to run:** 8 — Hardening
**Build state:** `./mvnw clean verify` green (219/219 tests), verified with NO Ollama running
(Docker must be running for the Postgres Testcontainers-backed ITs). Replay
(`POST /api/v1/evaluation-runs/{id}/replay` → REPLAY run whose worker copies captured results by
`sourceExecutionId`, zero target HTTP calls — proven by WireMock request counting in
`ReplayRunIntegrationTest`) and the §19 baseline-vs-candidate comparison
(`GET /api/v1/run-comparisons` + the no-JS HTML report at `GET /reports/comparisons`, with
explicit evaluator-version-mismatch warnings) are proven end to end, the latter by
`RunComparisonIntegrationTest` over a deliberately changed demo target. Real-model tests:
`./mvnw clean verify -Pollama-it` (needs Ollama + `OLLAMA_EVALUATOR_MODEL` pulled).

This header is the fast resume pointer — update it as the last step of every session. For the
full history and reasoning behind each session's decisions, see "Session Log" in
`docs/architecture.md`. Before starting any phase, confirm every checkbox in the previous phase(s)
below is actually checked; if something is unchecked or contradicts the repo state, reconcile that
first instead of proceeding.

## Phase 0 — Inspect and set up

- [x] Inspect repository (Initializr-generated skeleton, not empty)
- [x] Confirm Java 21 and Maven wrapper environment
- [x] Fix base package `com.rageval.rag_evaluation_workbench` → `com.rageval.workbench`
      (Initializr derived the package from the artifact ID; moved `RagEvaluationWorkbenchApplication`,
      test class, `TestcontainersConfiguration`, `TestRagEvaluationWorkbenchApplication`)
- [x] Confirm application class name `RagEvaluationWorkbenchApplication` (already correct)
- [x] Add minimal empty Liquibase root changelog (`db.changelog-master.yaml`) so
      `spring-boot-starter-liquibase` doesn't fail context startup before Phase 1 adds real
      changesets
- [x] Run `./mvnw clean verify` — BUILD SUCCESS, 1/1 tests passed, no Ollama running
- [x] Confirm resolved dependency versions: Spring Boot 4.1.0, Spring AI 2.0.0 (see
      `docs/architecture.md` for the dependency-tree evidence)
- [x] Inspect real Spring AI 2.0.0 `FactCheckingEvaluator` / `RelevancyEvaluator` /
      `Evaluator` / `EvaluationRequest` / `EvaluationResponse` API surface (see
      `docs/architecture.md` — "Spring AI 2.0.0 evaluator API" for signatures and surprises)
- [x] Create `docs/implementation-plan.md` (this file)
- [x] Create stub `docs/architecture.md` and `docs/data-handling.md`

**Not done in Phase 0 (deliberately deferred):** package structure under
`com.rageval.workbench` (target/dataset/run/evaluation/...), Liquibase schema, domain entities,
README, `.env.example`, `compose.yaml`, `examples/`, `scripts/`. These start in Phase 1.

## Phase 1 — Persistence foundation

- [x] Liquibase schema (root changelog + included changelogs) under
      `src/main/resources/db/changelog/` (8 included changelogs, one per aggregate table)
- [x] Domain entities: TargetSystem, EvaluationDataset, EvaluationTestCase, EvaluationRun,
      TestExecution, EvaluationMetricResult, HumanReview, EvaluationFinding
- [x] UUID PKs, FKs, indexes, check constraints, unique constraint (dataset + external test-case ID)
- [x] JSONB mapping compatible with Hibernate 7 / Spring Boot 4.1, verified by an integration test
      (Hibernate 7.4.1's built-in `Jackson3JsonFormatMapper`, auto-selected since only Jackson 3 is
      on the classpath — see "Phase 1 notes" below)
- [x] Repositories (Spring Data JPA)
- [x] `spring.jpa.hibernate.ddl-auto=validate`
- [x] Testcontainers PostgreSQL integration tests (migrations run, JSONB round-trips) —
      `PersistenceIntegrationTest`, 4 tests, Postgres-only Testcontainers config (no Ollama)

## Phase 2 — Dataset and target configuration

- [x] DTOs + explicit validation (no JPA entities across the API boundary)
- [x] Dataset JSON import (transactional, all-or-nothing)
- [x] Dataset CSV import
- [x] `EvaluationDataset` status transitions (DRAFT → READY → ARCHIVED), no silent edits once READY
- [x] Target CRUD, secret-name-only persistence, URL scheme validation (http/https only)
- [x] Target connection-test endpoint
- [x] `TargetSystemAdapter` abstraction + Standard HTTP adapter (`RestClient`, Jackson-tree
      placeholder substitution, JSON Pointer response extraction)
- [x] Retry policy (retryable vs non-retryable per spec §8.1)
- [x] WireMock adapter tests (success, timeouts, 401/429/500, malformed JSON, oversized response,
      secret header redaction)

**Deliberately moved to Phase 3:** Imported-trace adapter (JSON + CSV, spec §8.2) and the
`POST /api/v1/imported-traces` endpoint. This session's scope (see Session 2 log) explicitly
enumerated Phase 2's deliverables and did not include it. It also has a real dependency this
session doesn't: matching an imported trace to the right repetition needs `TestExecution`/run
context that doesn't exist until Phase 3's run orchestration lands — building the import endpoint
now would mean guessing at that shape rather than deriving it from the real orchestrator. Tracked
as the first item in Phase 3's checklist below.

## Phase 3 — Run orchestration

- [x] Imported-trace adapter (JSON + CSV, spec §8.2) + `POST /api/v1/imported-traces` (+ `/csv`
      multipart variant), moved here from Phase 2 — implemented as a real `TargetSystemAdapter`
      (`ImportedTraceTargetSystemAdapter` in the `run` module) resolving the stored trace by
      execution id; import creates a REPLAY-mode run + one pending execution per trace, bound 1:1
      to an `imported_trace` row (migration 011); row-level validation errors
      (`traces[i].field`), all-or-nothing
- [x] Run state machine (CREATED → QUEUED → RUNNING → PARTIALLY_COMPLETED/COMPLETED/FAILED/
      CANCELLED/INTERRUPTED) — allowed-transition table enforced on the `EvaluationRun` entity
      itself; every transition persisted via `RunStateService` (one short transaction per step,
      no in-memory-only job state)
- [x] Bounded task executor (`rag-evaluation.execution.*`: worker-count 2, queue-capacity 100;
      AbortPolicy + compensating INTERRUPTED on rejection; per-target concurrency from
      `TargetSystem.maximumConcurrency` via `TargetConcurrencyLimiter` semaphores; graceful
      shutdown waits `shutdown-grace-seconds`; no `CompletableFuture`, no common ForkJoinPool)
- [x] Live run execution creates pending `TestExecution` rows before invoking the target; persists
      answer/citations/chunks/model/tokens/latency/HTTP status/error info + attempt count
      (migration 010)
- [x] Cancellation support (persisted CANCELLED immediately; a running worker observes it between
      executions and cancels the remainder; in-flight invocation keeps its real result)
- [x] Startup recovery: stale RUNNING **and** QUEUED runs → INTERRUPTED (queue is in-memory, so an
      orphaned QUEUED run could never run again); stale RUNNING executions → CANCELLED; resume
      endpoint re-schedules only PENDING/CANCELLED executions, never SUCCEEDED or
      completed-with-error ones
- [x] Demo target under `demo` profile with all 14 §27 scenarios (`POST /demo-target/chat`,
      selected by external test-case ID DEMO-001..DEMO-014 with question-keyword fallback) +
      `examples/demo-dataset.json`; non-demo context verified to answer 404 on that path

## Phase 4 — Deterministic evaluation

- [x] `EvaluationStrategy` domain interface (key/version/applicability/evaluate), independent of
      Spring AI — `EvaluationApplicability` carries a real reason (APPLICABLE /
      NOT_APPLICABLE / MISSING_REQUIRED_DATA + why), `EvaluationResult` rejects blank
      explanations (no unexplained scores, enforced in the constructor)
- [x] TransportEvaluator, LatencyEvaluator (correct percentile impl + tests), RequiredFactsEvaluator,
      ForbiddenClaimsEvaluator, RefusalEvaluator (phrase-based), CitationPresenceEvaluator,
      ExpectedDocumentEvaluator (Hit@K, MRR), CitationRetrievalConsistencyEvaluator,
      AnswerabilityEvaluator — all nine wired into the run worker via the
      `run.application.EvaluationPipeline` interface (implementation in
      `classification.application`, keeping package dependencies acyclic)
- [x] Aggregation service (denominators exclude evaluator errors; run-level stats per §18) —
      `RunSummaryService` computed on demand at `GET /{id}/summary`; test-case outcomes
      PASSED/FAILED/INCONCLUSIVE/NOT_EVALUATED per the spec default rule
      (`TestCaseOutcomeCalculator`); zero-denominator rates are null, never 0/1
- [x] Evidence-based failure classification rules (§16) — `FailureClassificationService`,
      including the honest UNKNOWN ("Root cause: undetermined from black-box data") and the
      deterministic §17 repetition-consistency comparison (INCONSISTENT_RESPONSE); migration 012
      adds confidence / requires_human_confirmation / supporting_metric_result_ids to
      `evaluation_finding`; served at `GET /{id}/findings`
- [x] Unit + integration tests — 77 new unit tests (fact/claim matching, regex, refusal phrases,
      Hit@K, reciprocal rank, citation/chunk consistency, percentiles, aggregation denominators,
      outcome rules, classification rules, consistency analysis, evaluator-error handling) +
      `EvaluationVerticalSliceIntegrationTest`
- [x] Checkpoint: app produces a useful report with zero Ollama dependency — proven end to end:
      import demo dataset → configure demo target → run with 2 repetitions → deterministic eval →
      findings → aggregated summary, all through the real API, `./mvnw clean verify` green with
      no Ollama

## Phase 5 — Local LLM evaluation

- [x] Ollama configuration via env vars (`OLLAMA_BASE_URL`, `OLLAMA_EVALUATOR_MODEL`,
      `OLLAMA_TEMPERATURE`, `OLLAMA_MAX_CONCURRENCY`); app starts fine without Ollama
      (`LlmProperties` + `spring.ai.ollama.base-url` mapping; nothing contacts Ollama at startup)
- [x] Structured judge client abstraction + deterministic fake for tests (`JudgeClient` /
      `JudgeRequest` / `JudgeResponse` / `Judge*Exception`; `OllamaJudgeClient` via ChatClient +
      `BeanOutputConverter` schema-enforced structured output; `FakeJudgeClient` in test sources —
      default `./mvnw clean verify` never touches a real model)
- [x] ClaimExtractionEvaluator, GroundednessEvaluator (custom structured evaluator per the Phase 0
      API-surface findings — `FactCheckingEvaluator`/`RelevancyEvaluator` are NOT wrapped, see
      "Phase 5 notes" in `docs/architecture.md`), ReferenceCorrectnessEvaluator (reference material
      authoritative; retrieved context only via explicit `referenceCorrectnessUsesRetrievedContext`
      opt-in; not-applicable without reference answer or required facts), CompletenessEvaluator,
      AnswerRelevanceEvaluator, ContextRelevanceEvaluator (per-chunk decisions, ratios derived in
      Java, skipped without chunks), SemanticRefusalEvaluator (4-way classification, runs on every
      successful answer so INCORRECT_REFUSAL is observable), ContradictionEvaluator (explicit
      pairs across the four relations; never decides document authority)
- [x] Untrusted-content prompt delimiters + instruction-injection resistance in system prompt
      (`UntrustedContent`; asserted by unit tests against the recorded fake-judge requests)
- [x] Configurable context truncation, recorded in metadata (never silent) —
      `rag-evaluation.evaluator.maximum-context-characters` (default 30000, per-run overridable),
      truncation evidence + human-review flag
- [x] Graceful degradation: LLM evaluator results become `FAILED` (not chatbot failure) when
      Ollama is unavailable — exercised end to end by `LlmDegradationIntegrationTest` (closed
      port), incl. `evaluatorModel` health indicator details and unaffected test-case outcomes
- [x] `-Pollama-it` Maven profile for real-model tests (excluded from default `verify`;
      failsafe picks up `*OllamaIT` only under the profile)
- [x] Spec §14 additions landing with this phase: cross-evaluator disagreement review triggers
      (`CrossEvaluatorReviewTriggers`), evaluator-failure results always flagged for review,
      calibration dataset (22 labelled examples, all 11 §14 categories, under
      `src/test/resources/calibration/`) + `LabelAgreementService` (agreement rate, TP/FP/TN/FN,
      explicitly never called "model accuracy")

## Phase 6 — Review and reports

- [x] Human-review workflow (list pending, submit decision, override without deleting automated
      result) — `GET /api/v1/reviews/pending` (filters: run, severity, evaluator, metric status;
      most-severe first), `GET /api/v1/metric-results/{id}/review-context` (full question/answer/
      chunks/reference/evidence/explanation + existing reviews), `GET`/`POST
      /api/v1/metric-results/{id}/reviews` (CONFIRMED/REJECTED/UNCERTAIN/NEEDS_DOMAIN_EXPERT,
      optional pass/score override on the review row — automated result never mutated, proven by
      unit + integration test); a CONFIRMED review confirms findings supported by that result
- [x] `EvaluationFinding` generation, rule-based recommendation templates —
      `recommendation_template` table (migration 013) seeded with defaults for all 18 §6.8
      categories, editable via `GET/PUT /api/v1/recommendation-templates/{category}` (report
      reads current text at render time — edit → re-render reflects it, proven by IT); three
      additive LLM-evidence classification rules (groundedness-fail → UNSUPPORTED_CLAIM,
      context-relevance-fail → IRRELEVANT_CONTEXT, grounded-but-reference-wrong →
      INCORRECT_ANSWER naming OUTDATED_DOCUMENT, all requiring human confirmation)
- [x] Server-rendered Thymeleaf HTML report (all 20 sections per §22), no JS required —
      `GET /reports/runs/{id}`, `RunReportService` assembles everything from persisted data;
      finding details show question/expected/actual/reference facts/chunks/citations/failed
      metrics/observed problem/likely cause/severity/evidence/recommendation (template +
      specific)/acceptance criteria/human-review status; incorrect vs unsupported vs incomplete
      vs retrieval-failure vs unknown stay separate categories with concept explanations;
      IT asserts every section heading, both automated + human verdicts, and `<script`-free HTML
- [x] `docs/evaluation-methodology.md` — per-evaluator measures/limitations/interpretation,
      incl. the grounded-but-outdated caveat and the calibration "agreement, not accuracy"
      framing

## Phase 7 — Replay and comparison

- [x] Replay runs (evaluate stored executions via `sourceExecutionId`, no live target call) —
      `POST /api/v1/evaluation-runs/{id}/replay` creates a CREATED REPLAY run (started via the
      normal `/start`); the worker copies each captured source result instead of invoking the
      target (target config/secrets are not even resolved for a pure replay run);
      `sourceExecutionId` always references the *original* captured execution, so replaying a
      replay never builds a chain; optional body overrides name / evaluator keys / configuration
      (defaults inherit the source run); proven by `ReplayRunIntegrationTest` — metric results and
      findings produced with the WireMock request count unchanged
- [x] Baseline vs. candidate comparison service + `GET /api/v1/run-comparisons` —
      `comparison` module; matched by test-case externalId; added/removed/improved/regressed/
      unchanged/inconclusive (IMPROVED/REGRESSED only between two conclusive outcomes — an
      INCONCLUSIVE/NOT_EVALUATED side is never sold as a change); per-run stats reused from
      `RunSummaryService` (same denominators, null rates); metric rates with counts, high-severity
      finding counts, latency percentiles, evaluator versions; different datasets rejected unless
      `allowDatasetMismatch=true` (then matched by externalId with an explicit warning)
- [x] HTML comparison report — `GET /reports/comparisons?baselineRunId=..&candidateRunId=..`
      (`comparison-report.html`, no JavaScript, arrow-style "64% → 82%" summary, warnings box
      first)
- [x] Evaluator-version mismatch warnings — distinct version/prompt/model combinations per
      evaluator key from persisted metric results; every mismatch (including evaluator present in
      only one run) yields an explicit "not scientifically clean" warning in both API and report on comparisons

## Phase 8 — Hardening

- [x] Secret handling review, authorization header redaction in logs — full-codebase grep audit:
      only the two `ContentLoggingProperties`-guarded verbose paths touch content; secret values
      exist only in the transient per-call `TargetSystemConfiguration` (WireMock redaction test
      still guards the header path); dev-only opt-in `rag-evaluation.logging.verbose-content`
      added with a startup WARN when enabled
- [x] Observability: Actuator health/info/metrics/prometheus exposed; all §24 metrics via
      `common.observability.WorkbenchMetrics` (single owner of the tag policy — bounded tag
      values only, no IDs/content), review-backlog gauge from the review module; MDC-structured
      logs (`correlationId`/`runId`/`executionId`/`targetSystemId`/`evaluatorKey`) rendered by
      the console pattern; Spring AI prompt/completion observation export re-verified disabled
      against the resolved 2.0.0 `ChatObservationProperties` bytecode
- [x] Run deletion endpoint/service — `DELETE /api/v1/evaluation-runs/{id}`, 409 while
      QUEUED/RUNNING, cascades executions/metrics/reviews/findings/imported traces via the
      Phase 1 FK design (`HardeningIntegrationTest`)
- [x] Response-size limits enforced on target adapter (bounded streaming since Phase 2;
      re-verified — `RESPONSE_TOO_LARGE` WireMock test still locks it in)
- [x] Configurable raw-response/chunk-content storage (`rag-evaluation.storage.*`) applied at the
      `RunStateService` choke point (`StorageConfigurationIntegrationTest`); local-only mode
      documented (`local` profile binds 127.0.0.1, compose publishes on loopback)
- [x] Centralized `ProblemDetail` exception handling with correlation IDs — extended with
      handlers for malformed UUIDs, missing query parameters, and method-parameter validation
      (all were 500s before)
- [x] OpenAPI via springdoc **3.0.3** — Boot-4 line verified for real (2.8.x is Boot 3; 3.0.3
      parent POM builds against Boot 4.0.5; empirically proven on 4.1.0 by the suite + a live
      `/v3/api-docs` assertion). Cost: springdoc drags in Jackson 2 databind, which flipped
      Hibernate's JSONB mapper — pinned back with
      `hibernate.type.json_format_mapper=jackson3`. `docs/api.md` written anyway (endpoint
      overview + full cURL walkthrough + the compatibility notes)
- [x] ArchUnit tests (spec §28.5): controllers ↛ repositories (required extracting
      `FindingQueryService`/`MetricResultQueryService` — two Phase 4/6 controllers violated it),
      domain ↛ api, no controller returns a JPA entity (generic-aware)
- [x] Full `README.md` (§30), `.env.example`, `compose.yaml` (named volumes + health checks,
      loopback ports), `scripts/pull-models.sh` (env-driven model, clear failure when Ollama
      down, skips when already pulled), `scripts/start-local.sh`, `scripts/reset-local-data.sh`
      (confirmation-gated), `examples/demo-commands.sh` (full §31 workflow, jq behind an
      installed-check with python3 fallback); `docs/data-handling.md` rewritten for what the
      code actually does
- [x] Full end-to-end verification against the workflow in spec §31 (compose up → app under
      local,demo → `examples/demo-commands.sh` run for real — see Session 8 log)

## Notes

- Work one phase at a time. Do not modify passing code from earlier phases without stating why.
- After each phase: run relevant tests, fix failures, update this checklist, record significant
  decisions in `docs/architecture.md`.
