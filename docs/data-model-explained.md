# Data Model Explained: How Tables Get Populated

This document walks through the database schema of the RAG Evaluation Workbench end to end:
which table gets a row in which scenario, how tables relate to each other, and how the HTML
report is assembled from them. It's a companion to `docs/spec.md` — read that for the "what
and why," this for "where does the data actually land."

All file paths are relative to the repo root. Line numbers are approximate and may drift as
the code evolves; use them as a starting point, not gospel.

## 1. The tables, in creation order

Schema lives entirely under Liquibase (`spring.jpa.hibernate.ddl-auto: validate` — Hibernate never
creates tables, it only checks entities match). Changelogs: `src/main/resources/db/changelog/changes/001..013-*.yaml`.

| # | Table | Created for |
|---|---|---|
| 001 | `target_system` | The external chatbot/RAG system being evaluated, and how to call it |
| 002 | `evaluation_dataset` | A named, versioned set of test cases |
| 003 | `evaluation_test_case` | One question + expected behavior, belonging to a dataset |
| 004 | `evaluation_run` | One evaluation pass: a dataset run against a target |
| 005 | `test_execution` | One actual invocation (question × repetition) with its captured answer or failure |
| 006 | `evaluation_metric_result` | One evaluator's verdict on one execution |
| 007 | `human_review` | A human's decision on a flagged metric result |
| 008 | `evaluation_finding` | A synthesized root-cause classification of a failed execution |
| 009 | *(no new table)* | Adds connect-timeout/redirects/response-mapping columns to `target_system` |
| 010 | *(no new table)* | Adds `attempt_count` to `test_execution` |
| 011 | `imported_trace` | Raw data for a test execution that was imported rather than live-called |
| 012 | *(no new table)* | Adds `confidence`, `requires_human_confirmation`, `supporting_metric_result_ids` to `evaluation_finding` |
| 013 | `recommendation_template` | Default, editable recommendation text per failure category |

Nothing here uses JPA `@OneToMany`/`@ManyToOne`/`@OneToOne` — every relationship is a plain `UUID`
foreign-key column plus explicit repository queries (confirmed by grep: zero relationship
annotations anywhere in the codebase). This is deliberate: aggregates stay independent, and joins
happen explicitly in application-service code, never through lazy entity graphs.

## 2. The relationship graph

```
target_system                    evaluation_dataset
      │  (target_system_id)             │ (dataset_id)
      │                                  ├──────────────► evaluation_test_case
      │                                  │                        │ (test_case_id)
      └──────────────┬───────────────────┘                        │
                      ▼                                            │
                evaluation_run                                     │
                      │ (evaluation_run_id, CASCADE)                │
                      ▼                                            │
                test_execution ◄───────────────────────────────────┘
                (self-FK: source_execution_id → test_execution.id, for replay)
                      │
          ┌───────────┼────────────────────┐
          │ (test_execution_id, CASCADE)    │ (test_execution_id, CASCADE, unique)
          ▼                                 ▼
  evaluation_metric_result             imported_trace
          │ (metric_result_id, CASCADE)
          ▼
      human_review

evaluation_run ──(evaluation_run_id, CASCADE)──► evaluation_finding ──(test_execution_id, CASCADE)──► test_execution
      finding.supporting_metric_result_ids: JSON array of evaluation_metric_result.id (app-level link, not an FK)

recommendation_template: standalone, one row per FailureCategory, joined in only at report-render time
```

Cascades of note: deleting an `evaluation_run` cascades to its `test_execution` and
`evaluation_finding` rows, which cascade further down to `evaluation_metric_result`,
`human_review`, and `imported_trace`. Deleting a `target_system` or `evaluation_dataset` does
**not** cascade to `evaluation_run` — that's guarded in application code instead (a run keeps its
target/dataset alive).

## 3. Walking through the scenarios

### 3.1 Setting up: target, dataset, test cases

- `TargetSystemService.create` (`target/application/TargetSystemService.java:36`) validates the
  adapter config (e.g. `STANDARD_HTTP` requires an endpoint URL + HTTP method + answer JSON
  pointer) and inserts one `target_system` row. Note: there's no separate adapter-config table —
  migration 009's name is a bit misleading; it just adds columns to `target_system` itself.
  Secrets are never stored: only `secret_environment_variable` (the *name* of the env var) and
  `api_key_header_name` are persisted; the actual secret value is resolved at call time.
- `DatasetService.create` (`dataset/application/DatasetService.java:38`) inserts an
  `evaluation_dataset` row with `status = DRAFT`.
- `DatasetService.addTestCase` inserts `evaluation_test_case` rows, each FK'd to the dataset
  (`dataset_id`, `ON DELETE CASCADE`). While the dataset is `DRAFT` you can keep adding/editing
  cases; once `markReady()` transitions it to `READY`, the dataset becomes immutable for further
  test-case writes — this is what makes a dataset a stable, versioned artifact you can point
  multiple runs at.

