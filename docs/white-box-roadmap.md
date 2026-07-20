# White-Box Monitoring: Requirements and Roadmap

**Status: design exploration, not committed.** Nothing in this document is implemented or
scheduled. It exists to answer one question precisely: *what would this project actually need —
in data access, domain model, architecture, and product scope — to do comprehensive white-box
monitoring, and what would that cost in terms of the boundaries this project currently holds
itself to.* Read [docs/monitoring-guide.md](monitoring-guide.md) first if you haven't — this
document assumes you understand the current black-box workflow and extends it.

**This is a scope-change proposal, not an incremental feature list.** Section 8 explains why:
several capabilities described here directly contradict non-goals stated in `CLAUDE.md`. That
tension is the main thing worth deciding on before writing any code — everything else in this
document is downstream of that decision.

---

## 1. Black-box vs. white-box: the actual axis that changes

The current tool observes exactly one thing: the HTTP request it sends and the HTTP response it
gets back (`answer`, `citations`, `retrievedChunks`, `latency`, HTTP status/errors). Everything
the report concludes is derived from that single observation point. White-box monitoring means
adding observation points *inside* the client's RAG pipeline, upstream of what the API chooses
to expose in its response.

| Observation point | Available today (black-box) | Needed for white-box |
|---|---|---|
| Final answer text | Yes | Yes (unchanged) |
| Citations as returned by the API | Yes | Yes (unchanged) |
| Retrieved chunks as returned by the API | Yes — `RetrievedChunk` already carries `chunkId`, `documentId`, `rank`, `retrievalScore`, `content` (`src/main/java/com/rageval/workbench/run/domain/RetrievedChunk.java`) | Yes (unchanged) |
| Whether those chunks were reranked before being returned | **No** — the workbench can't tell a raw vector-search order from a post-rerank order | Yes — needs the pre-rerank candidate set |
| The source document's actual freshness/version | **No** — `expectedDocumentIds` only proves a document ID was or wasn't retrieved, never whether the document is current | Yes — needs a corpus/version feed |
| The system prompt / template actually sent to the generator | **No** | Yes |
| Which embedding model and generation model (and params) produced this specific answer | Partial — `modelJsonPointer` captures whatever the target chooses to report, generally just the generation model name | Yes, both models + versions, contractually not just self-reported |
| Tool calls / agent steps between retrieval and generation | **No** | Yes |
| Token/cost breakdown per pipeline stage | Partial — `responseMapping` can capture total input/output tokens if the target reports them | Yes, per-stage |
| Real production traffic (not just a curated test dataset) | **No** — only `EvaluationDataset` test cases or explicitly imported traces | Yes, as a continuous sampled feed |

The practical consequence: today, several `FailureCategory` values are structurally
underdetermined and the spec deliberately reports them as `UNKNOWN` or
`requiresHumanConfirmation=true` rather than guess:

- `OUTDATED_DOCUMENT` vs. `RETRIEVAL_MISS`/`RETRIEVAL_RANKING` vs. `MISSING_DOCUMENTATION` — from
  the response alone, the workbench cannot distinguish "the right document was retrieved but is
  stale" from "the wrong document was retrieved" from "no document exists." All three currently
  route to human review; `MISSING_DOCUMENTATION` is unconditionally
  `requiresHumanConfirmation=true` per spec §16.
- `RETRIEVAL_RANKING` is inferred by correlating "expected document retrieved, but at a lower
  rank than the one the answer appears to follow" — it's a plausible read of the *returned*
  ranking, not proof that a reranking step actually ran or what it changed.
- `EVALUATOR_DISAGREEMENT` and low-confidence LLM judgments about groundedness sometimes exist
  simply because the workbench has no way to see the *actual* prompt/context boundary the
  generator used — it reconstructs an approximation from whatever chunks the API returned.

White-box access turns each of these from "plausible black-box inference, flagged for human
confirmation" into "proven from evidence, no human confirmation required." That is the entire
value proposition of this roadmap — not new metrics for their own sake, but collapsing the
`UNKNOWN`/`requiresHumanConfirmation` bucket that the black-box design honestly (and
deliberately) leaves large.

---

## 2. New data/access requirements

