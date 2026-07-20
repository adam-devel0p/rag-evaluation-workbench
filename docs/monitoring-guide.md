# Black-Box Chatbot Monitoring Guide

A practical, end-to-end guide for someone who is **new to this codebase and new to RAG
evaluation in general**. It explains what the RAG Evaluation Workbench is, the vocabulary it
uses, how to point it at a real chatbot, and — the part people usually get wrong first —
**exactly what data to look at, in what order, and what conclusions you're allowed to draw
from it.**

This guide is task-oriented. For reference material, see:

- [docs/spec.md](spec.md) — full functional specification (the source of truth for behavior)
- [docs/api.md](api.md) — endpoint list + cURL walkthrough
- [docs/evaluation-methodology.md](evaluation-methodology.md) — per-evaluator detail (this guide
  summarizes it; read that doc when you need the fine print on one specific metric)
- [docs/data-handling.md](data-handling.md) — privacy/storage guarantees you can quote to a client
- [README.md](../README.md) — setup, build/test commands, demo walkthrough

---

## 1. What problem this tool solves

A client (or your own team) has already built a chatbot — usually a RAG (Retrieval-Augmented
Generation) system: a user asks a question in natural language, the system searches an internal
knowledge base for relevant text passages ("retrieved chunks"), and an LLM writes an answer
grounded in those passages, often citing which documents it used.

RAG systems fail in specific, recurring ways:

- The retriever doesn't find the right document (**retrieval miss**).
- It finds the right document but ranks a worse one first, and the LLM follows the wrong one
  (**ranking failure**).
- The right document is retrieved, but the LLM's answer contradicts it anyway
  (**incorrect answer**, unsupported by context).
- The answer contains claims that don't appear in any retrieved text (**unsupported claim** /
  hallucination).
- The retrieved document itself is stale, so a "grounded" answer is still wrong
  (**outdated document**).
- The system should have refused (sensitive/out-of-scope question) but answered anyway
  (**failed refusal**, possibly **unsafe disclosure**).
- The same question asked twice gets materially different answers (**inconsistency**).

This workbench is a **consultant's instrument for detecting and evidencing these failure modes
from the outside** — you never see the client's index, prompts, or model. You only see what a
real end user would see: the HTTP request you send in, and the answer/citations/chunks/latency
that come back. That's what "black-box" means here. Every conclusion the tool draws is
traceable to that HTTP evidence; where the evidence isn't enough to say *why* something failed,
the tool says so explicitly (`UNKNOWN`, "root cause: undetermined from black-box data") instead
of guessing.

**"Monitoring" in this context** does not mean a live dashboard or alerting agent (the project
explicitly does not build that — see `CLAUDE.md`). It means: you run a fixed, versioned set of
test questions against the chatbot on a schedule you control (once, before/after a client
deploys a change, weekly during an engagement, etc.), and you compare results over time. Runs
are the unit of monitoring; comparison between two runs is how you detect regressions.

---

## 2. Core vocabulary (read this before anything else)

| Term | What it is |
|---|---|
| **Target** (`TargetSystem`) | The chatbot you're testing, registered with its HTTP endpoint, auth, and how to extract fields (answer/citations/chunks) from its JSON response. |
| **Dataset** (`EvaluationDataset`) | A versioned, reusable set of test questions. Must be `READY` before you can run it. |
| **Test case** (`EvaluationTestCase`) | One question, plus the *ground truth* you supply: reference answer, required facts, forbidden claims, expected source document IDs, whether it should be refused, severity, tags. |
| **Run** (`EvaluationRun`) | One execution of a dataset against a target — the thing you actually "monitor" over time. Has a mode (live HTTP call vs. replay of stored data) and a repetition count. |
| **Execution** (`TestExecution`) | One (test case × repetition) call — the captured answer, citations, chunks, latency, HTTP status, errors. |
| **Evaluator** | A deterministic check or a local-LLM "judge" that scores one execution against one dimension (e.g. did it cite the right document, is the answer grounded). |
| **Metric result** (`EvaluationMetricResult`) | One evaluator's verdict on one execution: `COMPLETED` (pass/fail), `FAILED` (the evaluator itself broke — never held against the chatbot), or `SKIPPED` (not applicable, with a reason). |
| **Finding** (`EvaluationFinding`) | A human-readable, evidence-backed diagnosis synthesized from one or more metric results — this is what you put in a report ("retrieval miss on DEMO-003, evidence: expected-documents Hit@5 = 0"). |
| **Human review** (`HumanReview`) | A consultant's manual CONFIRMED/REJECTED/UNCERTAIN/NEEDS_DOMAIN_EXPERT decision recorded *next to* an automated result — it never overwrites the automated verdict. |

