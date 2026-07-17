# Implementation Plan — RAG Evaluation Workbench

Living checklist. Update after every phase (check items off, add notes). See `docs/spec.md` for
full requirements and `CLAUDE.md` for non-negotiable constraints.

## Current status

**Last completed phase:** 1 — Persistence foundation
**Next phase to run:** 2 — Dataset and target configuration
**Build state:** `./mvnw clean verify` green (5/5 tests), no local Ollama required (Testcontainers
starts its own Ollama and Postgres containers).

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

- [ ] DTOs + explicit validation (no JPA entities across the API boundary)
- [ ] Dataset JSON import (transactional, all-or-nothing)
- [ ] Dataset CSV import
- [ ] `EvaluationDataset` status transitions (DRAFT → READY → ARCHIVED), no silent edits once READY
- [ ] Target CRUD, secret-name-only persistence, URL scheme validation (http/https only)
- [ ] Target connection-test endpoint
- [ ] `TargetSystemAdapter` abstraction + Standard HTTP adapter (`RestClient`, Jackson-tree
      placeholder substitution, JSON Pointer response extraction)
- [ ] Retry policy (retryable vs non-retryable per spec §8.1)
- [ ] Imported-trace adapter (JSON + CSV)
- [ ] WireMock adapter tests (success, timeouts, 401/429/500, malformed JSON, oversized response,
      secret header redaction)

## Phase 3 — Run orchestration

- [ ] Run state machine (CREATED → QUEUED → RUNNING → PARTIALLY_COMPLETED/COMPLETED/FAILED/
      CANCELLED/INTERRUPTED)
- [ ] Bounded task executor (configurable worker count, queue capacity, per-target concurrency;
      no unbounded `CompletableFuture`, no common ForkJoinPool)
- [ ] Live run execution creates pending `TestExecution` rows before invoking the target
- [ ] Cancellation support
- [ ] Startup recovery: mark stale RUNNING runs INTERRUPTED; resume endpoint for incomplete
      executions only
- [ ] Demo target under `demo` profile with all required scenarios (§27) + example dataset

## Phase 4 — Deterministic evaluation

- [ ] `EvaluationStrategy` domain interface (key/version/applicability/evaluate), independent of
      Spring AI
- [ ] TransportEvaluator, LatencyEvaluator (correct percentile impl + tests), RequiredFactsEvaluator,
      ForbiddenClaimsEvaluator, RefusalEvaluator (phrase-based), CitationPresenceEvaluator,
      ExpectedDocumentEvaluator (Hit@K, MRR), CitationRetrievalConsistencyEvaluator,
      AnswerabilityEvaluator
- [ ] Aggregation service (denominators exclude evaluator errors; run-level stats per §18)
- [ ] Evidence-based failure classification rules (§16)
- [ ] Unit + integration tests
- [ ] Checkpoint: app produces a useful report with zero Ollama dependency

## Phase 5 — Local LLM evaluation

- [ ] Ollama configuration via env vars (`OLLAMA_BASE_URL`, `OLLAMA_EVALUATOR_MODEL`,
      `OLLAMA_TEMPERATURE`, `OLLAMA_MAX_CONCURRENCY`); app starts fine without Ollama
- [ ] Structured judge client abstraction + deterministic fake for tests
- [ ] ClaimExtractionEvaluator, GroundednessEvaluator (evaluate wrapping vs. custom structured
      evaluator per the API-surface findings in `docs/architecture.md`), ReferenceCorrectnessEvaluator,
      CompletenessEvaluator, AnswerRelevanceEvaluator (evaluate wrapping `RelevancyEvaluator`),
      ContextRelevanceEvaluator, SemanticRefusalEvaluator, ContradictionEvaluator
- [ ] Untrusted-content prompt delimiters + instruction-injection resistance in system prompt
- [ ] Configurable context truncation, recorded in metadata (never silent)
- [ ] Graceful degradation: LLM evaluator results become `FAILED` (not chatbot failure) when
      Ollama is unavailable
- [ ] `-Pollama-it` Maven profile for real-model tests (excluded from default `verify`)

## Phase 6 — Review and reports

- [ ] Human-review workflow (list pending, submit decision, override without deleting automated
      result)
- [ ] `EvaluationFinding` generation, rule-based recommendation templates
- [ ] Server-rendered Thymeleaf HTML report (all 20 sections per §22), no JS required
- [ ] `docs/evaluation-methodology.md`

## Phase 7 — Replay and comparison

- [ ] Replay runs (evaluate stored executions via `sourceExecutionId`, no live target call)
- [ ] Baseline vs. candidate comparison service + `GET /api/v1/run-comparisons`
- [ ] HTML comparison report
- [ ] Evaluator-version mismatch warnings on comparisons

## Phase 8 — Hardening

- [ ] Secret handling review, authorization header redaction in logs
- [ ] Observability: Actuator health/info/metrics/prometheus, custom Micrometer metrics (§24),
      no high-cardinality tags
- [ ] Run deletion endpoint/service (cascades dependent data)
- [ ] Response-size limits enforced on target adapter
- [ ] Centralized `ProblemDetail` exception handling with correlation IDs
- [ ] OpenAPI/springdoc if a Spring Boot 4.1-compatible version exists; otherwise `docs/api.md`
- [ ] Full `README.md`, `.env.example`, `compose.yaml`, `scripts/*.sh`, `examples/demo-commands.sh`
- [ ] Full end-to-end verification against the workflow in spec §31

## Notes

- Work one phase at a time. Do not modify passing code from earlier phases without stating why.
- After each phase: run relevant tests, fix failures, update this checklist, record significant
  decisions in `docs/architecture.md`.
