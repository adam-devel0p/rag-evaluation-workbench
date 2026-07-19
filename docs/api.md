# API documentation

## OpenAPI / Swagger UI (springdoc)

Interactive, always-current API documentation is served by **springdoc-openapi 3.0.3** while the
application runs:

- OpenAPI JSON: `http://127.0.0.1:8080/v3/api-docs`
- Swagger UI: `http://127.0.0.1:8080/swagger-ui`

Compatibility note (spec §29 asked for this to be verified, not assumed): springdoc's **3.x** line
is the Spring Boot 4 line — 3.0.3 builds against Boot 4.0.5 (checked in its published POM); the
2.8.x line targets Boot 3 and must not be used here. Compatibility with this project's Spring
Boot **4.1.0** was verified empirically: the full test suite runs with springdoc on the
classpath, and `HardeningIntegrationTest#openApiDocumentIsServedBySpringdoc` asserts `/v3/api-docs`
serves a valid document listing the workbench endpoints.

One real integration consequence, fixed in Phase 8: springdoc (via swagger-core) puts Jackson 2
databind on the classpath next to this project's Jackson 3. Hibernate's JSON format-mapper
auto-detection then preferred Jackson 2, breaking every JSONB column mapped to Jackson 3 types.
`application.properties` pins `spring.jpa.properties.hibernate.type.json_format_mapper=jackson3`
to keep entity JSON mapping on Jackson 3 regardless of what else the classpath carries.

## Endpoint overview

All endpoints are JSON over HTTP on `http://127.0.0.1:8080` (local profile), no authentication
(local-only deployment — see `docs/data-handling.md`). Errors are RFC 9457 `ProblemDetail` with a
`correlationId` extension property.

| Area | Endpoints |
| --- | --- |
| Targets | `POST/GET /api/v1/targets`, `GET/PUT/DELETE /api/v1/targets/{id}`, `POST /api/v1/targets/{id}/test-connection` |
| Datasets | `POST/GET /api/v1/datasets`, `GET /api/v1/datasets/{id}`, `POST /api/v1/datasets/{id}/mark-ready`, `GET/POST /api/v1/datasets/{id}/test-cases`, `PUT /api/v1/datasets/{id}/test-cases/{testCaseId}`, `POST /api/v1/datasets/import/json`, `POST /api/v1/datasets/import/csv` |
| Imported traces | `POST /api/v1/imported-traces`, `POST /api/v1/imported-traces/csv` |
| Runs | `POST/GET /api/v1/evaluation-runs`, `GET/DELETE /api/v1/evaluation-runs/{id}`, `POST /api/v1/evaluation-runs/{id}/start\|cancel\|resume\|replay`, `GET /api/v1/evaluation-runs/{id}/executions\|metrics\|findings\|summary` |
| Reviews | `GET /api/v1/reviews/pending`, `GET /api/v1/metric-results/{id}/review-context`, `GET/POST /api/v1/metric-results/{id}/reviews` |
| Recommendations | `GET/PUT /api/v1/recommendation-templates/{category}` |
| Comparison | `GET /api/v1/run-comparisons?baselineRunId=&candidateRunId=[&allowDatasetMismatch=true]` |
| Reports (HTML) | `GET /reports/runs/{id}`, `GET /reports/comparisons?baselineRunId=&candidateRunId=` |
| Ops | `GET /actuator/health\|info\|metrics\|prometheus` |

## Complete cURL walkthrough (end-to-end demo, spec §31)

Prerequisites: `docker compose up -d postgres ollama`, then
`./mvnw spring-boot:run -Dspring-boot.run.profiles=local,demo`. The same flow, fully scripted, is
`examples/demo-commands.sh`. Responses are JSON; capture the `id` fields as you go.