Keep the **three-way verdict** distinction in your head at all times, because it's the single
most important idea in this tool:

> Every metric result means exactly one of: **the chatbot failed the check**, **our evaluator
> tooling failed** (e.g. Ollama was down — never held against the chatbot), or **the check
> didn't apply** (e.g. no citations to check). Conflating "evaluator failed" with "chatbot
> failed" is the single most common mistake a new user makes when reading a report — a bank of
> `FAILED` results for `llm-groundedness` usually means *Ollama wasn't running*, not that the
> chatbot hallucinated everything.

---

## 3. Mental model of the pipeline

```
 Dataset (your test questions + ground truth)
        │
        ▼
 Target (the chatbot's HTTP contract)
        │
        ▼
 Run  ──▶  Executions (one HTTP call per test case × repetition)
        │
        ▼
 Evaluators (deterministic + local-LLM judges) ──▶ Metric results
        │
        ▼
 Failure classification ──▶ Findings (category, evidence, confidence)
        │
        ▼
 Aggregation ──▶ Run summary (rates, counts, by tag/severity)
        │
        ▼
 Human review (triage uncertain/high-severity findings)
        │
        ▼
 HTML report (20 sections) ──▶ what you actually hand the client
        │
        ▼
 Comparison (this run vs. an earlier baseline) ──▶ how you detect regressions over time
```

---

## 4. Prerequisites and setup

1. **Java 21**, **Docker** (Postgres + optionally Ollama via Testcontainers/Compose).
2. Start infrastructure and the app:
   ```bash
   docker compose up -d postgres ollama
   ./scripts/pull-models.sh                 # optional, pulls the local judge model
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```
   (Use `local,demo` instead of `local` only when you want the built-in fake chatbot for
   practice — see §5. For a real engagement, drop `demo`.)
3. App listens on `http://127.0.0.1:8080`. Swagger UI at `/swagger-ui` is the fastest way to
   see every request/response shape live.
4. **Ollama is optional but you should treat it as "on" for real engagements.** Without it,
   deterministic evaluators (transport, latency, required-facts, forbidden-claims, refusal,
   citation-presence, expected-documents, citation-retrieval-consistency, answerability) still
   run fully. The eight LLM judge evaluators (groundedness, reference-correctness,
   completeness, answer-relevance, context-relevance, semantic-refusal, contradiction, claim
   extraction) report `FAILED` instead — you lose most of the *quality* signal, not just a nice
   extra. Check `GET /actuator/health` before trusting a report; the `evaluatorModel` component
   tells you if Ollama is reachable.
