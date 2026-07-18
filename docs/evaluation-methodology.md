# Evaluation Methodology

What each evaluator in the RAG Evaluation Workbench actually measures, what it demonstrably
cannot measure, and how its results should — and should not — be interpreted. This document is
the long-form companion to section 19 of the generated HTML report.

## Ground rules that apply to every evaluator

- **Black-box scope.** The workbench observes only the target's HTTP responses: answer text,
  citations, retrieved chunks, latency, and errors. It never inspects the target's index,
  prompts, or model. Any conclusion that would require inside knowledge is reported as
  "undetermined from black-box data" instead of guessed.
- **Three-way verdicts.** Every metric result is one of: the *chatbot failed the metric*
  (`COMPLETED`, `passed=false`); the *evaluator itself failed* (`FAILED`, e.g. the local judge
  model was unreachable — never counted against the chatbot and excluded from every pass-rate
  denominator); or the metric was *not applicable* (`SKIPPED`, with the reason persisted).
  Conflating these produces wrong conclusions; the workbench never does.
- **No score without evidence.** Every result carries an explanation and structured evidence.
  Where a score aggregates per-item decisions (claims, chunks), the aggregation is computed in
  code from those decisions — the judge model is a source of per-item evidence, not of
  unexplained numbers.
- **Rates with missing denominators are absent, not zero.** A metric that never completed has a
  pass rate of `null` ("n/a" in the report), never 0% or 100%.

## Deterministic evaluators

### transport
**Measures:** whether the invocation produced a usable answer at all (HTTP success and a
non-blank extracted answer).
**Limitations:** says nothing about answer quality; a 200 with a polite error message in the
answer field counts as "usable" here and is judged by the content evaluators.
**Interpretation:** a failure is a target-availability/contract problem, and downstream content
metrics are deliberately skipped — absence of those verdicts is not evidence of quality.

### latency
**Measures:** one execution's wall-clock latency against the configured maximum
(`maximumAcceptableLatencyMilliseconds`; disabled when <= 0). Run-level median/p90/p95/max are
computed separately in aggregation.
**Limitations:** measured from the workbench's side — network path included; a slow evaluation
host inflates it. For imported traces the recorded latency *is* the measurement.
**Interpretation:** a per-execution verdict about a threshold, not a capacity statement.

### required-facts
**Measures:** whether each expected fact appears in the answer, per its match type (exact
substring, case-insensitive, regex). Semantic paraphrase matching is *not* attempted here — that
is what the LLM reference-correctness/completeness evaluators are for.
**Limitations:** a correct paraphrase that avoids the expected wording fails the deterministic
check. That is by design (determinism first), and exactly the disagreement the cross-evaluator
review triggers surface.
**Interpretation:** high-confidence (1.0) on what it matched; a failure means "the expected
surface form is absent", not necessarily "the fact is absent".

### forbidden-claims
**Measures:** whether any claim marked wrong/forbidden for the test case appears in the answer
(same match types as required facts).
**Limitations:** cannot catch a forbidden *meaning* expressed in unexpected words.
**Interpretation:** a match is strong evidence of incorrect or unsafe content; no match is weak
evidence of absence.

### refusal (phrase-based)
**Measures:** for refusal-required test cases: whether the answer matches a configured refusal
phrase list, distinguishing correct refusal / direct answer / refusal-then-continuation /
empty-or-error.
**Limitations:** phrase lists are crude; novel refusal wording is missed, and boilerplate
("I cannot help with that, however…") can look like a refusal. Confidence is deliberately below
the human-review threshold in ambiguous shapes, so these results are routed to review.
**Interpretation:** treat as a screen, not a verdict — the semantic-refusal evaluator and human
review are the corroboration.

### citation-presence
**Measures:** citations against the effective citation policy (REQUIRED/OPTIONAL/FORBIDDEN,
resolvable per tag): presence, non-blank identifiers, no duplicates.
**Limitations:** checks form, not truth — it cannot tell whether a present citation actually
supports the answer.
**Interpretation:** a policy compliance check; correctness of citation content belongs to
groundedness/consistency checks.

### expected-documents
**Measures:** whether the expected document IDs appear in the retrieved chunks: Hit@1/3/5, first
relevant rank, reciprocal rank. Exact ID matching only.
**Limitations:** requires the target to expose retrieved chunks and stable document IDs; without
them the metric is skipped, which is *not distinguishable from retrieval working*.
**Interpretation:** the primary retrieval-quality signal. A miss with a wrong answer points at
retrieval; a miss with a right answer may mean the ID mapping is wrong — check the dataset.

### citation-retrieval-consistency
**Measures:** whether every cited document exists in the retrieved chunks.
**Limitations:** proves *consistency*, not citation support — a consistent citation can still be
irrelevant to the claim it decorates.
**Interpretation:** an inconsistency is hard evidence the citation layer fabricates or reuses
sources it did not read this time.

### answerability
**Measures:** for unanswerable test cases: whether the answer carries a deterministic non-answer
signal (uncertainty phrase list) instead of confident content.
**Limitations:** phrase-based, same caveats as refusal; a confident *and correct* answer to a
question the dataset wrongly marks unanswerable also fails — dataset quality matters.
**Interpretation:** failures are flagged for human confirmation before being treated as
fabrication.