These are things the *client's* system must expose that it does not have to expose today. Each
is a new, optional integration surface — a client that only offers HTTP still gets black-box
evaluation exactly as now; these are additive.

1. **Source corpus access (read-only).** A connector that can list documents with a stable ID
   (matching whatever ID appears in `expectedDocumentIds`/citations), a content hash, a
   version/`lastModified` timestamp, and ideally a `supersedes`/`supersededBy` relationship. This
   is the single highest-value addition — see §6.
2. **Retriever/index introspection.** Read access to the vector store (or a retrieval-only
   endpoint) that returns the *raw* top-K candidates with similarity scores, before any rerank or
   filtering step, for a given query. Needed to distinguish "retriever didn't surface the
   document" from "retriever surfaced it, reranker demoted it."
3. **Full pipeline trace per request.** Every stage the answer passed through — query
   rewrite/expansion, retrieval, rerank, prompt assembly, generation, tool calls — each with its
   own input, output, latency, and token count. The natural fit is the emerging
   [OpenTelemetry GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/)
   for spans, given this project already instruments itself with Micrometer/Actuator.
4. **Prompt/template visibility.** The literal system prompt and template version used to build
   the generator call for a given answer, so a behavior change can be attributed to a prompt edit
   vs. a model swap vs. a corpus change instead of guessed at.
5. **Model/version metadata as a contract, not self-report.** Embedding model id+version,
   generation model id+version, decoding parameters — ideally attached to the trace, not just an
   optional field the target may or may not populate.
6. **A production-traffic sample feed.** A mechanism to receive or pull a sampled, consented,
   redacted stream of real user interactions continuously, distinct from the curated
   `EvaluationDataset` used today. This is the biggest privacy lift in this document — see §7.
7. **Cost/token accounting per stage**, not only the response-level total the target may report
   today via `responseMapping`.

None of these are things the workbench can synthesize from HTTP responses alone; all require the
client to build or expose new integration points on their side, which is a heavier ask of the
client than "give me a chat endpoint."

---

## 3. New domain model

Sketched at the level of new aggregates/records, following this project's existing style
(immutable records, JSONB for flexible structured data, UUID identifiers, `Instant` timestamps).

- **`Corpus` / `KnowledgeBaseSnapshot`** — tracks source documents: `documentId`, `contentHash`,
  `version`, `publishedAt`, `supersedes`. A run can be associated with the corpus snapshot active
  at the time it ran, which is what makes `OUTDATED_DOCUMENT` provable: compare the document ID
  in a citation/retrieved chunk against the corpus's current version of that ID's content hash.
- **`PipelineTrace`** — an ordered list of stages per execution (extends, not replaces,
  `TestExecution`): each stage has a type (`RETRIEVAL`, `RERANK`, `GENERATION`, `TOOL_CALL`),
  raw input/output, latency, and token count. `EvaluationEvidence` already has a `metadata` map
  and evaluators already reference `chunkId`/`documentId`/`chunkRank` — a trace-aware evaluator
  slots into the same evidence shape, it just gets to cite a specific pipeline stage instead of
  inferring one.
- **New target adapter types**, alongside today's `STANDARD_HTTP`/`IMPORTED_TRACE`:
  - `OTEL_TRACE_INGEST` — accepts OpenTelemetry GenAI spans instead of (or alongside) an HTTP
    answer, so trace-native clients don't need a bespoke export format.
  - `VECTOR_STORE_READONLY` — a connector abstraction over common stores (pgvector, Pinecone,
    Weaviate, Qdrant) for direct pre-rerank candidate introspection.
  - `FRAMEWORK_CALLBACK` — ingests LangChain/LlamaIndex callback/trace events for clients built
    on those frameworks.
- **`ProductionSample`** — a continuously-arriving analogue of `EvaluationTestCase`, minus
  hand-authored ground truth (no `referenceAnswer`/`requiredFacts` unless back-filled), plus a
  `sampledAt`, a `redactionApplied` flag, and a link to a `SamplingPolicy` (rate, consent scope,
  redaction rules) that produced it.
- **`DriftBaseline`** — a rolling statistical baseline per metric (mean/variance over a trailing
  window of runs or samples), replacing "compare exactly two named runs" with "is the current
  value outside the established range." This is a genuinely new concept — today's
  `run-comparisons` endpoint is pairwise and point-in-time by design.
