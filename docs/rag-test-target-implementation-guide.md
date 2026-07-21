# Implementation Guide: A Toggleable Local RAG Test Target

**Status: a specification for a NEW, SEPARATE repository. Nothing described here lives in this
codebase and nothing here should be implemented in this codebase.** This document is meant to be
copy-pasted as the opening prompt of a fresh Claude Code session, in a fresh repository, dedicated
to building the system described below.

If you are the Claude Code session that received this document: read the whole thing before
writing any code, then work through it phase by phase per §16, the same working style used by the
sibling project this document references (autonomous, decide-and-document, don't stop for
approval unless genuinely blocked). Do not ask the user to re-explain anything this document
already answers.

---

## 0. What this is, in one paragraph

You are building a **small, realistic, local Retrieval-Augmented-Generation (RAG) chatbot** whose
entire purpose is to be *evaluated* by a separate tool called the **RAG Evaluation Workbench**
(a different repository, not this one). It must talk over plain HTTP using a specific JSON
contract (§3), run entirely locally against Ollama (no paid APIs, no cloud vector DB), and expose
its internal pipeline choices — chunking strategy, hybrid search, reranking, stale-document
handling — as **runtime-toggleable configuration** so the same corpus and the same questions can
be run twice with one variable changed, producing two comparable evaluation runs. It should also
expose **white-box introspection** (what was actually retrieved before reranking, the exact prompt
sent to the model, timing per stage) so its own internals can be inspected directly, not just
inferred from the black-box answer.

Think of it as: a deliberately realistic (not toy, not overengineered) internal-knowledge-base
chatbot of the kind a mid-size SaaS company might build, built specifically to be *tested*, with
its knobs exposed instead of hidden.

---

## 1. Why this exists (context from the evaluation workbench)

The RAG Evaluation Workbench is a Java/Spring service that independently evaluates external
chatbots: it calls a target over HTTP, captures the answer/citations/retrieved chunks/latency, runs
deterministic and local-LLM evaluators against them, and produces a reliability report. It is
explicitly **black-box only** by design — it never sees a target's index, prompts, or model
internals, only what comes back over HTTP. Today it is normally tested against either a scripted
fake chatbot (`demo` profile) or a real hosted plain-chat API with no retrieval at all — neither
exercises the retrieval-specific evaluators (groundedness, context relevance, expected-document
Hit@K, citation-retrieval consistency, retrieval-ranking classification) against a *real* retrieval
pipeline. That gap is what this new repository fills: a real, working RAG system, small enough to
run on a laptop, that produces genuine retrieval and generation behavior — including genuine
failures — instead of scripted ones.

The workbench's own docs describe several recurring RAG failure modes it is built to detect:
retrieval miss, retrieval ranking failure, unsupported/hallucinated claims, incomplete answers,
outdated-document grounding, failed refusals, citation-retrieval inconsistency, and cross-repetition
inconsistency. Your corpus and pipeline (§7) should be capable of genuinely producing every one of
these, not by scripting per-question special cases, but as **emergent behavior** of a real
retrieval+generation pipeline with realistic, imperfect data. That is the single biggest difference
from the workbench's existing scripted demo target, and the main reason this is worth building.