### 3.2 Starting a run

`RunService.create` (`run/application/RunService.java:83`) checks the target is `enabled` and
(for a live run) `STANDARD_HTTP`, checks the dataset is `READY` and non-empty, validates the
requested evaluator keys, and inserts one `evaluation_run` row with `status = CREATED`. Calling
`start()` transitions it to `QUEUED` and hands it to a worker thread pool.

`evaluation_run.status` is a state machine (`EvaluationRun.transitionTo`,
`run/domain/EvaluationRun.java:117`) with an explicit allowed-transitions map — illegal jumps
(e.g. `COMPLETED → RUNNING`) throw rather than silently corrupting the column.

`RunStateService.createPendingExecutionsIfAbsent` (`run/application/RunStateService.java:130`)
then inserts one `test_execution` row per (test case × repetition) — so a run of 50 test cases
with `repetitions_per_test_case = 3` creates 150 `test_execution` rows, all `status = PENDING`,
before any target is called.

### 3.3 Live execution — this is where "test case fails" actually gets recorded

`RunWorker.invokeOne` (`run/application/RunWorker.java:148`) marks the execution `RUNNING`, calls
the target adapter, and then does exactly one of two things to that same `test_execution` row:

**Success** — `TestExecution.recordSuccess(...)` (`run/domain/TestExecution.java:132`) sets
`status = SUCCEEDED` and fills in `answer`, `citations`, `retrieved_chunks`, `raw_response`,
`target_model`, `latency_milliseconds`, `input_tokens`/`output_tokens`, `http_status`,
`attempt_count`.

**Failure** — `TestExecution.recordFailure(...)` (`run/domain/TestExecution.java:162`) sets
`status` to one of `TIMED_OUT` / `TARGET_ERROR` / `INVALID_RESPONSE`, and fills in `error_type`,
`error_message`, `http_status`, `attempt_count`. No answer, no citations — there's nothing to
grade.

So **this is the direct answer to "where do I find a failed test case": `test_execution.status`
plus the `error_type`/`error_message`/`http_status` columns on that same row.** No separate
"failure" table — a target-call failure is just a status + error columns on `test_execution`.

The classification into which failure bucket:
- `StandardHttpTargetSystemAdapter.interpret()` (`target/infrastructure/StandardHttpTargetSystemAdapter.java:196`)
  maps HTTP outcomes: retryable statuses (429/502/503/504, connection resets/timeouts) get retried
  up to `maximumAttempts` with backoff; terminal statuses (400/401/403/404/500), invalid JSON, or a
  missing/blank answer are failed immediately.
- `RunWorker.invokeOne` also wraps the entire adapter call in a catch-all: if an adapter throws
  instead of returning a result (a bug in the adapter itself), it's converted to a synthetic
  `INVALID_RESPONSE` with `error_type = "ADAPTER_ERROR"` — so one broken invocation can never kill
  the whole run.

Even a failed execution still gets run through the evaluation pipeline (see 3.4) — a
`TransportEvaluator` reports the transport failure as a metric, and it always resolves to a single,
unambiguous `evaluation_finding` with `category = TARGET_SYSTEM_ERROR` (see 3.5), which
short-circuits every quality-based rule below it (a transport failure is never *also* reported as,
say, `INCOMPLETE_ANSWER`).

### 3.4 Quality evaluation — `evaluation_metric_result`

Once an execution reaches a terminal, evaluable status (`SUCCEEDED`, `TIMED_OUT`, `TARGET_ERROR`,
`INVALID_RESPONSE`), `EvaluationService.evaluateExecution`
(`evaluation/application/EvaluationService.java:101`) runs **immediately** — right after that one
execution finishes, not at the end of the run. It loops over every enabled+applicable evaluator
(deterministic ones in `evaluation/deterministic/*` — e.g. `RequiredFactsEvaluator`,
`CitationPresenceEvaluator`, `RefusalEvaluator`, `LatencyEvaluator`, `TransportEvaluator`; LLM-judge
ones in `evaluation/llm/*` — e.g. `GroundednessEvaluator`, `CompletenessEvaluator`,
`ContradictionEvaluator`) and inserts **one `evaluation_metric_result` row per evaluator per
execution**.

Each row's `status` column captures one of three distinct things, which the codebase is careful
never to conflate (per the CLAUDE.md rule "never conflate evaluator failure with chatbot failure"):

| `evaluation_metric_result.status` | Meaning | `passed` column |
|---|---|---|
| `COMPLETED` | Evaluator ran fine; verdict is meaningful | `true`/`false` — did the chatbot pass this check |
| `SKIPPED` | Evaluator doesn't apply to this case (e.g. citation check on a should-refuse case) | `null` |
| `FAILED` | The **evaluator itself** crashed (bug, model unavailable, etc.) | `null` — never counted in pass rates |

