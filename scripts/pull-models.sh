#!/usr/bin/env bash
# Pulls the configured Ollama evaluator model (spec §26).
# - Reads the model name from OLLAMA_EVALUATOR_MODEL (default: llama3.2:3b, the application default).
# - Fails with a clear message if Ollama is unreachable.
# - Skips the download when the model is already present, so nothing large is re-downloaded on
#   every startup.
set -euo pipefail

BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"
MODEL="${OLLAMA_EVALUATOR_MODEL:-llama3.2:3b}"

if ! curl -fsS --max-time 5 "${BASE_URL}/api/tags" > /dev/null 2>&1; then
    echo "ERROR: Ollama is not reachable at ${BASE_URL}." >&2
    echo "Start it first (docker compose up -d ollama) or set OLLAMA_BASE_URL to where it runs." >&2
    exit 1
fi

if curl -fsS "${BASE_URL}/api/tags" | grep -q "\"name\":\"${MODEL}\""; then
    echo "Model '${MODEL}' is already available at ${BASE_URL} — nothing to download."
    exit 0
fi

echo "Pulling '${MODEL}' from the Ollama registry via ${BASE_URL} (this can take a while)..."
# /api/pull streams NDJSON progress lines; the last line carries either "success" or an "error".
RESULT=$(curl -fsS -N -X POST "${BASE_URL}/api/pull" \
    -H 'Content-Type: application/json' \
    -d "{\"model\":\"${MODEL}\"}" | tail -n 1)

if echo "${RESULT}" | grep -q '"error"'; then
    echo "ERROR: Ollama could not pull '${MODEL}': ${RESULT}" >&2
    exit 1
fi
echo "Model '${MODEL}' pulled successfully."