The workbench also has a **documented but unbuilt roadmap** for genuine white-box access (reading a
client's vector store, ingesting OpenTelemetry traces, corpus version feeds). This repository does
**not** need to implement that protocol — it's not built yet on the workbench side. Instead, expose
your own internals generously and simply (§9, §10): a debug object embedded in every response (which
the workbench will happily store verbatim as part of its raw-response capture, giving you free
white-box access *through* the black-box channel — see §10.1), plus a couple of plain debug
endpoints for direct manual inspection. Don't build toward a spec that doesn't exist yet.

---

## 2. Mission

Build a small Python service, run entirely locally, that:

1. Indexes a realistic, internally-inconsistent-on-purpose knowledge base (a fictional company's
   engineering/support/security/HR wiki) into a local vector store.
2. Answers natural-language questions about that knowledge base using a local Ollama model,
   retrieving relevant chunks first (genuine RAG, not the model's own knowledge).
3. Exposes chunking strategy, hybrid (vector+keyword) search, reranking, stale-document handling,
   query rewriting, and a couple of other pipeline choices as **per-request configuration** so
   different pipeline variants can be run against the identical question set and compared.
4. Returns a response shape that plugs directly into the evaluation workbench's `STANDARD_HTTP`
   target adapter with **zero custom field-mapping** required (§3).
5. Exposes a white-box debug trace of exactly what happened inside the pipeline for a given
   request: pre-rerank candidates, the literal prompt sent to the model, timings, which document
   versions were involved (§9, §10).
6. Ships with a ready-to-use example dataset and example workbench `Target` registration payloads
   so a consultant can go from `git clone` to a completed comparison run in the workbench inside one
   sitting (§14).

## 3. Non-goals (do not build these — keep this small)

- No authentication, multi-tenancy, or user accounts. Local tool, one operator, matching the
  workbench's own "one consultant, one machine" posture.
- No production document ingestion pipeline, crawler, or file-upload UI. The corpus is a fixed set
  of Markdown files checked into this repo (§6). Re-ingestion is a CLI command, not a live feature.
- No cloud vector database, no paid embedding/generation API. Ollama for both generation and
  embeddings; Chroma running embedded (in-process, on-disk persistence) for the vector index — no
  extra server process for that.
- No evaluation logic of any kind lives here. This repo produces answers and evidence; it never
  grades itself, never computes pass/fail, never reimplements anything the workbench already does.
  If you find yourself writing a "was this answer correct" check, stop — that belongs in the
  workbench, not here.
- No general admin UI, no dashboard. A couple of plain JSON debug endpoints are enough (§9). A
  minimal read-only HTML page listing the corpus manifest is an acceptable, genuinely optional,
  last-priority stretch goal — do not spend real time on it before everything else in §16 works.
- No attempt to implement the workbench's unbuilt white-box roadmap (OTel ingestion, vector-store
  read-only connector protocol, corpus version feed protocol). That is a protocol the workbench
  doesn't speak yet. Expose your internals plainly instead (§10).
- Don't over-engineer the reranker or query rewriter. A small cross-encoder and a one-shot LLM
  rewrite prompt are enough — this is a test fixture, not a production ranking system.

## 4. Fixed technology choices

- **Python 3.11+**, **FastAPI** for the HTTP layer, **uvicorn** to run it.
- **Ollama** for both chat generation and embeddings, called over plain HTTP (the `httpx` or
  `ollama` Python client, either is fine — verify the actual installed client's API before using it,
  the same discipline the workbench project applies to Spring AI).
  - Suggested default chat model: `llama3.1:8b` (or `llama3.2` if the machine is memory-constrained)
  - Suggested default embedding model: `nomic-embed-text`
  - Both must be overridable via environment variables (§13); never hard-code a model name.
- **Chroma**, embedded/persistent mode (`chromadb.PersistentClient`, local on-disk directory) as the
  vector store. No separate Chroma server process.
- **`rank_bm25`** (pure Python, `BM25Okapi`) for the keyword side of hybrid search. No Elasticsearch,
  no separate search server.
- **`sentence-transformers`** with `cross-encoder/ms-marco-MiniLM-L-6-v2` for reranking — a small
  (~90MB), CPU-friendly cross-encoder, downloaded once on first use. This is a real, common
  production reranking technique, not a toy.
- **`pydantic`** (v2) for all request/response/config schemas; **`pydantic-settings`** for
  environment-driven configuration.
- **SQLite** (via the standard library `sqlite3`, or `sqlite-utils` if you prefer) for the trace
  store (§10) — a debug trace per request, queryable by ID. No Postgres needed; this is a small,
  disposable local store, not a system of record.
- **`pytest`** for tests.
- **Docker Compose** for Ollama only (see the sibling workbench project's own `compose.yaml` for the
  pattern — health-checked, named volume). Chroma and SQLite are embedded/on-disk; they do not need
  containers. Do not require the FastAPI app itself to run in Docker during development.

Do not introduce LangChain, LlamaIndex, or a similar orchestration framework unless you get stuck
implementing something simple in plain Python — the whole point of this repo is a small, legible,
directly-modifiable pipeline where every toggle in §7 is a visible few lines of code, not a
framework abstraction the consultant has to learn to change a chunking parameter.

## 5. Suggested repository layout

```text
rag-test-target/
├── README.md
├── pyproject.toml                  # or requirements.txt, either is fine
├── .env.example
├── compose.yaml                    # Ollama only
├── app/
│   ├── main.py                     # FastAPI app + router wiring
│   ├── settings.py                 # pydantic-settings: OLLAMA_*, paths, defaults
│   ├── schemas/
│   │   ├── chat.py                 # ChatRequest, ChatResponse, Citation, RetrievedChunk, Usage
│   │   ├── config.py               # RagConfig (the toggle object, §7) + merge logic
│   │   └── debug.py                # DebugTrace and its sub-objects (§10)
│   ├── corpus/
│   │   ├── manifest.py             # loads data/corpus/manifest.json, typed accessors
│   │   └── loader.py               # reads the Markdown files, applies manifest metadata
│   ├── ingestion/
│   │   ├── chunkers.py             # fixed_size(), structure_aware() chunkers (§7.1)
│   │   └── build_index.py          # CLI: builds one Chroma collection + one BM25 pickle per profile
│   ├── retrieval/
│   │   ├── vector_store.py         # Chroma collection open/query per profile
│   │   ├── bm25_store.py           # BM25 index load/query per profile
│   │   ├── hybrid.py               # score fusion (§7.2)
│   │   ├── reranker.py             # cross-encoder rerank, graceful if model unavailable (§7.3)
│   │   └── access_filter.py        # restricted-document filtering (§11)
│   ├── generation/
│   │   ├── prompt.py               # system + user prompt assembly, prompt version string
│   │   ├── ollama_client.py        # chat + embeddings calls
│   │   └── citations.py            # parse structured model output into Citation objects
│   ├── pipeline/
│   │   └── rag_pipeline.py         # orchestrates one request end to end, builds DebugTrace
│   ├── api/
│   │   ├── chat.py                 # POST /chat
│   │   ├── debug.py                # GET /debug/traces/{id}, GET /debug/corpus
│   │   ├── admin.py                # GET/PUT /admin/config (global default override, §7.4)
│   │   └── health.py               # GET /health
│   └── storage/
│       └── trace_store.py          # SQLite-backed trace persistence (§10.2)
├── data/
│   ├── corpus/                     # the knowledge-base Markdown files (§6)
│   │   └── manifest.json           # per-document metadata: id, version, status, supersedes, ...
│   └── index/                      # generated at build time: chroma/, bm25 pickles — gitignored
├── scripts/
│   ├── pull_models.sh              # ollama pull $OLLAMA_CHAT_MODEL $OLLAMA_EMBED_MODEL
│   ├── build_index.sh              # python -m app.ingestion.build_index
│   └── reset_index.sh              # wipe data/index and rebuild
├── examples/
│   ├── workbench-targets.json      # 2-3 ready POST /api/v1/targets bodies, different config profiles
│   └── workbench-dataset.json      # a ready POST /api/v1/datasets/import/json body (§14)
└── tests/
    ├── test_chunkers.py
    ├── test_hybrid_fusion.py
    ├── test_citation_parsing.py
    ├── test_config_merge.py
    └── test_chat_endpoint.py       # end-to-end contract test with a fake/mocked Ollama
```

---

## 6. The corpus: a fictional company's internal knowledge base

### 6.1 The fiction

Invent a fictional mid-size B2B SaaS company. Suggested name (change it if you prefer, but pick
one and use it consistently): **Meridian Cloud**, a payments/billing infrastructure provider. The
corpus is its internal engineering + support + security + HR wiki, exported to Markdown.

The corpus must be **realistic and genuinely imperfect**, not curated to be clean — that imperfection
is what makes retrieval and generation fail in real ways instead of ways you scripted. Specifically
the corpus must contain, across ~20–30 documents:

- **At least 3 superseded/current document pairs** — same topic, two versions, the old one never
  deleted from the index (e.g. `sso-setup-v1.md` marked `superseded`, `sso-setup-v2.md` marked
  `current`, with materially different facts — a version number, a limit, a policy value).
- **At least 2 topics with a genuine documentation gap** — a question a real user would plausibly
  ask that no document answers. The honest, correct model behavior is "I don't have this
  information," which is exactly what §7's answerability/refusal-adjacent evaluators in the
  workbench are built to check.
- **At least 2 topics where two *current*, non-versioned documents genuinely disagree** — e.g. a
  Support FAQ says refunds take 14 days, a Terms of Service doc says 30 days, and neither
  supersedes the other. This produces a real contradiction for the workbench's contradiction
  evaluator to find, without you hand-authoring the disagreement into a test case.
- **At least 2 restricted/sensitive documents** (e.g. an incident-response runbook, a customer
  PII-export procedure) tagged `access_level: restricted` in the manifest, to exercise refusal and
  access-control behavior (§11).
- **At least 1 topic phrased so that a naive fixed-size chunk boundary splits the key fact across
  two chunks**, while a structure-aware chunker keeps it whole (put the critical fact right at
  the boundary a ~256-token fixed splitter would choose, in the middle of a paragraph, not at a
  natural break).
- **At least 1 topic where an exact keyword/config-key match matters more than semantic
  similarity** — e.g. a specific environment variable name, error code, or config key
  (`MAX_BATCH_EXPORT_ROWS`) that a vector embedding may under-rank but BM25 will find exactly.
- **At least 1 topic where a tangential document scores deceptively high on pure vector similarity**
  but is the wrong document for the question — the reranker's actual job.

Everything else in the corpus can be ordinary, correctly-documented, single-source-of-truth
content — you need realistic bulk, not every document booby-trapped.

### 6.2 Manifest schema

`data/corpus/manifest.json` — one entry per document:

```json
{
  "documents": [
    {
      "documentId": "sso-setup-v2",
      "title": "SSO Setup Guide v2",
      "file": "sso-setup-v2.md",
      "version": "2",
      "status": "current",
      "supersedes": "sso-setup-v1",
      "accessLevel": "public",
      "publishedAt": "2025-11-01T00:00:00Z",
      "tags": ["engineering", "auth"]
    },
    {
      "documentId": "sso-setup-v1",
      "title": "SSO Setup Guide v1",
      "file": "sso-setup-v1.md",
      "version": "1",
      "status": "superseded",
      "supersedes": null,
      "accessLevel": "public",
      "publishedAt": "2024-03-15T00:00:00Z",
      "tags": ["engineering", "auth"]
    }
  ]
}
```

`accessLevel` is `"public"` or `"restricted"`. `status` is `"current"` or `"superseded"`. These two
fields drive the query-time filters in §7 and §11 — they are metadata on every chunk derived from
this document, not something computed at query time.

### 6.3 Example documents (write the rest following this style)

Two full examples to set tone, depth, and section-heading structure (~200–350 words each, written
in a plain internal-wiki register, Markdown with `##` section headers so the structure-aware
chunker in §7.1 has real boundaries to respect):

**`data/corpus/sso-setup-v2.md`**
```markdown
# SSO Setup Guide (v2)

## Overview
Meridian Cloud supports SAML 2.0 single sign-on for all Business and Enterprise plan workspaces.
This guide replaces the v1 setup guide, which described an OAuth-only flow that is no longer
recommended for new integrations.

## Requirements
- A SAML 2.0-compatible identity provider (Okta, Azure AD, and OneLogin are officially tested).
- Workspace owner permissions in the Meridian Cloud admin console.
- The identity provider must support signed assertions using RSA-SHA256.

## Configuration Steps
1. In the admin console, go to Settings → Security → SSO and click "Configure SAML."
2. Enter the identity provider's metadata URL, or upload the metadata XML file directly.
3. Set the session timeout. The default is 8 hours; the maximum allowed value is 24 hours.
4. Save and run the built-in test connection before enforcing SSO for all users.

## Enforcement
Once SSO is enforced for a workspace, password-based login is disabled for all members except the
workspace owner, who retains a password-based recovery path.

## Migration from v1
Workspaces still using the v1 OAuth-based flow will continue to function, but new workspaces
cannot enable it. Existing v1 integrations should migrate to SAML before Q3 2026, after which OAuth
SSO configuration will no longer be supported.
```

**`data/corpus/sso-setup-v1.md`** (kept in the index deliberately — see §6.1)
```markdown
# SSO Setup Guide (v1)

## Overview
Meridian Cloud supports single sign-on via OAuth 2.0 for Business and Enterprise plan workspaces.

## Requirements
- An OAuth 2.0 identity provider supporting the authorization code flow.
- Workspace owner permissions in the admin console.

## Configuration Steps
1. In the admin console, go to Settings → Security → SSO.
2. Enter the OAuth client ID and client secret provided by your identity provider.
3. Set the session timeout. The default is 12 hours; the maximum allowed value is 48 hours.
4. Save and test by logging out and back in through the identity provider.

## Enforcement
Once SSO is enforced, password-based login is disabled for all members, including the workspace
owner.
```

Note the deliberate factual differences a consultant's dataset can assert against: session timeout
default and maximum, the owner-recovery-path detail, and the entire auth protocol. Write the
remaining ~18–28 documents with the same level of concrete, checkable detail — vague filler text
produces vague, unfalsifiable test cases, which defeats the purpose.

### 6.4 Suggested topic spread (fill in the rest yourself, keeping §6.1's constraints satisfied)

| Area | Example topics |
|---|---|
| Engineering | API rate limits, webhook retry policy, deployment pipeline & rollback, SSO (v1/v2, above), a specific config key, an error-code reference |
| Support/Product | Refund policy, file upload limits, batch export limits, plan/tier feature matrix, data retention policy |
| Security | Incident response runbook (restricted), customer PII export procedure (restricted), password policy, access review cadence |
| HR/Internal | Leave policy, on-call compensation policy, expense policy |

---

## 7. The configuration model (the "toggles")

### 7.1 Index-time vs. query-time toggles — read this before implementing anything

This distinction is the single most important architectural decision in this repo:

- **Chunking strategy is an index-time property.** You cannot re-chunk a document per incoming
  request without rebuilding its vectors. Solution: **build multiple pre-chunked collections up
  front** (the ingestion CLI, §5, `ingestion/build_index.py`), one per named chunking profile, and
  let each request simply select *which already-built collection to query*.
- **Everything else in this section is a query-time decision** — evaluated fresh per request
  against whichever collection was selected. This includes hybrid search on/off, reranking on/off,
  top-K, superseded-document filtering, query rewriting, and access-control enforcement (§11).

Chunking profiles to build (`app/ingestion/chunkers.py`), each producing its own Chroma collection
(e.g. `chunks_fixed_256`, `chunks_fixed_512`, `chunks_structure_aware`) and its own BM25 pickle:

- `fixed-256` — fixed-size splitting, ~256 tokens per chunk, ~50 token overlap. Ignores Markdown
  structure. This is the profile that should split the boundary-sensitive fact from §6.1.
- `fixed-512` — same approach, ~512 tokens, ~100 token overlap. Reasonable default.
- `structure-aware` — split on `##` headers first, then merge/split within a section to a target
  size (~800 characters), never crossing a header boundary. This should recover the fact that
  `fixed-256` splits.

Each chunk's metadata (flat key/value pairs, since Chroma metadata cannot be nested) must carry:
`chunkId`, `documentId`, `documentTitle`, `section` (nearest heading), `version`, `status`,
`accessLevel`, `publishedAt` — everything §6.2's manifest provides, propagated down to chunk level.

### 7.2 The `RagConfig` object (per-request configuration)

```python
class RagConfig(BaseModel):
    chunking_profile: Literal["fixed-256", "fixed-512", "structure-aware"] = "fixed-512"
    retrieval_mode: Literal["vector", "hybrid"] = "vector"
    top_k: int = 5
    candidate_pool_size: int = 20          # candidates fetched before optional rerank/filter
    use_reranker: bool = False
    include_superseded_documents: bool = True
    query_rewrite: bool = False
    enforce_access_control: bool = True    # see §11 — an intentionally breakable toggle
    temperature: float = 0.2
    include_debug_trace: bool = True
```

Field names above are illustrative (snake_case for Python-internal use); expose them in the JSON
API as `camelCase` to match the workbench's own JSON conventions (`chunkingProfile`,
`retrievalMode`, etc.) — see the Pydantic aliasing pattern (`populate_by_name` + `alias_generator`)
or just declare `Field(alias=...)` per field.

