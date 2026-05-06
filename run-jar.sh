#!/usr/bin/env bash
# run-jar.sh — runs a pipeline JAR inside a temporary Docker container
# connected to the scraper network, so it can reach postgres and mongodb
# by their container names.
#
# Usage:
#   bash run-jar.sh jar2-ruian
#   bash run-jar.sh jar3-csu
#   bash run-jar.sh jar4-enricher
#   bash run-jar.sh jar5-reporter
#   bash run-jar.sh initial-load
#
# Optional env overrides:
#   INITIAL_LOAD_DRY_RUN=true bash run-jar.sh initial-load

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

JAR_NAME="${1:-}"
if [[ -z "$JAR_NAME" ]]; then
  echo "Usage: bash run-jar.sh <jar-name>"
  echo "  e.g. bash run-jar.sh jar2-ruian"
  exit 1
fi

# Resolve JAR path
if [[ "$JAR_NAME" == "initial-load" ]]; then
  JAR_PATH="/initial/initial-load.jar"
  EXTRA_MOUNT="-v $DIR/initial-load/target:/initial:ro"
else
  JAR_PATH="/jars/${JAR_NAME}.jar"
  EXTRA_MOUNT=""
fi

# Build a temporary env file for docker --env-file
# This correctly handles passwords with special characters (@, %, *, etc.)
# by passing them as-is without any shell interpretation.
TMPENV=$(mktemp)
trap "rm -f $TMPENV" EXIT

# Always override these so the JAR uses container names, not localhost
cat > "$TMPENV" << ENVEOF
PG_HOST=postgres
PG_PORT=5432
MONGO_HOST=mongodb
MONGO_PORT=27017
ENVEOF

# Append all other vars from .env, skipping comments and empty lines,
# but NOT overwriting the host/port overrides above
if [[ -f "$DIR/.env" ]]; then
  grep -v '^\s*#' "$DIR/.env" \
    | grep -v '^\s*$' \
    | grep -v '^PG_HOST=' \
    | grep -v '^PG_PORT=' \
    | grep -v '^MONGO_HOST=' \
    | grep -v '^MONGO_PORT=' \
    >> "$TMPENV"
fi

# Pass any extra env vars from the calling shell (e.g. INITIAL_LOAD_DRY_RUN)
if [[ -n "${INITIAL_LOAD_DRY_RUN:-}" ]]; then
  echo "INITIAL_LOAD_DRY_RUN=$INITIAL_LOAD_DRY_RUN" >> "$TMPENV"
fi
if [[ -n "${CSU_MODE:-}" ]]; then
  echo "CSU_MODE=$CSU_MODE" >> "$TMPENV"
fi

echo "=== Running $JAR_NAME ==="

# shellcheck disable=SC2086
docker run --rm \
  --network scraper-shared-net \
  --env-file "$TMPENV" \
  $EXTRA_MOUNT \
  -v "$DIR/pipeline/jars:/jars:ro" \
  eclipse-temurin:21-jre \
  java -Xmx512m -jar "$JAR_PATH"

echo "=== $JAR_NAME finished ==="
