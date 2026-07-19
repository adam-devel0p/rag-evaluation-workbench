#!/usr/bin/env bash
# End-to-end demo workflow (spec §31) against the built-in demo target.
#
# Prerequisites:
#   docker compose up -d postgres ollama        (Ollama optional — see note below)
#   ./scripts/pull-models.sh                    (optional; deterministic evaluators work without it)
#   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,demo
#
# The script is safe to re-run: it creates a fresh dataset/target/run every time and deletes
# nothing. Without Ollama the LLM evaluator metrics simply report FAILED (evaluator failure,
# never a chatbot failure) and everything else — including the report — still works.
#
# Timing note: with Ollama up, all eight LLM judges run per execution. On CPU-only machines a
# small model still needs minutes per execution — a full 2-repetition demo run can exceed an
# hour. For a quick demo of the workflow itself, run with Ollama stopped (fast, deterministic
# verdicts + FAILED LLM metrics) and replay the run later with Ollama up.
#
# jq is used only if installed (pretty-printing and ID extraction). Without jq the script still
# runs: IDs are extracted with a python3 fallback, and raw JSON is printed instead of pretty JSON.
set -euo pipefail
cd "$(dirname "$0")/.."

BASE_URL="${WORKBENCH_BASE_URL:-http://127.0.0.1:8080}"

if command -v jq > /dev/null 2>&1; then
    HAVE_JQ=1
    echo "jq found — responses will be pretty-printed."
else
    HAVE_JQ=0
    echo "jq not found — raw JSON will be printed instead (install jq for nicer output)."
fi

# extract_field <json> <field>  — top-level string field, with or without jq.
extract_field() {
    if [ "${HAVE_JQ}" = "1" ]; then
        echo "$1" | jq -r ".$2"
    else
        echo "$1" | python3 -c "import json,sys; print(json.load(sys.stdin)[\"$2\"])"
    fi
}

show() {
    if [ "${HAVE_JQ}" = "1" ]; then echo "$1" | jq .; else echo "$1"; fi
}

echo
echo "== 0. Check the workbench is up =="
# /actuator/health answers 503 (aggregate DOWN) when Ollama is unreachable even though the
# workbench itself is fully functional — accept both 200 and 503 as "up".
HEALTH_CODE=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "${BASE_URL}/actuator/health" || true)
case "${HEALTH_CODE}" in
    200) echo "OK (health UP)" ;;
    503) echo "OK (app up; health reports DOWN — usually Ollama unreachable; LLM metrics will be FAILED)" ;;
    *)
        echo "ERROR: workbench not reachable at ${BASE_URL} — start it first (see header)." >&2
        exit 1
        ;;
esac

echo
echo "== 1. Import the sample dataset (spec §31 step 1) =="
DATASET_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/datasets/import/json" \
    -H 'Content-Type: application/json' \
    --data-binary @examples/demo-dataset.json)
DATASET_ID=$(extract_field "${DATASET_JSON}" "id")
echo "Imported dataset ${DATASET_ID}"

echo
echo "== 1b. Mark the dataset READY =="
curl -fsS -X POST "${BASE_URL}/api/v1/datasets/${DATASET_ID}/mark-ready" > /dev/null
echo "Dataset is READY"

echo
echo "== 2. Create a target pointing at the demo endpoint (spec §31 step 2) =="
TARGET_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/targets" \
    -H 'Content-Type: application/json' \
    -d '{
        "name": "Demo chatbot '"$(date +%s)"'",
        "description": "Built-in demo target (spec §27 scenarios)",
        "adapterType": "STANDARD_HTTP",
        "endpointUrl": "'"${BASE_URL}"'/demo-target/chat",
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
        "responseMapping": {"inputTokensJsonPointer": "/usage/inputTokens", "outputTokensJsonPointer": "/usage/outputTokens"}
    }')
TARGET_ID=$(extract_field "${TARGET_JSON}" "id")
echo "Created target ${TARGET_ID}"

echo
echo "== 3. Test the target connection (spec §31 step 3) =="
CONNECTION_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/targets/${TARGET_ID}/test-connection" \
    -H 'Content-Type: application/json' \
    -d '{"sampleQuestion": "Which Java version does the payments service require?"}')
show "${CONNECTION_JSON}"

echo
echo "== 4. Create an evaluation run, 2 repetitions (spec §31 step 4) =="
RUN_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/evaluation-runs" \
    -H 'Content-Type: application/json' \
    -d '{
        "targetSystemId": "'"${TARGET_ID}"'",
        "datasetId": "'"${DATASET_ID}"'",
        "name": "Demo baseline run",
        "repetitionsPerTestCase": 2
    }')
RUN_ID=$(extract_field "${RUN_JSON}" "id")
echo "Created run ${RUN_ID}"