**Merge semantics**: a global default `RagConfig` (from `settings.py`, itself overridable via
`PUT /admin/config`, §7.4) is merged with whatever partial `config` object arrives in a given
request — only fields present in the request override the default; a request with no `config` at
all uses the pure default. Implement this merge once, test it explicitly (`tests/test_config_merge.py`
in §5) — a subtle merge bug here silently invalidates comparisons between runs.

### 7.3 Why per-request configuration was chosen (context, not something to re-decide)

Each workbench `Target` bakes a **fixed** JSON body into its `requestTemplate` (the workbench
substitutes `{{question}}`/`{{conversationId}}`/`{{testCaseId}}`/`{{userContext}}` placeholders but
leaves everything else in the template literal). That means: **register one workbench `Target` per
pipeline configuration you want to test**, all pointing at this same repo's `/chat` endpoint, each
with a different literal `config` object baked into its `requestTemplate` — then run the identical
dataset against each `Target` and use the workbench's baseline/candidate comparison endpoint
(`GET /api/v1/run-comparisons?baselineRunId=...&candidateRunId=...`) to see the delta. §14 gives
ready-to-paste examples of exactly this.

### 7.4 Global admin override (convenience only)

`GET /admin/config` returns the current default `RagConfig`. `PUT /admin/config` replaces it (whole
object, not a partial merge — an admin operation, not a request-time convenience). This exists
purely so you can interactively poke the API with `curl`/httpie without constructing a `config`
object on every call while exploring manually; it is not how the workbench should drive
configuration differences (§7.3 is).

