# Walkthrough: Testing Your First Real Chatbot

A copy-paste, step-by-step exercise for going from "I've read about this tool" to "I ran it
against a real, external, live chatbot and got an actual report." It uses a genuinely public
chatbot API (verified below, not guessed) so you don't need a client engagement to try this.

Read [docs/monitoring-guide.md](monitoring-guide.md) first if you haven't — this walkthrough
assumes you know the vocabulary (target, dataset, run, execution, evaluator, finding) and jumps
straight into doing.

---

## What you'll test, and why this one

We'll point the workbench at **Groq's hosted `llama-3.1-8b-instant` model**, called through
Groq's OpenAI-compatible Chat Completions API. Reasons this is a good first real target:

- It's a genuinely public, hosted, external chatbot — not something running on your machine —
  so this is a real black-box test, not a simulation.
- The API key is **free, self-serve, and (per Groq's own signup page) does not require a credit
  card** — you can do this exercise without spending anything.
- Its request/response contract is simple and stable (plain `messages` in, `choices[0].message.
  content` out) and uses only `Authorization: Bearer <key>` — which matches exactly what this
  workbench's `STANDARD_HTTP` adapter supports today (one endpoint, one JSON body, one auth
  header). Contract confirmed directly against Groq's live API reference at the time this guide
  was written.
- It is **not** a RAG system — it's a plain LLM chat endpoint with no document retrieval. That's
  deliberate for a first exercise: you'll see citation/retrieval-dependent evaluators correctly
  report "not applicable" instead of failing, which is itself useful to see once before you test
  a real RAG target where you *do* expect those evaluators to run.

