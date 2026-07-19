# RAG Evaluation Workbench

A local-first Spring Boot service that **independently evaluates external chatbots and RAG
systems**. It calls a target chatbot over HTTP, captures answers/citations/retrieved
chunks/latency/errors, runs deterministic and local-LLM evaluators, flags uncertain cases for
human review, classifies failure causes with evidence, and renders HTML reliability reports. It
can compare a baseline run against a candidate run and replay stored responses through updated
evaluators without re-calling the chatbot.

**Status: MVP complete** (phases 0–8; see `docs/implementation-plan.md`).

## What it does / what it does NOT do

**Does:** target-system configuration with secret-by-env-var-name auth · dataset import
(JSON/CSV) · live evaluation runs with repetitions, retries, cancellation/resume and crash
recovery · imported-trace evaluation (offline logs) · nine deterministic evaluators · eight
local-LLM judge evaluators (Ollama) · evidence-based failure classification with an honest
UNKNOWN · human-review workflow layered over automated results · server-rendered HTML run and
comparison reports (no JavaScript) · replay · baseline-vs-candidate comparison with
evaluator-version warnings · run deletion · Prometheus metrics.

**Does NOT:** index or embed client documents, generate RAG answers, or host a chatbot · no
customer accounts, auth, or multi-tenancy (local-only tool) · no external/paid LLM APIs — judging
is local Ollama only · no production monitoring, PDF export, automatic remediation, or compliance
certification. It is a consultant's internal workbench, not a SaaS platform.

## Architecture overview

One Spring Boot application (Java 21, Spring Boot 4.1.0, Spring AI 2.0.0, PostgreSQL +
Liquibase, Jackson 3), organized in modules with one-directional dependencies:

```
report ─▶ comparison ─▶ review ─▶ classification ─▶ evaluation ─▶ run ─▶ target
                                                             (all) ─▶ dataset, common, configuration
```

- **target** — target CRUD, the `RestClient` HTTP adapter (request templating via Jackson-tree
  substitution, JSON-pointer response extraction, exact spec retry policy, bounded response
  reading), secret resolution from env vars.
- **run** — run state machine persisted on every step, bounded worker pool, startup recovery,
  imported traces, replay.
- **evaluation** — the `EvaluationStrategy` abstraction; deterministic evaluators + LLM judges
  over a structured-output `JudgeClient` (schema-enforced, untrusted-content hardened);
  aggregation with evaluator-error-free denominators.
- **classification / review / report / comparison** — §16 failure rules with evidence, the
  human-review queue, the 20-section HTML report, baseline-vs-candidate comparison.

Key invariants: chatbot failure ≠ evaluator failure, no score without evidence, Liquibase owns
the schema, controllers hold no business logic (ArchUnit-enforced), logs/metrics carry no client
content.

## Requirements

- **Java 21** (`java -version`)
- **Docker** (integration tests via Testcontainers; local Postgres/Ollama via compose)
- **Ollama** — *optional.* The app builds, starts, and evaluates deterministically without it;
  LLM metric results degrade to `FAILED` (never a chatbot failure) when it is unreachable.

## Quick start

```bash
# 1. Start PostgreSQL and Ollama (named volumes, health checks, bound to 127.0.0.1)
docker compose up -d postgres ollama

# 2. Pull the evaluator model (optional — deterministic evaluation works without it).
#    Reads OLLAMA_EVALUATOR_MODEL, defaults to llama3.2:3b, skips if already pulled:
./scripts/pull-models.sh

# 3. Run the application (profiles: local = compose datasource + loopback bind, demo = built-in fake chatbot)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local,demo
```

Or all three in one step: `./scripts/start-local.sh`. The app listens on
**http://127.0.0.1:8080**; Liquibase creates/validates the schema on startup. Swagger UI:
`http://127.0.0.1:8080/swagger-ui`, OpenAPI JSON at `/v3/api-docs`.

