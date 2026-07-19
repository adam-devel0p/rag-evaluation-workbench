# RAG Evaluation Workbench

A local-first Spring Boot service that **independently evaluates external chatbots and RAG
systems**. It calls a target chatbot over HTTP, captures answers/citations/retrieved
chunks/latency/errors, runs deterministic and local-LLM evaluators, flags uncertain cases for
human review, classifies failure causes with evidence, and renders HTML reliability reports. It
can compare a baseline run against a candidate run and replay stored responses through updated
evaluators without re-calling the chatbot.

It is a consultant's internal workbench — no auth, no multi-tenancy, no SaaS features. The app
evaluates *another* system; it is not itself a chatbot.

**Status:** Phases 0–7 complete (persistence, targets, datasets, live runs, deterministic +
LLM evaluation, review, reports, replay, comparison). Phase 8 (hardening: compose file, run
deletion, OpenAPI, scripts) is not done yet — see `docs/implementation-plan.md`.

## Tech stack

Java 21 · Spring Boot 4.1.0 · Spring AI 2.0.0 (Ollama) · Maven Wrapper · PostgreSQL +
Liquibase · Spring Data JPA · Spring MVC + `RestClient` · Thymeleaf (server-rendered reports,
no JS) · Jackson 3 (`tools.jackson.*`) · Testcontainers · WireMock · Actuator + Micrometer.

## Prerequisites

- **Java 21** (`java -version`)
- **Docker** — required for the integration tests (Testcontainers Postgres) and for the
  easiest way to run the app locally
- **Ollama** — *optional*. The app builds, starts, and evaluates deterministically without it.
  Only the LLM judge evaluators need it; when Ollama is unreachable those metric results
  degrade to `FAILED` (never counted as a chatbot failure).

## Quick start (recommended): Testcontainers dev mode

There is no `compose.yaml` or bundled datasource config yet (planned for Phase 8). The
easiest way to run the app is Spring Boot's test-run, which starts throwaway Postgres and
Ollama containers automatically via `TestcontainersConfiguration`:

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=demo
```

The app starts on **http://localhost:8080** with Liquibase applying the schema. The `demo`
profile enables a built-in fake chatbot at `POST /demo-target/chat` (see the walkthrough
below). Note the containers are ephemeral — data is gone on restart.

## Alternative: run against your own Postgres

Start Postgres yourself, e.g.:

```bash
docker run -d --name rageval-postgres -p 5432:5432 \
  -e POSTGRES_DB=rageval -e POSTGRES_USER=rageval -e POSTGRES_PASSWORD=rageval \
  postgres:17
```

Then point the app at it (no datasource is configured in `application.properties`, so pass it
via environment or system properties):

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/rageval \
SPRING_DATASOURCE_USERNAME=rageval \
SPRING_DATASOURCE_PASSWORD=rageval \
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

This keeps data across restarts. Liquibase owns the schema (`ddl-auto=validate`) — never
create tables by hand; migrations live under `src/main/resources/db/changelog/`.

### Optional: Ollama for the LLM judge

```bash
docker run -d --name rageval-ollama -p 11434:11434 ollama/ollama
docker exec rageval-ollama ollama pull llama3.2:3b
```

| Env var | Default | Meaning |
|---|---|---|
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama endpoint |
| `OLLAMA_EVALUATOR_MODEL` | `llama3.2:3b` | Judge model |
| `OLLAMA_TEMPERATURE` | `0.0` | Judge temperature |
| `OLLAMA_MAX_CONCURRENCY` | `2` | Parallel judge calls |

All other tunables (worker pool, retry/backoff, evaluator thresholds, citation policy, …) are
in [application.properties](src/main/resources/application.properties) with comments, and most
evaluator settings can also be overridden **per run** via `evaluatorConfiguration` on the run
request.

## Build & test

```bash
./mvnw clean verify              # full build + tests; needs Docker, must pass with NO Ollama
./mvnw clean verify -Pollama-it  # additionally runs *OllamaIT real-model tests
                                 #   (needs Ollama running + OLLAMA_EVALUATOR_MODEL pulled)
./mvnw test -Dtest=DemoTargetIntegrationTest   # run a single test class
```

The default `verify` deliberately excludes real-model tests; LLM evaluators are tested against
a deterministic fake `JudgeClient`.

## Debugging

Attach a debugger on port 5005:

```bash
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=demo \
  -Dspring-boot.run.jvmArguments="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"