echo
echo "== 5. Start the run (spec §31 step 5) =="
curl -fsS -X POST "${BASE_URL}/api/v1/evaluation-runs/${RUN_ID}/start" > /dev/null
echo "Run started"

echo
echo "== 6. Poll until completion (spec §31 step 6) =="
# With Ollama up, all eight LLM judges run per execution — a full demo run can take 10+ minutes
# on a small local model. Poll for up to 30 minutes and stop hard if the run is still going:
# every later step needs a terminal run.
STATUS="RUNNING"
for _ in $(seq 1 360); do
    RUN_STATE=$(curl -fsS "${BASE_URL}/api/v1/evaluation-runs/${RUN_ID}")
    STATUS=$(extract_field "${RUN_STATE}" "status")
    echo "  run status: ${STATUS}"
    case "${STATUS}" in
        COMPLETED|PARTIALLY_COMPLETED|FAILED|CANCELLED|INTERRUPTED) break ;;
    esac
    sleep 5
done
case "${STATUS}" in
    COMPLETED|PARTIALLY_COMPLETED) ;;
    *)
        echo "ERROR: run ${RUN_ID} is still ${STATUS} after the polling budget." >&2
        echo "Keep polling manually: curl ${BASE_URL}/api/v1/evaluation-runs/${RUN_ID}" >&2
        exit 1
        ;;
esac
echo "Run finished as ${STATUS} (PARTIALLY_COMPLETED is expected: one demo scenario deliberately answers HTTP 500)"

echo
echo "== 7. Retrieve the JSON summary (spec §31 step 7) =="
SUMMARY_JSON=$(curl -fsS "${BASE_URL}/api/v1/evaluation-runs/${RUN_ID}/summary")
show "${SUMMARY_JSON}"

echo
echo "== 8. The HTML report (spec §31 step 8) =="
echo "Open in a browser:  ${BASE_URL}/reports/runs/${RUN_ID}"

echo
echo "== 9. Submit a human review on the first pending item (spec §31 step 9) =="
PENDING_JSON=$(curl -fsS "${BASE_URL}/api/v1/reviews/pending?runId=${RUN_ID}&size=1")
if [ "${HAVE_JQ}" = "1" ]; then
    METRIC_RESULT_ID=$(echo "${PENDING_JSON}" | jq -r '.content[0].metricResultId // empty')
else
    METRIC_RESULT_ID=$(echo "${PENDING_JSON}" | python3 -c "
import json, sys
rows = json.load(sys.stdin).get('content', [])
print(rows[0]['metricResultId'] if rows else '')")
fi
if [ -n "${METRIC_RESULT_ID}" ]; then
    REVIEW_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/metric-results/${METRIC_RESULT_ID}/reviews" \
        -H 'Content-Type: application/json' \
        -d '{"decision": "CONFIRMED", "reviewerName": "Demo Reviewer", "comment": "Verified against the demo documentation."}')
    show "${REVIEW_JSON}"
else
    echo "No pending review items for this run — skipping (this can happen if every metric passed cleanly)."
fi

echo
echo "== 10. Create and start a replay run (spec §31 step 10) =="
REPLAY_JSON=$(curl -fsS -X POST "${BASE_URL}/api/v1/evaluation-runs/${RUN_ID}/replay" \
    -H 'Content-Type: application/json' \
    -d '{"name": "Demo replay run"}')
REPLAY_ID=$(extract_field "${REPLAY_JSON}" "id")
curl -fsS -X POST "${BASE_URL}/api/v1/evaluation-runs/${REPLAY_ID}/start" > /dev/null
for _ in $(seq 1 60); do
    REPLAY_STATUS=$(extract_field "$(curl -fsS "${BASE_URL}/api/v1/evaluation-runs/${REPLAY_ID}")" "status")
    case "${REPLAY_STATUS}" in COMPLETED|PARTIALLY_COMPLETED|FAILED) break ;; esac
    sleep 1
done
echo "Replay run ${REPLAY_ID} finished as ${REPLAY_STATUS} — stored responses re-evaluated, zero live target calls"

echo
echo "== 11. Compare baseline and candidate (spec §31 step 11) =="
COMPARISON_JSON=$(curl -fsS "${BASE_URL}/api/v1/run-comparisons?baselineRunId=${RUN_ID}&candidateRunId=${REPLAY_ID}")
show "${COMPARISON_JSON}"
echo
echo "HTML comparison report:  ${BASE_URL}/reports/comparisons?baselineRunId=${RUN_ID}&candidateRunId=${REPLAY_ID}"

echo
echo "Demo workflow complete."
echo "  Run report:        ${BASE_URL}/reports/runs/${RUN_ID}"
echo "  Comparison report: ${BASE_URL}/reports/comparisons?baselineRunId=${RUN_ID}&candidateRunId=${REPLAY_ID}"