> **A note on scope:** a plain chat model isn't the kind of system this tool is ultimately built
> to evaluate (it's built for RAG chatbots with citations and retrieval), but it's a real,
> working, zero-cost external target you can point at *today* to learn the mechanics — dataset →
> target → run → report → review — before you do this against a client's actual RAG system.

---

## Step 0 — Prerequisites

You should already be able to run the app per the README:

```bash
docker compose up -d postgres           # Ollama optional for this exercise — see note below
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Don't use the `demo` profile for this exercise — you don't need the fake chatbot, and it's one
less thing sharing the same running app while you're getting used to real-target configuration.

**About Ollama:** it's optional here too. Without it, you'll still get the full deterministic
evaluation (required facts, forbidden claims, refusal, answerability) — everything this
dataset's six test cases are actually designed to exercise. The eight LLM judge evaluators will
report `FAILED` (tooling gap, not a chatbot failure — see `docs/evaluation-methodology.md`) if
Ollama isn't running. If you want the full picture including `llm-semantic-refusal` and
`llm-answer-relevance` (both meaningful here even without retrieval), start it too:

```bash
docker compose up -d ollama
./scripts/pull-models.sh
```

Set once for the rest of this walkthrough:
```bash
BASE=http://127.0.0.1:8080
```

---

## Step 1 — Get a free Groq API key

1. Go to `https://console.groq.com/keys` and sign in (email/Google/GitHub signup).
2. Create an API key and copy it — Groq shows it once.
3. Export it in **the same shell you will run the Spring Boot app from** (the workbench reads
   secrets from the process environment at call time — `SecretResolver` calls `System.getenv`
   directly, so this has to be set before/where you start `./mvnw spring-boot:run`):
   ```bash
   export GROQ_API_KEY="gsk_...your key..."
   ```
4. If the app is already running, stop it and restart it after exporting the variable — it won't
   pick up an env var set in a different shell or after the JVM started.

You never paste the key into any request to this workbench — you'll only tell the workbench the
*name* of the variable (`GROQ_API_KEY`) in the next steps, exactly as the target-configuration
model requires (`docs/data-handling.md`).

---

## Step 2 — Import the dataset

This walkthrough ships with a ready-made 6-question dataset:
[examples/groq-target-dataset.json](../examples/groq-target-dataset.json) — two general-knowledge
fact checks, one arithmetic check, one refusal probe (does it correctly decline to write a
phishing email), one unanswerable-question probe (does it honestly say it doesn't have your
account balance instead of making one up), and one **hallucination probe** (asked to summarize a
paper and author that don't exist — the interesting question is whether it admits that or
confidently invents a summary).

```bash
DATASET_ID=$(curl -s -X POST "$BASE/api/v1/datasets/import/json" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/groq-target-dataset.json | jq -r .id)
echo "DATASET_ID=$DATASET_ID"

curl -s -X POST "$BASE/api/v1/datasets/$DATASET_ID/mark-ready"
```

(No `jq`? Swap the `jq -r .id` for `python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])'`,
same as `examples/demo-commands.sh` does.)

---

## Step 3 — Register Groq as a target

```bash
TARGET_ID=$(curl -s -X POST "$BASE/api/v1/targets" -H 'Content-Type: application/json' -d '{
  "name": "Groq llama-3.1-8b-instant",
  "description": "Public Groq-hosted chat model, no retrieval — first real black-box test target.",
  "adapterType": "STANDARD_HTTP",
  "endpointUrl": "https://api.groq.com/openai/v1/chat/completions",
  "httpMethod": "POST",
  "requestTemplate": {
    "model": "llama-3.1-8b-instant",
    "messages": [
      {"role": "user", "content": "{{question}}"}
    ]
  },
  "answerJsonPointer": "/choices/0/message/content",
  "citationsJsonPointer": null,
  "retrievedChunksJsonPointer": null,
  "modelJsonPointer": "/model",
  "enabled": true,
  "timeoutMilliseconds": 20000,
  "connectTimeoutMilliseconds": 5000,
  "followRedirects": false,
  "maximumConcurrency": 1,
  "authenticationType": "BEARER_TOKEN",
  "secretEnvironmentVariable": "GROQ_API_KEY",
  "responseMapping": {
    "inputTokensJsonPointer": "/usage/prompt_tokens",
    "outputTokensJsonPointer": "/usage/completion_tokens"
  }
}' | jq -r .id)
echo "TARGET_ID=$TARGET_ID"
```

Notes on the choices here:
- `citationsJsonPointer`/`retrievedChunksJsonPointer` are explicitly `null` — Groq's chat
  completions response has no such fields. Evaluators that depend on them (`expected-documents`,
  `citation-presence`, `citation-retrieval-consistency`, `llm-groundedness`,
  `llm-context-relevance`) will report `SKIPPED`/`FAILED` with a "no data" reason — expected, not
  a bug. See Step 7.
- `maximumConcurrency: 1` and a 20s timeout are deliberately conservative for a free-tier key —
  Groq is fast, but this keeps you well inside whatever rate limit applies to a brand-new key.
  You can raise `maximumConcurrency` later once you've confirmed a run completes cleanly.
- `responseMapping` captures token usage from Groq's `usage.prompt_tokens`/`usage.
  completion_tokens` — purely informational, not required for evaluation to work.

---

## Step 4 — Test the connection

```bash
curl -s -X POST "$BASE/api/v1/targets/$TARGET_ID/test-connection" \
  -H 'Content-Type: application/json' \
  -d '{"sampleQuestion": "What is the capital of Japan?"}' | jq
```

Expect a response confirming the `answer` pointer resolved (with the actual answer text back
from the real model) and `model` resolved to `llama-3.1-8b-instant`; `citations`/`chunks` will
show as not resolved/empty, which is correct here.

**If this fails:** see Troubleshooting at the bottom before moving on — no point running the
full dataset against a misconfigured target.

---

## Step 5 — Create and start the run

```bash
RUN_ID=$(curl -s -X POST "$BASE/api/v1/evaluation-runs" -H 'Content-Type: application/json' -d '{
  "targetSystemId": "'$TARGET_ID'",
  "datasetId": "'$DATASET_ID'",
  "name": "Groq llama-3.1-8b-instant — first run",
  "repetitionsPerTestCase": 1
}' | jq -r .id)
echo "RUN_ID=$RUN_ID"

curl -s -X POST "$BASE/api/v1/evaluation-runs/$RUN_ID/start"
```

One repetition for this first pass keeps it fast and light on the free-tier rate limit. Once
you've confirmed everything works end to end, re-run with `"repetitionsPerTestCase": 3` (the
project default) to actually exercise the consistency evaluator (`docs/monitoring-guide.md` §9)
— a single repetition structurally cannot detect an inconsistent chatbot.

---

## Step 6 — Poll until it finishes

```bash
watch -n 2 "curl -s $BASE/api/v1/evaluation-runs/$RUN_ID | jq .status"
```
(No `watch`? Just re-run the `curl ... | jq .status` line every couple of seconds by hand — six
short questions against a fast model should take well under a minute.)

Expect `COMPLETED`. If you see `PARTIALLY_COMPLETED`, one or more calls hit a transport error
(commonly a rate limit on a brand-new key) — check `GET
$BASE/api/v1/evaluation-runs/$RUN_ID/executions | jq` for the specific error before treating it
as an answer-quality problem (a `TARGET_SYSTEM_ERROR` finding is availability, not quality — see
`docs/monitoring-guide.md` §6.3).

---

## Step 7 — Read the results

**First, the summary** (30-second triage, per `docs/monitoring-guide.md` §6.1):
```bash
curl -s "$BASE/api/v1/evaluation-runs/$RUN_ID/summary" | jq
```
What to expect from *this specific dataset and target*, so you can tell "working as designed"
from "something's wrong":
- `testCaseOutcomes` — most of the six should be `PASSED`. `llama-3.1-8b-instant` is a
  well-aligned model; it should get the capital/planet/arithmetic questions right, decline the
  phishing request, and admit it doesn't know your balance. The genuinely interesting one to
  watch is `HALLUCINATION-PROBE-001` — read its answer text yourself either way.
- `retrieval.evaluatedCount` — should be `0`. There's nothing to retrieve; this is the "not
  applicable" case working correctly, not a failure.
- `metrics` — look for `required-facts`, `forbidden-claims`, `refusal`, `answerability` with
  non-null `passRate`. Look for `expected-documents`/`citation-presence` with
  `notApplicableCount` equal to their `applicableExecutionCount` and a `null` `passRate` — that's
  correct, not broken.
- If Ollama wasn't running, every `llm-*` metric should show `evaluatorErrorCount` equal to its
  `completedCount` of zero and a `null` `passRate` — again, tooling gap, not a chatbot failure.

**Then open the HTML report** — this is the artifact worth actually reading start to end once:
```
http://127.0.0.1:8080/reports/runs/RUN_ID_HERE
```
Follow the reading order from `docs/monitoring-guide.md` §6.2: §3 (limitations — it should
explicitly say retrieval/citations weren't available for this target) → §4 (executive summary) →
§10/§11 (any high/critical findings, with the actual evidence) → §16 (review backlog).

**Then look at raw findings if anything interesting turned up:**
```bash
curl -s "$BASE/api/v1/evaluation-runs/$RUN_ID/findings?size=50" | jq
```
If `HALLUCINATION-PROBE-001` or `REFUSAL-001` produced a finding, this is where you'll see its
`category` (e.g. `UNSUPPORTED_CLAIM`, `FAILED_REFUSAL`, or `UNKNOWN` if the evidence only proves
a symptom), `evidence`, and `confidence`.

---

## Step 8 — Review the interesting one

`HALLUCINATION-PROBE-001` and `REFUSAL-001` are both likely to land in the human-review queue
(low LLM confidence, or a HIGH/CRITICAL-severity result — see `docs/monitoring-guide.md` §8 for
the exact trigger list). Triage it yourself:

```bash
curl -s "$BASE/api/v1/reviews/pending?runId=$RUN_ID&size=10" | jq
```
Pick a `metricResultId` from the queue, read its full context, and record your own judgment —
this is the actual human-in-the-loop step a consultant would do on a real engagement:
```bash
curl -s "$BASE/api/v1/metric-results/METRIC_RESULT_ID_HERE/review-context" | jq

curl -s -X POST "$BASE/api/v1/metric-results/METRIC_RESULT_ID_HERE/reviews" \
  -H 'Content-Type: application/json' \
  -d '{"decision": "CONFIRMED", "reviewerName": "Your Name",
       "comment": "Read the actual answer text and confirmed the automated verdict."}'
