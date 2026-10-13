#!/usr/bin/env bash
# Runs the journey planner locally against a real LLM.
#
# The API key is read from the environment or from a local `.env` file
# (see .env.example). `.env` is gitignored: the key never enters the
# repository, the Docker image, or CI logs.
#
# Usage:
#   cp .env.example .env   # fill in your key once
#   ./scripts/run-planner.sh
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -f .env ]; then
  # shellcheck disable=SC1091
  set -a; source .env; set +a
fi

case "${LLM_PROVIDER:-}" in
  ollama|lmstudio) ;;
  *)
    if [ -z "${LLM_API_KEY:-}" ]; then
      echo "LLM_API_KEY is not set." >&2
      echo "Copy .env.example to .env and fill in your key, or export LLM_API_KEY." >&2
      exit 1
    fi
    ;;
esac

exec ./gradlew :journey-engine:runPlanner --no-daemon -q