To wipe all locally stored evaluation data: `./scripts/reset-local-data.sh` (asks for
confirmation; keeps downloaded Ollama models).

### Ollama configuration

| Env var | Default | Meaning |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `OLLAMA_EVALUATOR_MODEL` | `llama3.2:3b` | Judge model (must be pulled) |
| `OLLAMA_TEMPERATURE` | `0.0` | Judge temperature |
| `OLLAMA_MAX_CONCURRENCY` | `2` | Parallel judge calls |

`.env.example` documents every variable; copy it to `.env` (git-ignored) if you use one. All
other tunables live commented in
[application.properties](src/main/resources/application.properties); most evaluator settings can
also be overridden per run via `evaluatorConfiguration`.

## Build & test

```bash
./mvnw clean verify              # full build + tests; needs Docker, passes with NO Ollama running
./mvnw clean verify -Pollama-it  # additionally runs the *OllamaIT real-model tests
                                 #   (needs Ollama running + OLLAMA_EVALUATOR_MODEL pulled)
./mvnw test -Dtest=HardeningIntegrationTest   # a single test class
```

The default `verify` never touches a real model — LLM evaluators are tested against a
deterministic fake `JudgeClient`, and PostgreSQL integration tests run through Testcontainers.

## Complete evaluation workflow (demo profile)

The `demo` profile ships a fake chatbot (`POST /demo-target/chat`) with 14 deliberate scenarios
(correct answer, wrong version, hallucination, missing citation, refusal failures, timeout,
HTTP 500, nondeterminism, …) and a matching dataset
[examples/demo-dataset.json](examples/demo-dataset.json).

**The whole workflow below, scripted and safe to re-run: [`examples/demo-commands.sh`](examples/demo-commands.sh)**
(uses `jq` only if installed; falls back to python3 for ID extraction).

With the app running (`local,demo` profiles), step by step — set `BASE=http://127.0.0.1:8080`:

### 1. Import a dataset and mark it READY

```bash
DATASET_ID=$(curl -s -X POST $BASE/api/v1/datasets/import/json \
  -H 'Content-Type: application/json' \
  --data-binary @examples/demo-dataset.json | jq -r .id)
curl -s -X POST $BASE/api/v1/datasets/$DATASET_ID/mark-ready
```

Import is transactional and all-or-nothing with row-level error reporting. CSV import (basic
fields) exists at `POST /api/v1/datasets/import/csv`.

### 2. Configure the external chatbot as a target

```bash
TARGET_ID=$(curl -s -X POST $BASE/api/v1/targets -H 'Content-Type: application/json' -d '{
  "name": "Demo chatbot",
  "adapterType": "STANDARD_HTTP",
  "endpointUrl": "http://127.0.0.1:8080/demo-target/chat",
  "httpMethod": "POST",
  "requestTemplate": {"question": "{{question}}", "testCaseId": "{{testCaseId}}"},
  "answerJsonPointer": "/answer",
  "citationsJsonPointer": "/citations",
  "retrievedChunksJsonPointer": "/retrievedChunks",
  "modelJsonPointer": "/model",
  "enabled": true,
  "timeoutMilliseconds": 10000,
  "connectTimeoutMilliseconds": 2000,
  "followRedirects": false,
  "maximumConcurrency": 2,
  "authenticationType": "NONE",
  "responseMapping": {"inputTokensJsonPointer": "/usage/inputTokens",
                       "outputTokensJsonPointer": "/usage/outputTokens"}
}' | jq -r .id)
```

For a **real** chatbot set `authenticationType` to `BEARER_TOKEN` or `API_KEY_HEADER` plus
`secretEnvironmentVariable` — the **name** of the env var holding the credential. Only the name
is ever persisted; the value is read from your environment at call time and never logged.

### 3. Test the connection

```bash
curl -s -X POST $BASE/api/v1/targets/$TARGET_ID/test-connection \
  -H 'Content-Type: application/json' \
  -d '{"sampleQuestion": "Which Java version does the payments service require?"}' | jq
```

