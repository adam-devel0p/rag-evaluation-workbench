#!/usr/bin/env bash
# Starts the local infrastructure (PostgreSQL + Ollama via Docker Compose) and then the
# application on the host under the local + demo profiles (spec §26/§31). The evaluator model is
# NOT pulled here — run scripts/pull-models.sh once beforehand if you want LLM evaluators;
# everything deterministic works without it.
set -euo pipefail
cd "$(dirname "$0")/.."

echo "Starting PostgreSQL and Ollama (docker compose)..."
docker compose up -d --wait postgres ollama

echo "Starting the RAG Evaluation Workbench (profiles: local,demo) on http://127.0.0.1:8080 ..."
exec ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,demo
