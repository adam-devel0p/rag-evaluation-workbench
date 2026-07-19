#!/usr/bin/env bash
# Deletes ALL locally stored evaluation data by removing the PostgreSQL container and its named
# volume (spec §26; data-retention behavior is documented in docs/data-handling.md). Downloaded
# Ollama models are deliberately kept — they contain no client data and are expensive to re-pull.
set -euo pipefail
cd "$(dirname "$0")/.."

VOLUME=$(docker volume ls --format '{{.Name}}' | grep 'rageval-postgres-data' || true)
if [ -z "${VOLUME}" ]; then
    echo "No rageval-postgres-data volume found — nothing to reset."
    exit 0
fi

echo "This permanently deletes all workbench data (targets, datasets, runs, results, reviews)."
read -r -p "Type 'yes' to continue: " CONFIRM
if [ "${CONFIRM}" != "yes" ]; then
    echo "Aborted — nothing was deleted."
    exit 1
fi

docker compose rm --stop --force postgres
docker volume rm "${VOLUME}"
echo "Postgres data volume '${VOLUME}' removed. 'docker compose up -d postgres' starts fresh;"
echo "Liquibase recreates the schema on the next application start."