Makes one real call and reports which JSON pointers resolved (answer, citations, chunks, model).

### 4. Create an evaluation run

```bash
RUN_ID=$(curl -s -X POST $BASE/api/v1/evaluation-runs -H 'Content-Type: application/json' -d '{
  "targetSystemId": "'$TARGET_ID'",
  "datasetId": "'$DATASET_ID'",
  "name": "Demo baseline run",
  "repetitionsPerTestCase": 2
}' | jq -r .id)
```

### 5. Start it

```bash
curl -s -X POST $BASE/api/v1/evaluation-runs/$RUN_ID/start
```

`POST .../cancel` and `.../resume` exist for long runs; a run interrupted by an app restart is
marked `INTERRUPTED` on startup and can be resumed (completed executions are never re-run).

### 6. Poll run status

```bash
curl -s $BASE/api/v1/evaluation-runs/$RUN_ID | jq .status
```

The demo run legitimately ends **PARTIALLY_COMPLETED** — one scenario answers HTTP 500 on
purpose. Inspect details:

```bash
curl -s $BASE/api/v1/evaluation-runs/$RUN_ID/summary  | jq   # aggregated §18 summary
curl -s $BASE/api/v1/evaluation-runs/$RUN_ID/metrics?size=50  | jq   # per-evaluator results
curl -s $BASE/api/v1/evaluation-runs/$RUN_ID/findings?size=50 | jq   # classified findings
```

### 7. Open the HTML report

```
http://127.0.0.1:8080/reports/runs/<RUN_ID>
```

Server-rendered, no JavaScript, all 20 spec sections — including what could **not** be evaluated
and why (e.g. LLM metrics FAILED because Ollama was down — shown as tool failures, never chatbot
failures).

### 8. Submit a human review

```bash
curl -s "$BASE/api/v1/reviews/pending?runId=$RUN_ID&size=5" | jq          # triage queue
MR_ID=<metricResultId from the queue>
curl -s $BASE/api/v1/metric-results/$MR_ID/review-context | jq            # full context
curl -s -X POST $BASE/api/v1/metric-results/$MR_ID/reviews \
  -H 'Content-Type: application/json' \
  -d '{"decision": "CONFIRMED", "reviewerName": "Demo Reviewer",
       "comment": "Verified against the demo documentation."}' | jq
```

Decisions: `CONFIRMED` / `REJECTED` / `UNCERTAIN` / `NEEDS_DOMAIN_EXPERT`, optional
pass/score override — recorded **next to** the automated result, which is never mutated.

### 9. Replay — re-evaluate stored responses without calling the chatbot

```bash
REPLAY_ID=$(curl -s -X POST $BASE/api/v1/evaluation-runs/$RUN_ID/replay \
  -H 'Content-Type: application/json' -d '{"name": "Demo replay run"}' | jq -r .id)
curl -s -X POST $BASE/api/v1/evaluation-runs/$REPLAY_ID/start
```

Replay copies each captured response and re-runs the current evaluator pipeline — **zero** HTTP
calls to the target (proven by WireMock request counting in `ReplayRunIntegrationTest`). Useful
after changing evaluator config, or to fill in LLM metrics once Ollama is back up.

### 10. Compare baseline and candidate runs

```bash
curl -s "$BASE/api/v1/run-comparisons?baselineRunId=$RUN_ID&candidateRunId=$REPLAY_ID" | jq
```

HTML version: `http://127.0.0.1:8080/reports/comparisons?baselineRunId=..&candidateRunId=..`.
Improved/regressed verdicts require conclusive outcomes on both sides; evaluator
version/prompt/model mismatches produce explicit "not scientifically clean" warnings above every
number. To simulate a changed chatbot in the demo:
`curl -s -X PUT $BASE/demo-target/scenario-redirects -H 'Content-Type: application/json' -d '{"DEMO-002": "DEMO-001"}'`,
then run a second live run as the candidate.