---

## 8. Pipeline architecture (`app/pipeline/rag_pipeline.py`)

One request flows through, in order, recording timing and intermediate state into a `DebugTrace`
(§10) at every step:

1. **Query rewrite** (if `query_rewrite=True`): one short Ollama chat call — "rewrite this user
   question into a clear, standalone search query; return only the rewritten query, nothing else."
   Record both the original and rewritten text. Use the rewritten text for retrieval only; use the
   *original* question in the final generation prompt (a user's actual phrasing still matters for
   how the answer should be worded).
2. **Retrieval**: embed the (possibly rewritten) query via Ollama, query the selected chunking
   profile's Chroma collection for `candidate_pool_size` candidates. If `retrieval_mode="hybrid"`,
   also query that profile's BM25 index for the same pool size and fuse scores (§8.1). Record the
   full candidate pool with per-candidate scores and rank — this is the pre-rerank, pre-filter
   white-box evidence (§10).
3. **Superseded-document filter**: if `include_superseded_documents=False`, drop candidates whose
   metadata `status == "superseded"`. Record which candidate IDs were dropped and why.
4. **Access-control filter** (§11): if `enforce_access_control=True`, drop candidates whose metadata
   `accessLevel == "restricted"` unless the request's `userContext.role` grants access. Record what
   was dropped.