```

Useful endpoints and knobs:

- `GET /actuator/health` — liveness (includes DB); `GET /actuator/metrics`, `GET /actuator/prometheus`
- SQL logging: add `--logging.level.org.hibernate.SQL=DEBUG` (or set in a local profile)
- Privacy rule: the app never logs full prompts/answers/chunks — identifiers and sizes only.
  Don't "fix" that while debugging; verbose content logging is a dev-only opt-in.

## Manual end-to-end walkthrough (demo target)

With the app running under the `demo` profile, the built-in demo chatbot
(`POST /demo-target/chat`) implements 14 deliberate scenarios (spec §27): correct answers,
wrong version, missing citation, hallucination, refusal, timeout, HTTP 500, nondeterminism,
etc. The shipped dataset [examples/demo-dataset.json](examples/demo-dataset.json) exercises
all of them.

### 1. Import the demo dataset and mark it READY

```bash
DATASET_ID=$(curl -s -X POST localhost:8080/api/v1/datasets/import/json \
  -H 'Content-Type: application/json' \
  --data-binary @examples/demo-dataset.json | jq -r .id)

curl -s -X POST localhost:8080/api/v1/datasets/$DATASET_ID/mark-ready
```

### 2. Register the demo chatbot as a target system

```bash
TARGET_ID=$(curl -s -X POST localhost:8080/api/v1/targets \
  -H 'Content-Type: application/json' -d '{
  "name": "Demo Target",
  "adapterType": "STANDARD_HTTP",
  "endpointUrl": "http://localhost:8080/demo-target/chat",
  "httpMethod": "POST",
  "requestTemplate": {
    "question": "{{question}}",
    "testCaseId": "{{testCaseId}}",
    "conversationId": "{{conversationId}}"
  },
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
  "responseMapping": {
    "inputTokensJsonPointer": "/usage/inputTokens",
    "outputTokensJsonPointer": "/usage/outputTokens"
  }
}' | jq -r .id)

# sanity check the wiring without creating a run:
curl -s -X POST localhost:8080/api/v1/targets/$TARGET_ID/test-connection \
  -H 'Content-Type: application/json' -d '{"question": "ping"}' | jq
```

For a **real** target, set `authenticationType` (e.g. `API_KEY_HEADER` / `BEARER`) plus
`secretEnvironmentVariable` — the *name* of the env var holding the key. Secrets are never
persisted or logged.

### 3. Create and start an evaluation run

```bash
RUN_ID=$(curl -s -X POST localhost:8080/api/v1/evaluation-runs \
  -H 'Content-Type: application/json' -d "{
  \"targetSystemId\": \"$TARGET_ID\",
  \"datasetId\": \"$DATASET_ID\",
  \"name\": \"Demo run\",
  \"repetitionsPerTestCase\": 1
}" | jq -r .id)

curl -s -X POST localhost:8080/api/v1/evaluation-runs/$RUN_ID/start

# poll until COMPLETED / PARTIALLY_COMPLETED (DEMO-013 answers HTTP 500 on purpose,
# so the demo run legitimately ends PARTIALLY_COMPLETED):
curl -s localhost:8080/api/v1/evaluation-runs/$RUN_ID | jq .status
```

`POST .../cancel` and `POST .../resume` exist for long runs; interrupted runs (app restart)
are marked `INTERRUPTED` and can be resumed.

### 4. Inspect results

```bash
curl -s localhost:8080/api/v1/evaluation-runs/$RUN_ID/executions | jq
curl -s localhost:8080/api/v1/evaluation-runs/$RUN_ID/metrics | jq
curl -s localhost:8080/api/v1/evaluation-runs/$RUN_ID/summary | jq
curl -s localhost:8080/api/v1/evaluation-runs/$RUN_ID/findings | jq
```

Open the **HTML report** in a browser: `http://localhost:8080/reports/runs/<RUN_ID>`

### 5. Human review of uncertain results

```bash
curl -s localhost:8080/api/v1/reviews/pending | jq          # what needs a human
curl -s localhost:8080/api/v1/metric-results/<METRIC_RESULT_ID>/review-context | jq
curl -s -X POST localhost:8080/api/v1/metric-results/<METRIC_RESULT_ID>/reviews \
  -H 'Content-Type: application/json' \
  -d '{"decision": "PASS", "reviewer": "mario", "justification": "Answer is factually correct."}' | jq
```

(Field shapes: see [SubmitReviewRequest.java](src/main/java/com/rageval/workbench/review/api/dto/SubmitReviewRequest.java).)

### 6. Replay — re-evaluate stored responses without calling the chatbot

Useful after changing evaluator config or when Ollama was down during the original run:

```bash
REPLAY_ID=$(curl -s -X POST localhost:8080/api/v1/evaluation-runs/$RUN_ID/replay \
  -H 'Content-Type: application/json' \
  -d '{"name": "Replay with new settings"}' | jq -r .id)
curl -s -X POST localhost:8080/api/v1/evaluation-runs/$REPLAY_ID/start
```

Replay runs make **zero** HTTP calls to the target (proven by WireMock request counting in
`ReplayRunIntegrationTest`).

### 7. Baseline vs. candidate comparison

Simulate a "changed" chatbot by redirecting demo scenarios (e.g. make the wrong-answer
scenario DEMO-002 behave like the correct one), run again, then compare:

```bash
curl -s -X PUT localhost:8080/demo-target/scenario-redirects \
  -H 'Content-Type: application/json' -d '{"DEMO-002": "DEMO-001"}'
# ... create + start a second run (candidate) exactly like step 3 ...
curl -s -X DELETE localhost:8080/demo-target/scenario-redirects   # restore behavior

curl -s "localhost:8080/api/v1/run-comparisons?baselineRunId=$RUN_ID&candidateRunId=$CANDIDATE_RUN_ID" | jq
```

HTML comparison report:
`http://localhost:8080/reports/comparisons?baselineRunId=..&candidateRunId=..`
It includes explicit warnings when the two runs used different evaluator versions/prompts/models.

## API overview

| Area | Endpoints |
|---|---|
| Targets | `POST/GET /api/v1/targets`, `GET/PUT/DELETE /{id}`, `POST /{id}/test-connection` |
| Datasets | `POST/GET /api/v1/datasets`, `GET /{id}`, `POST /{id}/mark-ready`, `GET/POST /{id}/test-cases`, `PUT /{id}/test-cases/{tcId}`, `POST /import/json`, `POST /import/csv` |
| Runs | `POST/GET /api/v1/evaluation-runs`, `GET /{id}`, `POST /{id}/start\|cancel\|resume\|replay`, `GET /{id}/executions\|metrics\|summary\|findings` |
| Imported traces | `POST /api/v1/imported-traces`, `POST /api/v1/imported-traces/csv` (evaluate captured logs offline) |
| Review | `GET /api/v1/reviews/pending`, `GET /api/v1/metric-results/{id}/review-context\|reviews`, `POST .../reviews` |
| Recommendations | `GET /api/v1/recommendation-templates`, `PUT /{category}` |
| Comparison | `GET /api/v1/run-comparisons?baselineRunId=&candidateRunId=` |
| Reports (HTML) | `GET /reports/runs/{runId}`, `GET /reports/comparisons?baselineRunId=&candidateRunId=` |
| Demo (profile `demo` only) | `POST /demo-target/chat`, `PUT/DELETE /demo-target/scenario-redirects` |

Request/response shapes live in the `api/dto` package of each module; errors are RFC 9457
`ProblemDetail` responses.

## Key concepts

- **Metric status vs. chatbot failure.** A metric result distinguishes *the chatbot failed the
  metric* from *the evaluator itself failed* (`FAILED`, e.g. Ollama down) from *not
  applicable*. Aggregation denominators exclude evaluator errors — never conflate the two.
- **No score without evidence.** Every score carries its evidence; conflicting results are
  never averaged into one number.
- **Datasets** are `DRAFT` (editable) until `mark-ready`; test cases carry required facts,
  forbidden claims, expected document IDs, refusal expectations, and severity.
- **Runs** execute on a bounded in-app worker pool (`rag-evaluation.execution.*`); per-target
  concurrency and timeouts live on the target itself.

## Project layout

```
src/main/java/com/rageval/workbench/
├── common          shared api/error/json/persistence plumbing
├── target          target-system CRUD, HTTP adapter, connection test, secrets
├── dataset         datasets + test cases, JSON/CSV import
├── run             run lifecycle, worker pool, replay, imported traces
├── evaluation      evaluator strategies (deterministic + llm), aggregation
├── classification  failure-cause rules, findings, recommendation templates
├── review          human-review workflow
├── report          Thymeleaf HTML run report
├── comparison      baseline-vs-candidate comparison + HTML report
└── demo            demo-profile fake chatbot (spec §27 scenarios)
```

## Further documentation

- [docs/spec.md](docs/spec.md) — full functional specification
- [docs/implementation-plan.md](docs/implementation-plan.md) — phase checklist + current status
- [docs/architecture.md](docs/architecture.md) — decisions and session log
- [docs/evaluation-methodology.md](docs/evaluation-methodology.md) — what each evaluator
  measures, its limitations, and how to interpret results
- [docs/data-handling.md](docs/data-handling.md) — privacy and data-retention rules
- [CLAUDE.md](CLAUDE.md) — non-negotiable constraints for AI-assisted development