- **`ScheduledRun`** — a durable, persisted recurring-run definition (dataset/target/cadence),
  polled by a bounded worker — the first-class version of "you script the recurring cURL calls
  yourself," which is what `docs/monitoring-guide.md` §10 currently recommends as the only
  option.
- **`AlertRule`** — threshold + metric + channel (webhook, email, etc.), evaluated against
  `DriftBaseline` or `RunSummary` values.

---

## 4. New evaluators/analyses this access enables

- **Index recall evaluator** — measure retriever recall@k directly against a fixed embedding
  gold set via `VECTOR_STORE_READONLY`, independent of what the generator does with the result.
  Isolates retriever health from generation quality for the first time.
- **Document freshness evaluator** — using `Corpus` version metadata, flags an answer grounded in
  a chunk whose source document was superseded after a configurable cutoff. Converts today's
  `OUTDATED_DOCUMENT` (a hypothesis surfaced by cross-referencing `llm-groundedness` and
  `llm-reference-correctness`, per `docs/evaluation-methodology.md`) into a directly evidenced
  finding, backed by the corpus's own version comparison, not a symptom-plus-guess.
- **Reranker effectiveness evaluator** — diffs the pre-rerank candidate order
  (`VECTOR_STORE_READONLY`) against the post-rerank order actually returned, against
  `expectedDocumentIds`. Proves `RETRIEVAL_RANKING` vs. `RETRIEVAL_MISS` precisely instead of
  inferring it from the returned rank alone.
- **Prompt-regression evaluator** — correlates metric-score changes across runs with prompt
  template version changes recorded in the trace, so "did this get worse because of a prompt
  edit" becomes answerable instead of "did this get worse" alone.
- **Stage-level cost/efficiency evaluator** — latency and token cost attributed per pipeline
  stage (retrieval vs. rerank vs. generation), instead of the current single wall-clock
  `latency` evaluator.
- **Production drift detector** — continuous comparison of live-sample metric distributions
  against `DriftBaseline`, raising `AlertRule`s on deviation, instead of a human deciding to run
  a comparison between two named runs.

All of these plug into the *existing* evidence and finding machinery unchanged —
`EvaluationEvidence`, the three-way verdict (chatbot failed / evaluator failed / not
applicable), `FailureCategory`, and human review keep working exactly as designed. White-box
access changes what evidence is available, not the philosophy of what to do with it — that
principle doesn't need to be redesigned, only fed richer input. See §9.

---

## 5. Architectural implications for this codebase specifically

- **Module additions**, respecting the existing one-directional dependency graph
  (`report → comparison → review → classification → evaluation → run → target`, all depending
  down into `dataset`/`common`/`configuration`): a `corpus` module (peer to `dataset`, since both
  are reference data an evaluator consults); a `sampling` module for production traffic (peer to
  `run`, since it produces executions just like a live run does); `scheduling` and `alerting`
  modules sitting above `run`.
- **No vector database requirement for the workbench's own storage.** This is worth being
  explicit about: white-box access means the workbench *talks to* the client's vector store
  read-only — it does not need to embed, index, or store vectors itself. `CLAUDE.md`'s "do not
  use a vector database" constraint can stay intact for the workbench's own persistence; only a
  new *outbound, read-only* integration is added, conceptually similar to how the `target` module
  already calls out to an external HTTP service it doesn't own.
- **Genuine background execution becomes unavoidable.** Continuous production sampling and
  scheduled recurring runs are not point-in-time operations — they need a durable job that
  survives an app restart. Today's run queue is explicitly in-memory and documented as not
  surviving a restart (README "Known MVP limitations"). This needs a durable, *bounded* job model
  (e.g., a `scheduled_run`/`sampling_cursor` table polled by a fixed-size worker on a fixed
  interval) — bounded and Postgres-backed, not literally the "unbounded asynchronous execution"
  or a message-broker (Kafka) that `CLAUDE.md` currently rules out. The distinction between
  "durable, bounded, polling-based background work" and "unbounded async execution" is the one
  that would need to be drawn explicitly if this is pursued, since the current wording doesn't
  leave obvious room for either interpretation.