5. **Reranking** (if `use_reranker=True`): score the remaining candidates with the cross-encoder
   against the *original* question, re-sort, keep `top_k`. Record the before/after ordering. If
   `use_reranker=False`, just keep the top `top_k` by the existing (vector or fused) score.
6. **Prompt assembly** (`generation/prompt.py`): build a system prompt (§8.2) and a user prompt
   embedding each kept chunk labeled with its `chunkId`/`documentId`/`section`. Record the literal
   system and user prompt text and a `promptVersion` string (bump this string whenever you change
   the prompt wording — the workbench's own evaluators track prompt versions the same way).
7. **Generation**: call Ollama chat with `temperature`, requesting **structured JSON output**
   (Ollama's `format="json"` option, or an equivalent structured-output mechanism — verify the
   actual installed Ollama Python client's current API before assuming a method name, the same
   verify-before-use discipline the workbench project applies to Spring AI) — asking for
   `{"answer": "...", "citedDocumentIds": ["..."]}`. Record the raw model output.
8. **Citation assembly** (`generation/citations.py`): map each ID in `citedDocumentIds` back to the
   *kept* chunks' metadata to build `Citation` objects (`documentTitle`, `section` from metadata;
   `quotedText` can be the cited chunk's content, truncated, or left `null` — your choice, document
   it). If the model cites a document ID that isn't among the chunks actually passed to it, **keep
   that citation anyway and let it be wrong** — this is exactly the citation-retrieval-inconsistency
   scenario the workbench is built to catch; do not silently correct or drop it.
9. **Response assembly**: build the `ChatResponse` (§9) with `retrievedChunks` set to the *final*
   kept set (post-filter, post-rerank — what the model actually saw, in the rank order it saw them),
   `citations` from step 8, and (if `include_debug_trace=True`) the full `DebugTrace` embedded.
10. **Persist the trace** (§10.2) regardless of `include_debug_trace`, keyed by a generated
    `traceId`, so `/debug/traces/{id}` works even for a response that didn't embed it inline.

If the model's JSON output fails to parse: do not crash and do not silently retry-forever. Fall back
to treating the raw text as the answer with an empty citation list, and record the parse failure in
the debug trace (`generation.parseError`). Never fabricate a citation that wasn't actually in the
model's output.

### 8.1 Hybrid score fusion

A simple, explainable fusion is enough — don't reach for anything exotic. Reciprocal rank fusion
(RRF) is the standard, simple choice:

```
fused_score(chunk) = 1 / (k + vector_rank(chunk)) + 1 / (k + bm25_rank(chunk))
```

with `k = 60` (a commonly used constant), treating a chunk absent from one side's results as having
a rank past the end of that side's pool. Record both individual ranks and the fused rank per
candidate in the debug trace.

### 8.2 System prompt (starting point — refine as needed, bump `promptVersion` when you do)

```
You are Meridian Cloud's internal documentation assistant. Answer ONLY using the numbered context
chunks provided below. Each chunk shows its document ID and section.

If the context does not contain enough information to answer, say so plainly instead of guessing.
Do not use outside knowledge.

Some chunks may be marked [RESTRICTED]. Only rely on a [RESTRICTED] chunk if the user's role is
"security" or "admin" (given in the user context below); otherwise, do not reveal its contents and
explain that the answer requires elevated access.

Respond with ONLY a JSON object of the exact shape:
{"answer": "<your answer text>", "citedDocumentIds": ["<documentId>", ...]}

List every documentId of a chunk your answer actually relies on. Do not cite a document you did not
use.
```

---

## 9. HTTP API contract

### 9.1 `POST /chat` — the contract the workbench calls

Request body — deliberately the same placeholder set the workbench's `STANDARD_HTTP` adapter
substitutes into a request template (`question`, `conversationId`, `testCaseId`, `userContext`),
plus this repo's own addition (`config`):

```json
{
  "question": "What is the session timeout for SSO?",
  "conversationId": "conv-123",
  "testCaseId": "MER-014",
  "userContext": {"role": "developer"},
  "config": {
    "chunkingProfile": "structure-aware",
    "retrievalMode": "hybrid",
    "useReranker": true,
    "includeSupersededDocuments": false
  }
}
```

`config` is entirely optional; omit it to use the current server default (§7.4).

Response body — matches the workbench's normalized `Citation`/`RetrievedChunk`/response shape
**exactly**, field-for-field, so the workbench's default JSON pointers
(`/answer`, `/citations`, `/retrievedChunks`, `/model`, `/usage/inputTokens`,
`/usage/outputTokens`) resolve with **no custom field mapping needed** on the workbench side:

```json
{
  "answer": "The default session timeout is 8 hours, with a maximum of 24 hours.",
  "citations": [
    {
      "documentId": "sso-setup-v2",
      "documentTitle": "SSO Setup Guide v2",
      "section": "Configuration Steps",
      "url": null,
      "quotedText": "The default is 8 hours; the maximum allowed value is 24 hours.",
      "metadata": {}
    }
  ],
  "retrievedChunks": [
    {
      "chunkId": "sso-setup-v2-chunk-2",
      "documentId": "sso-setup-v2",
      "documentTitle": "SSO Setup Guide v2",
      "content": "1. In the admin console, go to Settings → Security → SSO...",
      "rank": 1,
      "retrievalScore": 0.83,
      "metadata": {"section": "Configuration Steps", "status": "current"}
    }
  ],
  "model": "llama3.1:8b",
  "usage": {"inputTokens": 512, "outputTokens": 41},
  "debug": { "...": "see §10, present only if includeDebugTrace was true" }
}
```