5. Everything is local-only by default (Postgres + Ollama bound to `127.0.0.1`, no external API
   calls, no auth on the workbench's own API). See §12 before deploying anywhere else.

**First time through, do the demo walkthrough before touching a real client chatbot.** Run
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local,demo` and either follow
`examples/demo-commands.sh` or the README's "Complete evaluation workflow" section. The demo
target has 14 scripted scenarios (correct answer, wrong version, hallucination, missing
citation, refusal failure, timeout, HTTP 500, nondeterminism...) specifically so you can see
each finding category fire once with a known cause, before you have to diagnose an unknown one
against a real system.

---

## 5. Step-by-step workflow against a real chatbot

### Step 1 — Design the dataset (this is the part that determines report quality)

The tool can only detect what your test cases encode as ground truth. A dataset is a JSON (or
CSV, basic fields only) file imported via `POST /api/v1/datasets/import/json`. Model it on
[examples/demo-dataset.json](../examples/demo-dataset.json). Per test case:

| Field | Purpose |
|---|---|
| `externalId` | Stable ID you reuse across runs — this is the join key for comparison over time. Never change it once you start tracking history. |
| `question` | The literal input sent to the chatbot. |
| `referenceAnswer` | Ground truth answer text — feeds the LLM reference-correctness/completeness judges. Leave it out only if you genuinely have no source of truth (the report will flag that gap, not hide it). |
| `answerable` / `shouldRefuse` | Whether a correct system should answer at all, and whether it should refuse. Drives the answerability/refusal evaluators. |
| `severity` | `LOW`/`MEDIUM`/`HIGH`/`CRITICAL` — drives what surfaces in report §7 and §10, and the run-level high/critical failure count. Reserve `CRITICAL` for things like security/compliance answers. |
| `requiredFacts` | Structured facts (`id`, `value`, `matchType` ∈ `EXACT_TEXT`/`CASE_INSENSITIVE_TEXT`/`REGEX`/`SEMANTIC`, `mandatory`, `explanation`) the answer must contain. Deterministic matching is literal — a correct paraphrase that avoids the expected wording fails this check by design; that's why the LLM reference-correctness evaluator exists as a second opinion. |
| `forbiddenClaims` | Same shape, but content that must *not* appear (e.g. a deprecated version number). |
| `expectedDocumentIds` | The document IDs a correct retrieval should surface — this is your ground truth for retrieval quality (Hit@1/3/5, MRR). Only works if the target's response exposes stable chunk/document IDs. |
| `tags` | Free-text labels (e.g. `security`, `pricing`, `retrieval-heavy`) — every report and comparison breaks results down by tag, so tag along whatever axes matter to the engagement (feature area, risk category, question type). |
| `userContext` | Optional metadata (e.g. simulated role) passed through as evidence, not interpreted. |

Practical dataset design advice for a real engagement:
- Cover the client's actual high-value and high-risk question categories, not just "easy"
  questions — the point is finding failures, not confirming success.
- Include a few deliberately unanswerable/refusal-required questions if the domain has any
  (PII, security, out-of-scope topics) — this is often where the highest-severity findings live.
- Write `requiredFacts`/`forbiddenClaims` for anything numeric or version-specific (dates,
  prices, TLS/Java versions, limits) — deterministic checks are cheap, high-confidence, and
  don't need Ollama.
- Keep `externalId`s permanent. Comparison joins runs by `externalId`, so renaming one silently
  turns a test case into "added" + "removed" instead of "tracked over time."

Then: `POST /api/v1/datasets/{id}/mark-ready` — a `READY` dataset can't be silently edited
(protects run history from drifting under you); create a new dataset/version instead.

### Step 2 — Register the target (the real chatbot)

`POST /api/v1/targets` with `adapterType: STANDARD_HTTP`. You need to know the client's API
contract: endpoint URL, HTTP method, request body shape (`requestTemplate`, with `{{question}}`
and `{{testCaseId}}` placeholders substituted per call), and where in the *response* JSON the
answer/citations/chunks/model live (`answerJsonPointer`, `citationsJsonPointer`,
`retrievedChunksJsonPointer`, `modelJsonPointer` — RFC 6901 JSON Pointers). For a real system
with auth, set `authenticationType` to `BEARER_TOKEN` or `API_KEY_HEADER` and
`secretEnvironmentVariable` to the **name** of an env var holding the token — export that var in
the shell that runs the app; the value is never persisted or logged (see §12/data-handling.md).

Then `POST /api/v1/targets/{id}/test-connection` with one real sample question. It reports which
JSON pointers actually resolved — fix your pointers here before burning a full dataset run on a
misconfigured target.

If you can't call the chatbot live (offline logs only), use `adapterType: IMPORTED_TRACE`
instead and `POST /api/v1/imported-traces` (or the CSV variant) to load previously captured
Q&A pairs — every trace's `externalTestCaseId` must exist in the dataset. This produces a
REPLAY-mode run you evaluate exactly like a live one, just without ever calling anything.

### Step 3 — Create and start a run

```bash
POST /api/v1/evaluation-runs
{ "targetSystemId": "...", "datasetId": "...", "name": "Acme chatbot — baseline 2026-07-20",
  "repetitionsPerTestCase": 3 }
POST /api/v1/evaluation-runs/{id}/start
```

Default repetitions is 3 — deliberately more than 1, because a single call can't detect
**inconsistency** (§9 below), one of the failure modes this tool exists to catch. Use more
repetitions for flaky/nondeterministic systems, fewer only to move fast during setup iteration.

Name runs so their purpose is obvious months later — you will be comparing against this run by
name/ID, and "Acme chatbot — baseline 2026-07-20" beats "Run 1."

Poll `GET /api/v1/evaluation-runs/{id}` for `status`. Terminal states:

| Status | Meaning |
|---|---|
| `COMPLETED` | Every execution finished (doesn't mean every test passed — see report). |
| `PARTIALLY_COMPLETED` | Some executions failed at the transport level (timeouts, 5xx) but the run finished. Normal for a flaky target; check §15 of the report. |
| `FAILED` | Something broke the run itself. |
| `CANCELLED` | You cancelled it. |
| `INTERRUPTED` | The app restarted mid-run; `POST .../resume` to continue — completed executions are never re-run. |

### Step 4 — Read the results (see §6 below for the full walkthrough)

Start with the JSON summary, then the HTML report, then drill into findings and reviews.

### Step 5 — Human review the uncertain/high-stakes findings

### Step 6 — Establish it as your baseline, then repeat on a cadence and compare

Sections 6–11 below go through each of these in depth.

---

## 6. What data to look at, and in what order

This is the heart of "how do I actually use this." There are three ways to look at a run — a
one-line JSON summary, a full HTML report, and raw findings/metrics for drill-down. Use them in
this order.

### 6.1 First: the run summary (`GET /api/v1/evaluation-runs/{id}/summary`)

This is your 30-second triage view before reading anything else. It's exactly spec §18's
aggregation, and every rate in it is computed so missing data can never masquerade as a good or
bad score:

- **`executions`** — total/succeeded/failed/cancelled/incomplete. If `failed` is high, the
  chatbot itself was unreachable or erroring for many questions — a target-availability problem,
  separate from answer quality (see `TARGET_SYSTEM_ERROR` findings, report §15).
- **`testCaseOutcomes`** (`PASSED`/`FAILED`/`INCONCLUSIVE`/`NOT_EVALUATED` + `passRate`) — the
  single top-line number, but read the definition, don't just quote it: a test case is `PASSED`
  only if *every enabled mandatory evaluator that completed successfully* passed.
  `INCONCLUSIVE` (evaluator infrastructure failed, e.g. Ollama down) is excluded from the pass
  rate denominator — it is not counted as a failure. **If `inconclusive` is large, your pass
  rate is not trustworthy yet — fix the evaluator infrastructure (usually: start Ollama) and
  replay before reporting the number to a client.**
- **`metrics`** — per-evaluator breakdown: `applicableExecutionCount`, `completedCount`,
  `passedCount`, `failedCount`, `evaluatorErrorCount`, `notApplicableCount`,
  `unreviewedCount`, `humanConfirmedCount`, `passRate`. `passRate` is computed **only over
  `completedCount`** — `evaluatorErrorCount` is deliberately never in that denominator. A metric
  with a `null` `passRate` means it never completed for anyone (check
  `evaluatorErrorCount`/`notApplicableCount` to see why), which the tool reports as "n/a," never
  a fake 0%.
- **`passRateByTag`** / **`passRateBySeverity`** — the fastest way to spot "the chatbot is fine
  in general but bad on `security`-tagged questions" or "all `CRITICAL` cases are failing."
- **`highOrCriticalFailureCount`** — the number to put in an executive summary headline.
- **`retrieval`** — `hitRateAt1/3/5`, `meanReciprocalRank`, over executions where an
  expected-document check actually ran. If `evaluatedCount` is small or zero, either your
  dataset doesn't set `expectedDocumentIds` or the target doesn't expose chunk IDs — retrieval
  quality is simply unmeasured, not good.
- **`latency`** — median/p90/p95/max, measured from the workbench's side (network path
  included — a slow evaluation host inflates this).
- **`correctRefusalRate`**, **`targetErrorRate`** — self-explanatory rates, same
  present-only-with-evidence rule.
- **`humanReviewBacklog`** — how many flagged results are still awaiting a decision. Non-zero
  means the report you're about to send isn't final.
- **`consistency`** — `comparableTestCases`/`consistentTestCases`/`consistencyRate` from
  repetitions (§9 below); test cases run fewer than twice aren't comparable and are excluded.

### 6.2 Second: the HTML report (`GET /reports/runs/{id}`)

Server-rendered, no JavaScript, 20 sections (spec §22) — this is the artifact you actually hand
to a client or keep as an audit record. Read it top to bottom the first time; after that, jump
to whichever section matches your question.

| # | Section | What it tells you |
|---|---|---|
| 1 | Report title & run metadata | Which run, which target, which dataset/version, when. |
| 2 | Scope | Test case counts by severity/tag — what was actually exercised. |
| 3 | Limitations | The tool's own honest caveats for *this* run (e.g. Ollama unavailable, chunk content not stored) — read this before trusting anything below it. |
| 4 | Executive summary | The one paragraph a client stakeholder reads. |
| 5 | Overall outcome counts | PASSED/FAILED/INCONCLUSIVE/NOT_EVALUATED tallies. |
| 6 | Metrics table | Every evaluator's counts and pass rate — the detailed version of §6.1 above. |
| 7 | Results by severity | Where the CRITICAL/HIGH failures concentrate. |
| 8 | Results by tag | Where failures concentrate by your dataset's tags. |
| 9 | Reliability by test category | Cross-cut of severity/tag reliability. |
| 10 | High and critical findings | **The list most people should read first in practice** — every finding at HIGH/CRITICAL severity with its evidence. |
| 11 | Representative failure examples | Full per-example detail: question, expected behavior, actual response, reference facts, retrieved chunks, citations, failed metrics, observed problem, likely cause (or "undetermined"), severity, evidence, recommended change, acceptance criteria, human-review status. This is the unit of evidence a client can't argue with — it's their own chatbot's actual output. |
| 12 | Retrieval findings | `RETRIEVAL_MISS`, `RETRIEVAL_RANKING`, `OUTDATED_DOCUMENT`, `IRRELEVANT_CONTEXT` grouped together. |
| 13 | Refusal & unsupported-claim findings | `FAILED_REFUSAL`, `UNSAFE_DISCLOSURE`, `ACCESS_CONTROL_RISK`, `UNSUPPORTED_CLAIM`. |
| 14 | Consistency findings | `INCONSISTENT_RESPONSE` — same question, different repetitions, materially different answers. |
| 15 | Latency and target errors | `HIGH_LATENCY`, `TARGET_SYSTEM_ERROR` — availability/performance, not answer quality. |
| 16 | Human-review status | Every reviewed result, automated verdict shown *alongside* the human decision (never replacing it) — plus the outstanding backlog. |
| 17 | Prioritized recommendations | Rule-based, per-category, manually editable text (`PUT /api/v1/recommendation-templates/{category}`) — never raw unconstrained LLM output. Edit these once per engagement to match the client's voice/process. |
| 18 | Acceptance criteria | What "fixed" looks like per finding — hand this to the client's dev team. |
| 19 | Methodology | What each evaluator measures/can't measure — the report-embedded twin of `evaluation-methodology.md`. |
| 20 | Evaluator configuration and versions | Exact thresholds/model/prompt versions used — this is what makes two runs comparable or not (see §10 below). |

**Practical reading order for a real engagement:** §3 (limitations) → §4 (executive summary) →
§10 (high/critical findings) → §11 (representative examples, to get the actual evidence) → §12–15
(the category breakdowns relevant to what you found) → §16 (review backlog — don't ship a report
with a nonzero backlog on high-severity items) → §17/18 (what you tell the client to fix).

### 6.3 Third: drill-down JSON when you need to build tooling or filter further

- `GET /api/v1/evaluation-runs/{id}/executions` — every raw execution (answer, citations,
  chunks, latency, status).
- `GET /api/v1/evaluation-runs/{id}/metrics` — every individual `EvaluationMetricResult`
  (evaluator key, outcome `COMPLETED`/`FAILED`/`SKIPPED`, pass/score, explanation, evidence,
  confidence).
- `GET /api/v1/evaluation-runs/{id}/findings` — every `EvaluationFinding`: `category` (one of
  the `FailureCategory` values below), `severity`, `title`, `summary`, `likelyCause`,
  `recommendation`, `acceptanceCriteria`, `evidence`, `confidence`,
  `requiresHumanConfirmation`, `supportingMetricResultIds`, `humanConfirmed`.

`FailureCategory` values you'll see, and what each implies:

```
TARGET_SYSTEM_ERROR   — availability/transport problem, not an answer-quality problem
MISSING_DOCUMENTATION — no source contains the info at all (always requires human confirmation)
OUTDATED_DOCUMENT      — grounded in a real chunk, but that chunk is stale vs. the reference
RETRIEVAL_MISS         — expected document never retrieved
RETRIEVAL_RANKING      — expected document retrieved, but ranked below a worse one that was followed
IRRELEVANT_CONTEXT     — retrieved chunks aren't relevant to the question
UNSUPPORTED_CLAIM      — answer contains claims no retrieved chunk supports
INCORRECT_ANSWER       — right context retrieved, answer still wrong/contradicts it
INCOMPLETE_ANSWER      — right but missing expected coverage
INCORRECT_CITATION     — cited a document that wasn't actually retrieved
MISSING_CITATION       — citation required by policy but absent
FAILED_REFUSAL         — should have refused, answered instead
UNSAFE_DISCLOSURE       — failed refusal that also leaked sensitive info
ACCESS_CONTROL_RISK    — permission-sensitive disclosure signal
INCONSISTENT_RESPONSE  — repetitions materially disagree
HIGH_LATENCY            — over the configured latency threshold
EVALUATOR_DISAGREEMENT — deterministic vs. LLM, or LLM vs. LLM, evaluators disagree
UNKNOWN                 — symptom proven, cause not determinable from black-box data — never guessed
```

Never treat `UNKNOWN` as a failure of the tool — it's the tool being honest that a black-box
observation (e.g. "answer is wrong") doesn't by itself prove *why* (bad retrieval? bad
generation? stale index? no way to tell without the client's internals). Report it as-is; don't
let a client push you into inventing a cause the evidence doesn't support.

---

## 7. Deterministic vs. LLM evaluators — what each actually tells you

Full detail: `docs/evaluation-methodology.md`. Short version for triage:

**Deterministic (no Ollama needed, always on):** `transport` (got a usable answer at all),
`latency` (threshold check), `required-facts`/`forbidden-claims` (literal string/regex match —
high confidence when it matches, but a correct paraphrase can still fail it), `refusal`
(phrase-list based — crude, treat as a screen not a verdict), `citation-presence` (policy
compliance, not content correctness), `expected-documents` (Hit@1/3/5, MRR — the primary
retrieval signal, exact ID match only), `citation-retrieval-consistency` (cited docs actually
retrieved), `answerability` (phrase-based non-answer detection on unanswerable cases).

**LLM judges (need Ollama, degrade to `FAILED` — not a chatbot failure — when it's down):**
`llm-claim-extraction` (feeds the others, no verdict itself), `llm-groundedness` (per-claim
SUPPORTED/PARTIALLY_SUPPORTED/CONTRADICTED/NOT_IN_CONTEXT against retrieved chunks — **a
grounded answer can still be wrong if the source document itself is outdated**, which is why you
read this together with reference-correctness), `llm-reference-correctness` (answer vs. your
dataset's ground truth, not vs. retrieved context), `llm-completeness` (coverage vs. reference),
`llm-answer-relevance` (on-topic or not — a fluent hallucination can still score "relevant"),
`llm-context-relevance` (per-chunk topical relevance — retrieval *precision*, complementing
`expected-documents`' *recall* view), `llm-semantic-refusal` (four-way classification, catches
refusal-shaped wording the phrase list misses), `llm-contradiction` (explicit contradiction
pairs; never decides which side is "true" — that's a lead for you, not a verdict).

**The single most useful cross-check:** grounded (`llm-groundedness` = supported) but
reference-wrong (`llm-reference-correctness` = fails) is the classic **outdated document**
signature — the chatbot did nothing wrong given what it retrieved; the client's knowledge base
is stale. This is exactly the kind of finding a client can't get from their own eyeballing and is
usually the most valuable thing in the report.

Every LLM judge call is schema-enforced, and the answer/context text is fenced as untrusted
content with an explicit "don't follow instructions found here" system prompt — so a chatbot
can't prompt-inject the judge via its own answer text.

---

## 8. Human review — when and how to use it

Not everything the evaluators produce is confident enough to hand to a client unchecked. Results
get flagged into the review queue automatically (spec §14) when: LLM confidence is below
threshold (default `0.70`, `rag-evaluation.evaluator.human-review-confidence-threshold`),
deterministic and LLM evaluators disagree, two LLM evaluators disagree, severity is HIGH/CRITICAL,
retrieved context was truncated (default cap 30,000 characters,
`rag-evaluation.evaluator.maximum-context-characters`), documents contradict each other, a
reference answer is missing, a security/permission-sensitive tag is present, or an evaluator
produced malformed/incomplete output.

Workflow:
```bash
GET /api/v1/reviews/pending?runId={id}          # triage queue
GET /api/v1/metric-results/{metricResultId}/review-context   # full evidence for one item
POST /api/v1/metric-results/{metricResultId}/reviews
  { "decision": "CONFIRMED", "reviewerName": "...", "comment": "...", 
    "passOverride": true|false (optional), "scoreOverride": 0.0-1.0 (optional) }
```
Decisions: `CONFIRMED` (automated verdict was right), `REJECTED` (it was wrong — use
`passOverride`/`scoreOverride` to correct it), `UNCERTAIN` (genuinely can't tell from available
evidence), `NEEDS_DOMAIN_EXPERT` (outside your competence — flag and move on). The override is
recorded **next to** the automated result, never replacing it — reports always show both, so
there's a permanent audit trail of what a human changed and why. Confirming a review on a metric
result also confirms any finding that rests on it.

**Practical rule:** clear the review backlog for every HIGH/CRITICAL finding before a report
leaves your hands. Lower-severity backlog can ship with a caveat, but don't present an
unreviewed high-stakes claim as settled.

---

## 9. Repetition and consistency

Run each test case `repetitionsPerTestCase` times (default 3). After execution, the workbench
deterministically compares repetitions' overall pass/fail, required facts, forbidden claims,
refusal classification, extracted claims, citations, and expected-document retrieval — wording
variation is not flagged, but different version numbers, different owners, conflicting
instructions, different refusal behavior, or different permission-sensitive disclosures across
repetitions are. This is the only way to catch a chatbot that's "right on average but unreliable
per-call" — a single-repetition run structurally cannot detect this failure mode. Check
`consistency.consistencyRate` in the summary and report §14 for the actual inconsistent pairs.

---

## 10. Turning single runs into monitoring: baselines and comparison

This is what makes repeated runs into "monitoring" instead of one-off audits.

1. Run once against the current chatbot → this is your **baseline**.
2. Later (after a client deploys a change, or on whatever cadence the engagement calls for — see
   below), run the **same dataset/version** against the target again → this is your
   **candidate**.
3. Compare:
   ```bash
   GET /api/v1/run-comparisons?baselineRunId={base}&candidateRunId={cand}
   ```
   or the HTML version at `/reports/comparisons?baselineRunId=..&candidateRunId=..`.

The comparison joins test cases by `externalId` and reports: **added** (in candidate only),
**removed** (in baseline only), **improved**, **regressed**, **unchanged**, **inconclusive**
cases; metric-rate deltas; high-severity finding count deltas; latency deltas. Example shape
from the spec:

```
Correctness: 64% → 82%
Groundedness: 71% → 86%
Correct refusals: 42% → 79%
High-severity failures: 8 → 2
Regressed test cases: 4
Improved test cases: 22
Inconclusive test cases: 3
```

**Read the warnings before the numbers.** If the two runs used different evaluator
versions/prompts/judge model (report §20 tells you this per run), or a different dataset
version, the comparison surfaces an explicit "not scientifically clean" warning above every
number — an improvement that's actually just a looser judge model is worse than useless if you
report it as real progress. Never suppress or paraphrase away that warning to a client.

### Operationalizing a monitoring cadence

There is **no built-in scheduler** in this project by design (it's a workbench, not a monitoring
agent — see `CLAUDE.md`'s explicit "what this project is NOT"). To run this on a cadence
yourself: script the same dataset + target + `POST .../start` sequence (cron, a CI job, a manual
checklist — your choice) against the **same dataset version and `externalId`s**, and diff each
new run against the last one (or against the original baseline) via the comparison endpoint.
Keep runs named/dated so history stays legible (`RUN_ID`s alone won't tell a future reader when
or why a run happened).

---

## 11. Replay — re-scoring without re-calling the chatbot

```bash
POST /api/v1/evaluation-runs/{id}/replay   { "name": "..." }
POST /api/v1/evaluation-runs/{replayId}/start
```

Copies every captured response from the source run and re-runs the *current* evaluator pipeline
against it — zero HTTP calls to the target. Use this when: you changed evaluator thresholds/
prompts/config and want updated scores without disturbing the client's chatbot; you brought
Ollama online after a run that had all LLM metrics `FAILED` and want to fill them in; or you want
to sanity-check a new evaluator version against known-good historical data before trusting it on
a live run. A replay is a legitimate comparison candidate (it's how `examples/demo-commands.sh`
constructs a baseline/candidate pair for the demo).

---

## 12. Data handling reminders for a real engagement

Everything stays local: Postgres + Ollama on `127.0.0.1`, no external LLM APIs, no telemetry.
Logs/metrics carry identifiers and sizes only, never question/answer/citation/chunk content, and
authorization headers are never logged. Secrets are env-var *names* in the database, never
values. Two storage knobs reduce what's retained per execution if a client requires it:
`rag-evaluation.storage.store-raw-responses=false` and
`rag-evaluation.storage.store-retrieved-chunk-content=false` (the latter disables
content-dependent LLM evaluators, which will report missing-data rather than silently judging
nothing). Delete a run and everything under it with `DELETE /api/v1/evaluation-runs/{id}`; wipe
everything with `scripts/reset-local-data.sh`. There is no authentication on this API — the
`local` profile binds to loopback specifically so that's safe by default; if you ever need to run
this off your own machine, you are responsible for putting access control in front of it
yourself. Full detail and client-facing sample language: `docs/data-handling.md`.

---

## 13. Common gotchas

- **A wall of `FAILED` on the LLM metrics** almost always means Ollama isn't running or the
  model isn't pulled — check `GET /actuator/health` (`evaluatorModel` component) before assuming
  the chatbot itself is broken.
- **`PARTIALLY_COMPLETED` isn't necessarily bad** — it just means some executions hit a
  transport-level failure (timeout/5xx). Check report §15, not the top-level status alone.
- **A `null`/"n/a" rate is not a 0%.** It means the metric had nobody in its denominator
  (skipped/not-applicable), not that everyone failed it.
- **Required-facts failures on a correct-sounding answer** are often just wording mismatches —
  cross-check against `llm-reference-correctness` before calling it a real failure.
- **Comparisons across evaluator-version changes carry an explicit warning** — don't drop it
  when summarizing to a client.
- **`READY` datasets can't be edited in place** — if you need to change questions, create a new
  dataset (or version) so old runs' history stays meaningful and comparable.