### Deleting a run

```bash
curl -s -X DELETE $BASE/api/v1/evaluation-runs/$RUN_ID   # cascades all dependent data
```

## Observability

- `GET /actuator/health` — includes the `evaluatorModel` component: **DOWN with a reason when
  Ollama is unreachable** (the aggregate then reports 503 — deterministic evaluation still
  works; the status is honest, not fatal).
- `GET /actuator/info`, `/actuator/metrics`, `/actuator/prometheus` — application metrics under
  `rageval.*` (target invocations/failures/latency, runs, executions, evaluator
  executions/failures/duration, judge calls/failures, review backlog), tagged only with bounded
  values — never client content or IDs.
- Logs carry `[cid=… run=… exec=… target=… eval=…]` MDC context and no client content.

## Data handling and its limitations

Everything stays on your machine: local Postgres, local Ollama, no external APIs, no telemetry.
Logs and metrics carry identifiers and sizes, never content. Secrets are env-var **names** in
the database, values only at call time. Raw-response and chunk-content storage are configurable
(`rag-evaluation.storage.*`). Deletion is per run (cascading) or wholesale
(`scripts/reset-local-data.sh`); there is no automatic retention/expiry. The API has **no
authentication** — the local profile binds to 127.0.0.1, and exposing it beyond loopback is on
you. Full detail + sample client-facing limitation language (explicitly not a compliance
claim): [docs/data-handling.md](docs/data-handling.md).

## Known MVP limitations

- **Black-box evidence only.** Root causes that need target internals (outdated vs. missing
  document, retriever vs. index fault) are reported as findings needing human confirmation, or
  honestly as UNKNOWN.
- **LLM judgments are calibrated, not certified.** Judge agreement is measured against a small
  labelled calibration set (`-Pollama-it`) and reported as agreement, never as "accuracy";
  low-confidence and disagreeing results are routed to human review.
- **No auth/multi-user.** One consultant, one machine. No concurrent-editing protections beyond
  optimistic locking.
- **In-memory run queue.** Queued runs don't survive a restart — startup recovery marks them
  INTERRUPTED for explicit resume.
- **Per-target concurrency changes** apply from the next application start (semaphores sized on
  first use).
- **SEMANTIC fact-match mode** falls back to case-insensitive matching plus LLM
  reference-correctness judging; there is no dedicated embedding-based matcher.
- **Ollama health = 503 aggregate** when the model is down (see Observability) — by design, but
  worth knowing before wiring uptime probes to `/actuator/health`.

## Project layout

```
src/main/java/com/rageval/workbench/
├── common          shared api/error/json/csv/observability plumbing
├── configuration   typed @ConfigurationProperties
├── target          target CRUD, HTTP adapter, connection test, secret resolution
├── dataset         datasets + test cases, JSON/CSV import
├── run             run state machine, worker pool, recovery, imported traces, replay
├── evaluation      evaluator strategies (deterministic + llm), judge client, aggregation
├── classification  failure-cause rules, findings, recommendation templates
├── review          human-review queue and decisions
├── report          Thymeleaf HTML run report
├── comparison      baseline-vs-candidate comparison + HTML report
└── demo            demo-profile fake chatbot (spec §27 scenarios)
```

## Further documentation

- [docs/spec.md](docs/spec.md) — full functional specification
- [docs/api.md](docs/api.md) — endpoint overview + complete cURL walkthrough + springdoc notes
- [docs/implementation-plan.md](docs/implementation-plan.md) — phase checklist + current status
- [docs/architecture.md](docs/architecture.md) — decisions and session log
- [docs/evaluation-methodology.md](docs/evaluation-methodology.md) — what each evaluator
  measures, its limitations, how to interpret results
- [docs/data-handling.md](docs/data-handling.md) — privacy, storage, deletion, local-only mode
- [CLAUDE.md](CLAUDE.md) — non-negotiable constraints for AI-assisted development