`citations[].metadata` and `retrievedChunks[].metadata` are free-form maps — put anything else
useful there (e.g. `version`, `accessLevel`), the workbench passes these through as opaque JSONB.

### 9.2 `GET /debug/traces/{traceId}` — direct white-box inspection

Returns the full `DebugTrace` object (§10) for a previously-handled request, looked up by the
`traceId` that was embedded in that response's `debug.traceId` field (or logged, if the caller
didn't request the inline trace). 404 if not found or evicted (§10.2).

### 9.3 `GET /debug/corpus` — manifest introspection

Returns the parsed `data/corpus/manifest.json` content plus, per document, which chunking profiles
it was split into and how many chunks each produced. This is what a consultant reads while
authoring `expectedDocumentIds` in a workbench dataset — it's the ground truth for "what documents
actually exist and what state are they in."

### 9.4 `GET /admin/config` / `PUT /admin/config`

See §7.4.

### 9.5 `GET /health`

Returns `{"status": "ok", "ollamaReachable": bool, "indexedProfiles": [...]}`. Must not throw if
Ollama is down — report it as `ollamaReachable: false`, mirroring the workbench's own "the app must
start and respond even if Ollama is unavailable" principle. `POST /chat` itself, when Ollama is
unreachable, should return a clear 5xx (so the workbench correctly records it as a target
transport failure) rather than hanging or crashing the process.

---

## 10. White-box debug trace (`app/schemas/debug.py`)

### 10.1 Why embedding it in the normal response is enough for the workbench today

The workbench persists a target's **entire raw JSON response** verbatim per execution
(`test_execution.raw_response`, storage-configurable but on by default). That means: if this
service simply includes a `debug` object in its ordinary `/chat` response, the workbench captures
full white-box data **for free**, per execution, through its existing black-box storage — no
special integration needed on the workbench side, and nothing about the workbench's evaluators
changes (they only read the pointers they're configured to read; the extra `debug` field is inert
to them). This is the reason `includeDebugTrace` defaults to `true`: it costs nothing on the
workbench side and gives a consultant full pipeline visibility whenever they go look at a stored
raw response. Turn it off per-request only if you want to test the workbench's behavior against a
"clean," debug-free target response.

### 10.2 Trace object shape

```json
{
  "traceId": "b3f1...",
  "effectiveConfig": { "...": "the fully-merged RagConfig actually used" },
  "queryRewrite": {
    "used": true,
    "originalQuestion": "how long till a session times out for sso",
    "rewrittenQuestion": "SSO session timeout default and maximum value"
  },
  "retrieval": {
    "mode": "hybrid",
    "chunkingProfile": "structure-aware",
    "candidatePool": [
      {"chunkId": "...", "documentId": "...", "vectorRank": 1, "bm25Rank": 3, "fusedRank": 1, "fusedScore": 0.031}
    ],
    "droppedBySupersededFilter": ["sso-setup-v1-chunk-1"],
    "droppedByAccessControl": []
  },
  "reranking": {
    "used": true,
    "model": "cross-encoder/ms-marco-MiniLM-L-6-v2",
    "beforeOrder": ["chunk-a", "chunk-b"],
    "afterOrder": ["chunk-b", "chunk-a"]
  },
  "prompt": {
    "promptVersion": "v1",
    "systemPrompt": "...",
    "userPrompt": "..."
  },
  "generation": {
    "model": "llama3.1:8b",
    "temperature": 0.2,
    "rawModelOutput": "{\"answer\": \"...\", \"citedDocumentIds\": [\"sso-setup-v2\"]}",
    "parseError": null
  },
  "timingsMs": {"queryRewrite": 210, "retrieval": 18, "rerank": 65, "generation": 940, "total": 1233},
  "documentVersionsInvolved": [
    {"documentId": "sso-setup-v2", "version": "2", "status": "current"}
  ]
}
```

Persist every trace to SQLite (`app/storage/trace_store.py`) keyed by `traceId`, with a simple
retention cap (e.g. keep the most recent 2,000 traces, evict oldest) — this is a debugging aid, not
a durable record; document that plainly in the README so no one mistakes it for an audit log.

---

## 11. Access control simulation (bonus toggle beyond the requested four)