- **Observability alignment.** Adopting OpenTelemetry GenAI semantic conventions for trace
  ingestion is the natural extension of the existing Micrometer/Actuator investment rather than a
  bespoke schema — it also gives white-box clients a standard way to export traces without a
  custom SDK.
- **Persistence** stays Liquibase + PostgreSQL + JSONB throughout, consistent with the existing
  pattern (`requestTemplate`, `EvaluationEvidence.metadata`, `RetrievedChunk.metadata` are all
  JSONB already) — trace stages and corpus snapshots fit the same shape without introducing a new
  storage technology.

---

## 6. Why document freshness is the highest-value, lowest-risk first step

If only one white-box capability is built, it should be corpus/document-version access (§2.1,
§3's `Corpus`, §4's document-freshness evaluator). Reasoning:

- It requires the least from the client: a read-only listing of documents with IDs, hashes, and
  timestamps — no trace instrumentation, no vector-store credentials, no production-traffic
  pipeline, no new privacy exposure beyond what citations already reveal.
- It resolves the single most common ambiguity in the current black-box report: per
  `docs/evaluation-methodology.md`, "a grounded answer can still be wrong if its source document
  is outdated" is explicitly called out as something groundedness *cannot* detect on its own —
  this is presented today as the classic case requiring a human to go check. A corpus feed
  removes that human step entirely for this one, frequently-occurring case.
- It requires no change to the run model, no scheduler, no background workers — it's a static
  reference the evaluator pipeline consults during an ordinary run, architecturally identical to
  how `EvaluationDataset` is consulted today.
- It doesn't touch any of the CLAUDE.md tensions in §8 below — no new async execution, no
  production traffic, no vector database.

Everything else in this document (index introspection, traces, production sampling, drift,
alerting) is materially larger in scope, client integration burden, and — for production
sampling specifically — privacy risk. Document freshness is the one piece of white-box access
that pays for itself without forcing the scope conversation in §8.

---

## 7. Production sampling is a distinct, larger problem

Everything else in this document is still "run a curated, consultant-authored dataset, just with
richer evidence available per run." Continuous production-traffic sampling (§2.6) is a different
kind of change: it turns the workbench from a tool that processes data the consultant explicitly
selected into one that continuously ingests whatever real users say and whatever the client's
chatbot returns them — at volume, unbounded in content, and not previously reviewed by anyone.
That has consequences the rest of this document's additions don't:

- **Privacy posture changes fundamentally.** `docs/data-handling.md`'s guarantees are built
  around a consultant deliberately choosing what test data enters the system. A production
  sample feed needs its own consent scope, redaction pipeline (PII scrubbing before persistence,
  not after), and retention policy distinct from `EvaluationDataset`/`TestExecution` — reusing
  the existing storage toggles (`store-raw-responses`, `store-retrieved-chunk-content`) is not
  sufficient on its own for a continuous real-user feed.
- **The current "no authentication, local-only, `127.0.0.1`" deployment model stops being
  adequate.** A workbench that continuously ingests live customer traffic is no longer a tool a
  consultant runs against their own laptop for the duration of an engagement — it starts looking
  like infrastructure that needs real access control, which `CLAUDE.md` explicitly defers
  ("no authentication or multi-tenancy" as a hard current constraint, not just an unstated gap).
- **Volume changes the aggregation and review model.** Human review today assumes a bounded,
  reviewable backlog per run. A continuous sample feed at production volume needs sampling *of
  the samples* for review purposes, or the review queue becomes meaningless.

Recommendation: treat production sampling as its own follow-on decision, gated behind the
scheduler/durable-job work in §5 and an explicit revisit of the auth/deployment story — not
something to build alongside the corpus/trace/index work in §§2–4.

---

## 8. Direct tension with current `CLAUDE.md` non-goals

This section exists so the tension is visible before anyone starts implementing, not discovered
mid-build. `CLAUDE.md` currently states, as hard, non-negotiable constraints:

> **What this project is NOT (do not build):** ... production monitoring agents, a general
> dashboard ... **Do NOT use:** ... a vector database ... unbounded asynchronous execution.
> ... no external paid LLM APIs · automatic modification of client systems ...
> **No auth/multi-user. One consultant, one machine.**