```

---

## Step 9 (optional) — Turn this into a real baseline/candidate comparison

The interesting extension: change one thing about the target and see whether the workbench
correctly detects the difference. The cheapest way to do this without touching the dataset is to
swap the model. Create a **second** target identical to Step 3 except:
```json
"name": "Groq llama-3.3-70b-versatile",
"requestTemplate": {"model": "llama-3.3-70b-versatile", "messages": [{"role": "user", "content": "{{question}}"}]}
```
Run the same dataset against it, then compare:
```bash
curl -s "$BASE/api/v1/run-comparisons?baselineRunId=$RUN_ID&candidateRunId=<second run's ID>" | jq
open "$BASE/reports/comparisons?baselineRunId=$RUN_ID&candidateRunId=<second run's ID>"
```
This is a legitimate baseline-vs-candidate comparison in the sense the tool means it (two
different systems answering the same dataset) — read the comparison's warnings section before
trusting any delta, per `docs/monitoring-guide.md` §10.

---

## Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| `test-connection` returns a 400/401-shaped target error, or the execution error type is `HTTP_401` | `GROQ_API_KEY` wasn't set in the shell that started the app, or the key is wrong/revoked | Re-export the key, fully restart `./mvnw spring-boot:run` (not just re-run the curl) |
| `HTTP_429` errors on some executions, run ends `PARTIALLY_COMPLETED` | Free-tier rate limit hit | Lower isn't usually needed — the adapter already retries 429s with backoff (spec §8.1) — but if it persists, drop `maximumConcurrency` to 1 (already the default above) and re-run later |
| `MISSING_ANSWER` / empty answer errors | `answerJsonPointer` doesn't match Groq's actual response shape (e.g. you changed the request template and the response shape moved) | Re-run `test-connection` and inspect the raw response; the pointer should stay `/choices/0/message/content` unless Groq's contract has changed since this guide was written — re-verify at `https://console.groq.com/docs/api-reference` if so |
| Every `llm-*` metric is `FAILED` | Ollama isn't running/model isn't pulled | Expected without Ollama (see Step 0); start it and use replay (`docs/monitoring-guide.md` §11) to fill in the LLM metrics without re-calling Groq |
| Connection refused to `127.0.0.1:8080` | App isn't running, or crashed on startup (check Postgres is up) | `docker compose up -d postgres`, re-run `./mvnw spring-boot:run -Dspring-boot.run.profiles=local` |

---

## What to do next

Once this feels natural, the real work is repeating Steps 2–7 against an actual client RAG
system instead of a plain chat model — at that point `citationsJsonPointer`/
`retrievedChunksJsonPointer` stop being `null`, `expectedDocumentIds` in your dataset become
meaningful, and the retrieval/groundedness evaluators this exercise deliberately skipped start
doing real work. `docs/monitoring-guide.md` §5 walks through designing that dataset properly.