This is why the app must build and run without Ollama: if an LLM evaluator can't reach Ollama, its
`evaluation_metric_result.status` becomes `FAILED` — never treated as the chatbot failing anything.

`evaluate​Execution` is idempotent: if an execution already has metric results, it's skipped, which
is what makes resuming an interrupted run safe.

After all evaluators for one execution finish, `CrossEvaluatorReviewTriggers` may flag disagreements
(e.g. a phrase-based refusal check and a semantic refusal check disagreeing) by setting
`requires_human_review = true` on the relevant metric result — independent of that evaluator's own
verdict.

### 3.5 Root-cause synthesis — `evaluation_finding`

This is the piece that's easy to conflate with metric results, so here's the precise mechanical
difference.

**`evaluation_metric_result`** = one raw, mechanical verdict from one evaluator. No notion of "why"
beyond its own `explanation` text, and — importantly — **no failure-category column at all**.

**`evaluation_finding`** = a synthesized, evidence-linked root-cause hypothesis, created by
`FailureClassificationService.classifyRun` (`classification/application/FailureClassificationService.java:83`),
which runs **once per run, at run finalization** — after every execution's metric results exist,
because some rules need the full picture (e.g. cross-repetition consistency needs all repetitions
of a test case). It assigns exactly one `FailureCategory` per finding (`TARGET_SYSTEM_ERROR`,
`RETRIEVAL_MISS`, `UNSUPPORTED_CLAIM`, `INCOMPLETE_ANSWER`, `MISSING_CITATION`, `FAILED_REFUSAL`,
`UNKNOWN`, etc. — 18 values total), plus `severity`, human-readable `title`/`summary`/`likely_cause`
/`recommendation`/`acceptance_criteria`, its own `confidence` (which can be lower than the
underlying metric result's confidence — LLM-judge-backed findings are explicitly confidence-capped),
and `requires_human_confirmation`.

The link back to evidence is the `supporting_metric_result_ids` column — a JSON array of
`evaluation_metric_result.id` values, **not an enforced foreign key**, just an application-level
pointer. One finding can point at several metric results (e.g. a required-facts failure plus an
expected-document miss both feeding one `RETRIEVAL_MISS` finding); one execution can also produce
zero findings (nothing wrong) or several (e.g. a failed refusal can yield both `FAILED_REFUSAL` and
`UNSAFE_DISCLOSURE`).

Findings are **fully regenerated** every time `classifyRun` runs: it deletes all existing findings
for the run first, then re-derives them from current metric results — so re-classifying a run (or
resuming it) never accumulates duplicates, but it does mean `evaluation_finding.id` values change
across re-runs. If a failed metric result doesn't match any specific rule, `ruleUnexplainedFailure`
still creates an honest `UNKNOWN`-category finding rather than silently dropping it — consistent
with the "no score without evidence, don't claim a root cause the data doesn't prove" rule.

**Summary table:**

| | `evaluation_metric_result` | `evaluation_finding` |
|---|---|---|
| Created by | `EvaluationService`, one evaluator at a time | `FailureClassificationService`, once per run |
| Created when | Immediately after each execution finishes | At run finalization, after all executions done |
| Cardinality | Many per execution (one per evaluator) | Zero-to-many per execution |
| Has a failure category? | No | Yes (`FailureCategory` enum) |
| Regenerated on re-run? | No — created once, skipped if present | Yes — deleted and rebuilt every `classifyRun` |
| Links to what | `test_execution_id` (FK) | `test_execution_id` (FK) + `supporting_metric_result_ids` (app-level array) |

### 3.6 Human review

`HumanReviewService.submit` (`review/application/HumanReviewService.java:46`) inserts a
`human_review` row against a specific `metric_result_id` — never against a finding or execution
directly, and never mutates the original automated metric result (reviews are append-only; a
metric result can accumulate several).

There's no persisted "review queue" table — the pending queue is a live 3-way JPQL join
(`ReviewQueueRepository.findPending`,`review/infrastructure/ReviewQueueRepository.java:53`):
`evaluation_metric_result` where `requires_human_review = true` and no `human_review` row yet
exists for it, joined through `test_execution` to `evaluation_test_case` for display context.

`requires_human_review` gets set on a metric result by three independent mechanisms: the
evaluator's own verdict, central triggers (low confidence, failure on a HIGH/CRITICAL-severity
test case, failure on a security-sensitive tag), and cross-evaluator disagreement (3.4).

If a human confirms a review (`decision = CONFIRMED`), `HumanReviewService.confirmSupportedFindings`
walks every `evaluation_finding` on the same `test_execution_id` and marks `human_confirmed = true`
on any finding that both `requires_human_confirmation` and lists this exact `metric_result_id` in
its `supporting_metric_result_ids` — this is the concrete mechanism connecting
`human_review → evaluation_metric_result → evaluation_finding`.