Mapped against this roadmap:

| This document proposes | Current `CLAUDE.md` stance |
|---|---|
| Continuous production monitoring, drift detection, alerting | Explicitly listed as "what this project is NOT" |
| Scheduled/recurring runs as a first-class durable feature | "In-memory run queue" is a stated MVP limitation, not a gap to close casually — closing it changes the concurrency model |
| Vector-store connector (read-only, external) | "Do NOT use... a vector database" — needs the "workbench doesn't store vectors, only reads a client's" distinction drawn explicitly (see §5) or this is a straightforward violation |
| Continuous background sampling/ingestion | "No unbounded asynchronous execution" — needs a bounded-and-durable design to stay inside this rule, not an exception to it |
| Production traffic ingestion at scale | "No auth/multi-user. One consultant, one machine" becomes untenable the moment the workbench is continuously fed live traffic rather than run point-in-time by one person |

None of this means the roadmap is wrong — it means **pursuing white-box *continuous monitoring*
(as opposed to white-box *evidence for point-in-time runs*, §6's corpus work) is a product
mission change, not an incremental phase**, and should be decided as such — updating `CLAUDE.md`
itself, deliberately, rather than accumulating features that quietly contradict it. The phased
roadmap in §9 is ordered specifically so that the highest-value, lowest-tension work (document
freshness) can proceed immediately, while the mission-changing work (scheduling, sampling, alerting)
is clearly gated behind an explicit decision.

---

## 9. Suggested phased roadmap

Ordered by value-to-risk ratio, each phase assumed additive to the black-box tool (nothing here
removes or breaks existing black-box evaluation of a client with no white-box access).

**Phase A — Corpus connector + document-freshness evidence.**
No scope tension (§6, §8). New `corpus` module, `Corpus`/document-version domain model, a new
deterministic-style evaluator that compares a cited/retrieved document's version against the
active corpus snapshot. Directly resolves the most common `requiresHumanConfirmation` case in
today's reports.

**Phase B — Vector-store read-only introspection + reranker-effectiveness evaluator.**
Still point-in-time, still no new async model. New `VECTOR_STORE_READONLY` adapter type
alongside `STANDARD_HTTP`/`IMPORTED_TRACE`; proves `RETRIEVAL_RANKING` vs. `RETRIEVAL_MISS`
precisely instead of inferring it.

**Phase C — OTel GenAI trace ingestion + stage-level cost/latency breakdown.**
Still point-in-time and additive to the existing `TestExecution`/run model — a trace is captured
alongside a normal run, not instead of one. New `OTEL_TRACE_INGEST` adapter type,
`PipelineTrace` domain model.

**Phase D — Durable scheduler for recurring runs.**
First real infrastructure shift: requires designing a bounded, durable, Postgres-backed job
model and explicitly deciding how it coexists with (or replaces) the "no unbounded asynchronous
execution" constraint (§5, §8). Should not proceed without that decision made explicitly.

**Phase E — Production sampling, redaction, and consent pipeline.**
Gated behind Phase D and an explicit revisit of the auth/local-only deployment story (§7). The
largest privacy and architecture lift in this document; treat as its own project, not a feature
of this one.

**Phase F — Drift detection and alerting.**
Depends on D (continuous data) and E (production-scale signal) existing first;
`DriftBaseline`/`AlertRule` as sketched in §3.

A team that only wants "more certainty in point-in-time reports" should stop after Phase C — that
gets the bulk of the root-cause-precision value (§1's table) without touching any of the §8
tensions. Phases D–F are where this stops being an incremental extension of the current product
and becomes a materially different one.

---

## 10. What does not need to change

Worth stating explicitly, since most of this document is about what's missing: the evidence
philosophy already in place — no score without evidence, the chatbot-failure vs. evaluator-failure
vs. not-applicable three-way verdict, human review recorded alongside (never replacing) automated
results, rule-based-then-editable recommendation templates instead of unconstrained LLM output —
extends to white-box data without modification. White-box access changes what evidence is
available to those mechanisms; it does not change the mechanisms themselves. Nothing in this
roadmap proposes touching `classification`, `review`, or the aggregation rules in
`evaluation/application`.
