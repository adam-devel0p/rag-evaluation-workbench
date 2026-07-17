# Data Handling and Privacy

Stub created in Phase 0. Full content lands in Phase 6/8 once storage configuration, logging, and
deletion are implemented. See `CLAUDE.md` and `docs/spec.md` §23 for the binding requirements this
document must eventually describe in full:

- What is logged (identifiers and sizes only, by default) vs. what is never logged (full prompts,
  answers, citations, chunks, tokens, documents, authorization headers).
- The dev-only opt-in for verbose content logging, and how it's disabled by default.
- How secrets are handled: only the environment variable *name* holding a secret is persisted,
  never the value; optional API-key header name is persisted, the key value is not.
- Configurable storage of raw target responses and retrieved-chunk content
  (`rag-evaluation.storage.store-raw-responses`, `store-retrieved-chunk-content`).
- Run deletion: what is deleted, what (if anything) is retained, and how it's triggered.
- Data retention behavior in general (no automatic expiry beyond what's documented here).
- Spring AI prompt/completion observation export: disabled unless explicitly enabled.
- No client content ever sent to external/paid APIs — Ollama only, and it is expected to run
  locally.
- Local-only deployment mode and what that guarantees.
- Sample limitation language: this documents data-handling *behavior*, not a legal or compliance
  certification.

This file will be rewritten (not just appended to) once the corresponding implementation exists,
so its statements are always true of the running code rather than aspirational.