```bash
BASE=http://127.0.0.1:8080

# 1. Import the sample dataset (creates dataset + all 14 demo test cases atomically)
curl -s -X POST "$BASE/api/v1/datasets/import/json" \
  -H 'Content-Type: application/json' \
  --data-binary @examples/demo-dataset.json
# → note "id" as DATASET_ID

# 1b. Mark it READY (runs only accept READY datasets)
curl -s -X POST "$BASE/api/v1/datasets/$DATASET_ID/mark-ready"

# 2. Create a target pointing at the built-in demo endpoint
curl -s -X POST "$BASE/api/v1/targets" -H 'Content-Type: application/json' -d '{
  "name": "Demo chatbot", "adapterType": "STANDARD_HTTP",
  "endpointUrl": "'$BASE'/demo-target/chat", "httpMethod": "POST",
  "requestTemplate": {"question": "{{question}}", "testCaseId": "{{testCaseId}}"},
  "answerJsonPointer": "/answer", "citationsJsonPointer": "/citations",
  "retrievedChunksJsonPointer": "/retrievedChunks", "modelJsonPointer": "/model",
  "enabled": true, "timeoutMilliseconds": 10000, "connectTimeoutMilliseconds": 2000,
  "followRedirects": false, "maximumConcurrency": 2, "authenticationType": "NONE",
  "responseMapping": {"inputTokensJsonPointer": "/usage/inputTokens",
                       "outputTokensJsonPointer": "/usage/outputTokens"}
}'
# → note "id" as TARGET_ID

# 3. Test the connection (real call; reports which pointers resolved)
curl -s -X POST "$BASE/api/v1/targets/$TARGET_ID/test-connection" \
  -H 'Content-Type: application/json' \
  -d '{"sampleQuestion": "Which Java version does the payments service require?"}'

# 4. Create an evaluation run (2 repetitions)
curl -s -X POST "$BASE/api/v1/evaluation-runs" -H 'Content-Type: application/json' -d '{
  "targetSystemId": "'$TARGET_ID'", "datasetId": "'$DATASET_ID'",
  "name": "Demo baseline run", "repetitionsPerTestCase": 2
}'
# → note "id" as RUN_ID

# 5. Start it
curl -s -X POST "$BASE/api/v1/evaluation-runs/$RUN_ID/start"

# 6. Poll until a terminal status (PARTIALLY_COMPLETED is expected — one demo
#    scenario deliberately answers HTTP 500)
curl -s "$BASE/api/v1/evaluation-runs/$RUN_ID"

# 7. JSON summary (aggregation, spec §18)
curl -s "$BASE/api/v1/evaluation-runs/$RUN_ID/summary"

# 7b. Per-evaluator metric results and classified findings (paged)
curl -s "$BASE/api/v1/evaluation-runs/$RUN_ID/metrics?size=50"
curl -s "$BASE/api/v1/evaluation-runs/$RUN_ID/findings?size=50"

# 8. HTML report — open in a browser
open "$BASE/reports/runs/$RUN_ID"

# 9. Human review: list pending, inspect context, submit a decision
curl -s "$BASE/api/v1/reviews/pending?runId=$RUN_ID&size=5"
# → note "metricResultId" of a row as MR_ID
curl -s "$BASE/api/v1/metric-results/$MR_ID/review-context"
curl -s -X POST "$BASE/api/v1/metric-results/$MR_ID/reviews" \
  -H 'Content-Type: application/json' \
  -d '{"decision": "CONFIRMED", "reviewerName": "Demo Reviewer",
       "comment": "Verified against the demo documentation."}'

# 10. Replay: re-judge the stored responses with current evaluators, zero live calls
curl -s -X POST "$BASE/api/v1/evaluation-runs/$RUN_ID/replay" \
  -H 'Content-Type: application/json' -d '{"name": "Demo replay run"}'
# → note "id" as REPLAY_ID, then:
curl -s -X POST "$BASE/api/v1/evaluation-runs/$REPLAY_ID/start"

# 11. Baseline vs candidate comparison (JSON + HTML)
curl -s "$BASE/api/v1/run-comparisons?baselineRunId=$RUN_ID&candidateRunId=$REPLAY_ID"
open "$BASE/reports/comparisons?baselineRunId=$RUN_ID&candidateRunId=$REPLAY_ID"

# Cleanup (optional): delete a run and all its dependent data
curl -s -X DELETE "$BASE/api/v1/evaluation-runs/$REPLAY_ID"
```