## LLM evaluators (local judge model)

All eight use the local Ollama judge with schema-enforced structured output, explicit
system/user message separation, and delimiter-fenced untrusted content, so answer text cannot
instruct the judge. Every verdict is itself a model output: treat each as an informed opinion
with evidence, calibrated per engagement against the labelled sample (an *agreement rate*, never
"model accuracy"). When the judge is unavailable, these metrics are `FAILED` — a tooling gap,
never a chatbot failure.

### llm-claim-extraction
**Measures:** nothing pass/fail — it decomposes the answer into atomic factual claims that the
groundedness and consistency machinery consumes as evidence.
**Limitations:** missed or over-split claims propagate into groundedness; extraction quality is
part of what calibration samples.

### llm-groundedness
**Measures:** per extracted claim: SUPPORTED / PARTIALLY_SUPPORTED / CONTRADICTED /
NOT_IN_CONTEXT against the retrieved chunks, with supporting/contradicting chunk IDs. Pass/score
are derived in code from the per-claim verdicts.
**Limitations:** **a grounded answer can still be wrong if its source document is outdated** —
groundedness judges the answer against *what was retrieved*, not against the truth. A vacuous
pass (no claims extracted) is flagged for review, never celebrated.
**Interpretation:** read together with reference correctness: grounded + reference-wrong is the
classic outdated-document signature (surfaced as its own finding, requiring human confirmation).

### llm-reference-correctness
**Measures:** the answer against the *reference material* (reference answer and required facts) —
the authority is the dataset, not the retrieved context (context is only shown to the judge with
an explicit opt-in).
**Limitations:** only as good as the reference; a stale reference answer produces false
failures. Not applicable without reference material — and that absence is itself flagged.
**Interpretation:** the closest thing to "is it right", with the reference's own freshness as
the caveat.

### llm-completeness
**Measures:** whether the answer covers the aspects of the question the reference material
expects.
**Limitations:** judged coverage, not judged depth; and without a reference answer it judges
against required facts alone (flagged for review).
**Interpretation:** distinguishes *incomplete* (right but missing content) from *incorrect* —
the report keeps these concepts separate.

### llm-answer-relevance
**Measures:** whether the answer addresses the question asked.
**Limitations:** relevance is not correctness — a fluent, on-topic hallucination is fully
relevant.
**Interpretation:** low relevance with passing fact checks usually means boilerplate or
deflection; read it as a UX signal, not a truth signal.

### llm-context-relevance
**Measures:** per retrieved chunk: is it relevant to the question — ratio, relevant-in-top-3, and
irrelevant-top-ranked are derived in code from per-chunk decisions and persisted ranks.
**Limitations:** judges topical relevance, not sufficiency; skipped when the target exposes no
chunks.
**Interpretation:** the retrieval-precision complement to expected-documents' recall view.

### llm-semantic-refusal
**Measures:** a four-way semantic classification (answer / refusal / partial-refusal-with-
disclosure / uncertainty) on *every* successful answer, so incorrect refusals of ordinary
questions are observable, not just missed refusals.
**Limitations:** subtler than phrase matching but still a judgment call; disagreements with the
phrase-based evaluator are flagged for review rather than averaged.
**Interpretation:** the pass/fail mapping depends on the test case (shouldRefuse/answerable);
an unanswerable case accepts both honest uncertainty and refusal.

### llm-contradiction
**Measures:** explicit contradiction pairs across four relations (answer vs. reference, answer
vs. context, context vs. context, answer internal).
**Limitations:** it never decides which side of a contradiction is authoritative — context-vs-
context contradictions are flagged for human review because *no* automated judgment can pick
the current document.
**Interpretation:** contradictions are leads for the consultant, not verdicts.

## Failure classification and findings

Findings are produced by evidence-based rules (spec §16), each carrying category, explanation,
supporting metric IDs, evidence, confidence, and whether human confirmation is required. Where
the data proves only a symptom, the category is honest `UNKNOWN` with "Root cause: undetermined
from black-box data". The report never blends the distinct concepts: incorrect answer,
unsupported answer, incomplete answer, retrieval failure, and unknown root cause remain separate
categories with separate recommended actions. Recommendation text comes from per-category
templates that are manually editable (`PUT /api/v1/recommendation-templates/{category}`) — never
from unconstrained model output.

## Human review

Results are flagged for review on the spec §14 triggers (low confidence, deterministic/LLM
disagreement, LLM/LLM disagreement, HIGH/CRITICAL severity, truncated context, contradictory
documents, missing reference answer, security/permission-sensitive tags, evaluator failure or
malformed output). A review decision (CONFIRMED / REJECTED / UNCERTAIN / NEEDS_DOMAIN_EXPERT,
with optional pass/score override) is stored *next to* the automated result — never replacing
it — and reports always show both. A CONFIRMED review on a supporting metric result also
confirms the findings that rest on it.

## Calibration

`src/test/resources/calibration/` holds the labelled examples (all eleven spec §14 categories);
`LabelAgreementService` compares automated labels with human labels and reports agreement rate
plus TP/FP/TN/FN. This number describes agreement over a small labelled sample with a specific
judge model — it is not, and is never presented as, model accuracy. Re-run the comparison
(`-Pollama-it`) whenever the judge model or prompts change.