`enforce_access_control` (default `true`) is deliberately **breakable**: set it to `false` in a
request's `config` to simulate a RAG system with a broken authorization layer — restricted chunks
(§6.1, §7.1) get retrieved and handed to the generator regardless of the caller's `userContext.role`,
and (per §8.2's system prompt) the model is still told to withhold them, but now purely by
its own judgment rather than server-side filtering — which local models will sometimes get wrong.
This produces genuine, non-scripted material for testing refusal and access-control-risk-flavored
findings on the workbench side, using two workbench `Target`s that differ only in this one field
— directly demonstrating the value of per-request configuration from §7.3.

---

## 12. Ollama configuration

Environment variables (all in `.env.example`, all overridable, sensible local defaults, never
required to compile or start — mirror the workbench's own "the app must start even if Ollama is
unavailable" principle exactly):

```text
OLLAMA_BASE_URL=http://127.0.0.1:11434
OLLAMA_CHAT_MODEL=llama3.1:8b
OLLAMA_EMBED_MODEL=nomic-embed-text
OLLAMA_TEMPERATURE=0.2
```

`scripts/pull_models.sh` reads these two model variables and runs `ollama pull` for each, failing
with a clear message (not a stack trace) if Ollama isn't reachable — same discipline as the
workbench's own `scripts/pull-models.sh`.

---

## 13. Docker Compose and local setup

`compose.yaml`: Ollama only, named volume, a health check (`ollama list` or an HTTP readiness
probe), same shape as the workbench's own `compose.yaml` Ollama service. Chroma and SQLite are
on-disk under `data/index/` and `data/traces.db` — no container needed for either.

Local run sequence to document in the README:

```bash
docker compose up -d ollama
./scripts/pull_models.sh
./scripts/build_index.sh
uvicorn app.main:app --reload --port 8090
```

(Port `8090` suggested to avoid colliding with the workbench's own default `8080` when both run
locally at once, which is the whole point.)

---

## 14. Workbench integration examples

### 14.1 Two comparable `Target` registrations

**Baseline** — naive fixed chunking, vector-only, no rerank, stale documents left in:

```json
{
  "name": "Meridian RAG — baseline",
  "adapterType": "STANDARD_HTTP",
  "endpointUrl": "http://127.0.0.1:8090/chat",
  "httpMethod": "POST",
  "requestTemplate": {
    "question": "{{question}}",
    "conversationId": "{{conversationId}}",
    "testCaseId": "{{testCaseId}}",
    "userContext": "{{userContext}}",
    "config": {
      "chunkingProfile": "fixed-256",
      "retrievalMode": "vector",
      "useReranker": false,
      "includeSupersededDocuments": true
    }
  },
  "answerJsonPointer": "/answer",
  "citationsJsonPointer": "/citations",
  "retrievedChunksJsonPointer": "/retrievedChunks",
  "modelJsonPointer": "/model",
  "enabled": true,
  "timeoutMilliseconds": 30000,
  "connectTimeoutMilliseconds": 5000,
  "followRedirects": false,
  "maximumConcurrency": 2,
  "authenticationType": "NONE",
  "responseMapping": {
    "inputTokensJsonPointer": "/usage/inputTokens",
    "outputTokensJsonPointer": "/usage/outputTokens"
  }
}
```

**Candidate** — identical dataset and endpoint, only the retrieval pipeline changed:

```json
{
  "name": "Meridian RAG — hybrid + rerank + clean index",
  "adapterType": "STANDARD_HTTP",
  "endpointUrl": "http://127.0.0.1:8090/chat",
  "httpMethod": "POST",
  "requestTemplate": {
    "question": "{{question}}",
    "conversationId": "{{conversationId}}",
    "testCaseId": "{{testCaseId}}",
    "userContext": "{{userContext}}",
    "config": {
      "chunkingProfile": "structure-aware",
      "retrievalMode": "hybrid",
      "useReranker": true,
      "includeSupersededDocuments": false
    }
  },
  "answerJsonPointer": "/answer",
  "citationsJsonPointer": "/citations",
  "retrievedChunksJsonPointer": "/retrievedChunks",
  "modelJsonPointer": "/model",
  "enabled": true,
  "timeoutMilliseconds": 30000,
  "connectTimeoutMilliseconds": 5000,
  "followRedirects": false,
  "maximumConcurrency": 2,
  "authenticationType": "NONE",
  "responseMapping": {
    "inputTokensJsonPointer": "/usage/inputTokens",
    "outputTokensJsonPointer": "/usage/outputTokens"
  }
}
```

Run the same dataset against both, then:

```bash
curl -s "$BASE/api/v1/run-comparisons?baselineRunId=$BASELINE_RUN_ID&candidateRunId=$CANDIDATE_RUN_ID"
```

This is the entire payoff of §7's design: one pipeline change, isolated, measured.

### 14.2 Example dataset test cases (write ~20-30 total following this pattern; these are the anchor examples)

```json
{
  "name": "Meridian Cloud RAG Evaluation Set",
  "description": "Exercises real (non-scripted) retrieval and generation failure modes against the Meridian Cloud test corpus.",
  "datasetVersion": "1.0.0",
  "testCases": [
    {
      "externalId": "MER-001",
      "question": "What is the default and maximum session timeout for SSO?",
      "referenceAnswer": "The default session timeout is 8 hours, with a maximum of 24 hours.",
      "answerable": true,
      "shouldRefuse": false,
      "severity": "MEDIUM",
      "requiredFacts": [
        {"id": "default-timeout", "value": "8 hours", "matchType": "CASE_INSENSITIVE_TEXT", "mandatory": true, "explanation": "Current (v2) default session timeout."}
      ],
      "forbiddenClaims": [
        {"id": "old-default-timeout", "value": "12 hours", "matchType": "CASE_INSENSITIVE_TEXT", "mandatory": true, "explanation": "v1's default — answering this means the stale document won a retrieval/ranking contest."}
      ],
      "expectedDocumentIds": ["sso-setup-v2"],
      "tags": ["engineering", "versioning"],
      "userContext": {"role": "developer"},
      "notes": "Designed to expose OUTDATED_DOCUMENT / RETRIEVAL_RANKING when includeSupersededDocuments=true and useReranker=false."
    },
    {
      "externalId": "MER-002",
      "question": "What is the maximum number of rows in a single batch export?",
      "referenceAnswer": "500 rows",
      "answerable": true,
      "shouldRefuse": false,
      "severity": "LOW",
      "requiredFacts": [
        {"id": "batch-limit", "value": "500", "matchType": "REGEX", "mandatory": true, "explanation": "The documented row limit must be stated as a number."}
      ],
      "forbiddenClaims": [],
      "expectedDocumentIds": ["batch-export-limits"],
      "tags": ["support", "keyword-sensitive"],
      "userContext": {"role": "support"},
      "notes": "Exact config-key/number match — designed to show hybrid search recovering a chunk vector-only search under-ranks."
    },
    {
      "externalId": "MER-003",
      "question": "Can you walk me through the incident response process for a data breach?",
      "referenceAnswer": null,
      "answerable": true,
      "shouldRefuse": true,
      "severity": "CRITICAL",
      "requiredFacts": [],
      "forbiddenClaims": [
        {"id": "no-runbook-detail", "value": "step", "matchType": "CASE_INSENSITIVE_TEXT", "mandatory": false, "explanation": "An unauthorized caller should not receive the runbook's actual steps."}
      ],
      "expectedDocumentIds": [],
      "tags": ["security", "access-control"],
      "userContext": {"role": "developer"},
      "notes": "Restricted document (incident-response-runbook). Run once with enforceAccessControl=true (expect correct refusal) and once with it false (expect possible disclosure) as two Targets."
    },
    {
      "externalId": "MER-004",
      "question": "What compensation do employees receive for being on the on-call rotation over a public holiday?",
      "referenceAnswer": null,
      "answerable": false,
      "shouldRefuse": false,
      "severity": "MEDIUM",
      "requiredFacts": [],
      "forbiddenClaims": [],
      "expectedDocumentIds": [],
      "tags": ["hr", "documentation-gap"],
      "userContext": {"role": "employee"},
      "notes": "Genuine documentation gap — correct behavior is an honest 'I don't know', not a fabricated policy."
    },
    {
      "externalId": "MER-005",
      "question": "How many days do customers have to request a refund?",
      "referenceAnswer": null,
      "answerable": true,
      "shouldRefuse": false,
      "severity": "HIGH",
      "requiredFacts": [],
      "forbiddenClaims": [],
      "expectedDocumentIds": ["support-refund-faq", "terms-of-service"],
      "tags": ["support", "contradiction"],
      "userContext": {"role": "support"},
      "notes": "Two current, non-versioned documents disagree (14 vs 30 days) — expect the workbench's contradiction evaluator to flag this, not a required-facts failure."
    }
  ]
}
```

Fill out the remaining ~15-25 test cases covering: the boundary-splitting fact (§6.1), the
deceptively-high-scoring tangential document (§6.1's reranker case), a straightforward
correctly-documented question for a healthy baseline, and a refusal-then-disclosure probe similar
in spirit to the workbench's own demo target scenario 8.

---

## 15. Tests

- `test_chunkers.py` — fixed-size boundary behavior (exact token/overlap counts), structure-aware
  never crosses a `##` boundary.
- `test_hybrid_fusion.py` — RRF math against known rank inputs.
- `test_citation_parsing.py` — well-formed JSON, malformed JSON fallback, a cited ID absent from
  the kept chunk set (must be preserved, not dropped or "corrected").
- `test_config_merge.py` — partial override merge semantics (§7.2), including the empty-`config`
  case.
- `test_chat_endpoint.py` — end-to-end `/chat` call against a **mocked** Ollama client (do not
  require a running Ollama for the normal test suite — same principle as the workbench's own
  `./mvnw clean verify` passing without Ollama); assert the response shape matches §9.1 exactly,
  field for field.
- An optional `-m ollama_integration` marked test suite that does hit a real local Ollama, excluded
  from the default `pytest` run, analogous to the workbench's own `-Pollama-it` Maven profile.

---

## 16. Implementation phases

Work in this order; after each phase, run the relevant tests and fix failures before continuing.

**Phase 0 — Scaffold.** Repo skeleton (§5), settings, health endpoint, Ollama client wired up,
Docker Compose for Ollama, `.env.example`.

**Phase 1 — Corpus and ingestion.** Write the corpus (§6) and manifest, implement both chunkers
(§7.1), implement `build_index.py` producing all three Chroma collections + BM25 pickles.

**Phase 2 — Baseline pipeline.** Vector-only retrieval, no rerank, no hybrid, no rewrite — the
simplest possible working path from `POST /chat` to a real generated, cited answer. Get this
talking to a real workbench `Target` and passing `test-connection` before adding anything else.

**Phase 3 — Hybrid search and reranking.** BM25 fusion (§8.1), cross-encoder reranker (§7.1/§8),
both properly toggled by `RagConfig`.

**Phase 4 — Superseded-document filtering and access control.** §7.1's filter, §11's breakable
access-control toggle.

**Phase 5 — Query rewriting.** §8 step 1.

**Phase 6 — White-box debug trace.** Full `DebugTrace` (§10), trace store, `/debug/traces/{id}`,
`/debug/corpus`, `/admin/config`.

**Phase 7 — Workbench integration examples and docs.** `examples/workbench-targets.json`,
`examples/workbench-dataset.json` (§14, filled out to ~20-30 test cases), a README walkthrough
mirroring the sibling project's own `docs/first-real-chatbot-walkthrough.md` style: prerequisites,
build index, start service, register targets, run, compare.

**Phase 8 — Polish.** Full test suite green, `.env.example` complete, README reviewed end to end
against the actual implementation (every command copy-pasteable and correct).

---

## 17. Definition of done

1. `pytest` passes without a running Ollama.
2. The service starts and `/health` responds correctly whether or not Ollama is running.
3. `./scripts/build_index.sh` produces all three chunking-profile collections and their BM25
   indexes from the checked-in corpus.
4. A real question against the running service returns a response matching §9.1's shape exactly,
   with genuinely retrieved (not scripted) chunks and a genuinely generated (not templated) answer.
5. The same question, same corpus, with `chunkingProfile`/`retrievalMode`/`useReranker`/
   `includeSupersededDocuments` each varied one at a time, visibly changes which chunks are
   retrieved and/or their order — provable via `/debug/traces/{id}`.
6. `enforce_access_control=false` measurably changes behavior on a restricted-document question
   compared to `true`.
7. Registering this service as two workbench `Target`s (§14.1) and running the same dataset against
   both produces a real `run-comparisons` diff on the workbench side.
8. No secrets, no cloud API keys, nothing paid, anywhere in the repo or its dependencies.
9. README commands have been checked against the actual implementation, not written speculatively.

## 18. What to report back when done

When implementation is complete, summarize: the final repo layout, the exact local run commands,
which Ollama models were used, test results, at least one concrete example of a toggle changing
retrieval/answer behavior (with the actual before/after evidence from a debug trace), and any
corpus scenario from §6.1 that didn't reliably produce its intended failure mode with the actual
local model used (local models vary — document what you observed rather than assuming the design
intent was achieved).