### 3.7 Imported traces vs. replay — two different things that both avoid calling the target

**Imported trace** (migration 011, `run/application/ImportedTraceImportService.java:87`): used
when a target's adapter type is `IMPORTED_TRACE` (you're feeding in pre-recorded chatbot responses
from an external log/export, not calling anything live). Creates an `evaluation_run` with
`run_mode = REPLAY`, then for each imported row creates a fresh `PENDING` `test_execution` plus a
1:1 `imported_trace` row (unique on `test_execution_id`). When the run executes,
`ImportedTraceTargetSystemAdapter` just looks up the trace row by execution id and returns it as an
always-`SUCCEEDED` result — no HTTP call happens at all. A missing trace surfaces as
`INVALID_RESPONSE`/`TRACE_NOT_FOUND`, going through the identical failure-recording path as a real
target error.

**Replay of a prior run** (`RunService.replay`, `run/application/RunService.java:133`): used when
you want to re-score *already-captured* answers with updated evaluators, without re-calling the
target at all. Copies terminal executions from a source run into new `test_execution` rows with
`source_execution_id` pointing back at the original (self-referential FK on `test_execution`,
never chained through an intermediate replay). `RunStateService.copyFromSourceExecution` copies
across the answer/citations/chunks/latency/tokens/status — success or failure alike — and the
evaluation pipeline re-runs against that copied data. If the source execution has since been
deleted, the new execution is marked `CANCELLED` rather than silently failing.

### 3.8 Comparing baseline vs. candidate runs

No comparison table exists — `RunComparisonService.compare`
(`comparison/application/RunComparisonService.java:54`) computes everything on the fly: loads both
runs' summaries (querying `test_execution`/`evaluation_metric_result` fresh each time), matches
test cases by `external_id`, computes per-metric pass-rate deltas, counts HIGH/CRITICAL findings
per run, and flags evaluator-version mismatches as explicit warnings (never silently averaged into
one number, per the "no score without evidence" rule). The result is an in-memory record rendered
by Thymeleaf and discarded — nothing is written back to the database.

### 3.9 Report generation

Same story: `RunReportService.build(runId)` (`report/application/RunReportService.java:172`) is a
read-only method that queries the run, target, dataset, test cases, executions, metric results,
findings, and human reviews — plus a live read of `recommendation_template` — and assembles one
in-memory `RunReport` object. `ReportController` hands that to Thymeleaf templates
(`src/main/resources/templates/run-report.html`, `comparison-report.html`). Nothing about the
report is cached or persisted; re-requesting the report URL re-renders from whatever is currently
in the database, so editing a recommendation template or submitting a new human review changes the
very next render without needing to re-run anything.

`recommendation_template` (migration 013) is seeded with one default row per `FailureCategory` and
is editable via the API. Its text is the generic, category-level "here's what to generally do about
this," shown in the report alongside each specific finding's own `recommendation` text (which is
tailored to that particular failure's evidence).

## 4. Two worked examples

### "Test case Q42 failed in run R — where do I look?"

1. `evaluation_test_case` — find its `id` (via `external_id` + `dataset_id`).
2. `test_execution WHERE evaluation_run_id = R AND test_case_id = <Q42's id>` — one row per
   repetition. Check `status` first:
   - If `TARGET_ERROR`/`TIMED_OUT`/`INVALID_RESPONSE`: the `error_type`/`error_message`/
     `http_status` columns on this exact row tell you what went wrong with the call itself. No
     answer was captured.
   - If `SUCCEEDED`: the target responded; the "failure" is a quality judgment, found downstream.
3. `evaluation_metric_result WHERE test_execution_id IN (...)` — per-evaluator `passed`/`score`/
   `explanation` for that execution. This is where you see *which* check the answer failed.
4. `evaluation_finding WHERE test_execution_id IN (...)` — the synthesized root cause (`category`,
   `severity`, `likely_cause`, `recommendation`). `supporting_metric_result_ids` tells you exactly
   which rows from step 3 back this conclusion.
5. `human_review WHERE metric_result_id IN (ids from step 3)` — any human override, if the case was
   flagged and someone reviewed it.

### "What's the difference between `evaluation_finding` and `evaluation_metric_result`, again?"

A metric result is a machine's raw yes/no/score on one narrow check ("did the answer cite a
document?"). A finding is the analyst-facing conclusion drawn from one or more of those raw checks
("the model retrieved the wrong document entirely — that's a retrieval miss, not a citation
problem"). Metric results are created continuously as executions complete; findings are computed
once, at the end, from the complete set of metric results, and get thrown away and rebuilt from
scratch every time the run is reclassified.
