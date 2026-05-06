#!/usr/bin/env bash
# run-jar.sh — runs a pipeline JAR inside a temporary Docker container
# connected to the scraper network so it can reach postgres and mongodb
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
#   CSU_MODE=full bash run-jar.sh jar3-csu

set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

JAR_NAME="${1:-}"
if [[ -z "$JAR_NAME" ]]; then
  echo "Usage: bash run-jar.sh <jar-name>"
  echo "  e.g. bash run-jar.sh jar2-ruian"
  exit 1
fi

# Resolve JAR path and optional extra mount
if [[ "$JAR_NAME" == "initial-load" ]]; then
  JAR_PATH="/initial/initial-load.jar"
  EXTRA_MOUNT="-v $DIR/initial-load/target:/initial:ro"
else
  JAR_PATH="/jars/${JAR_NAME}.jar"
  EXTRA_MOUNT=""
fi

# Build a clean temp env file for docker --env-file.
# The .env file written by CI has single-quoted values (e.g. PG_PASSWORD='abc@123')
# which Docker Compose handles correctly but --env-file passes literally.
# We strip surrounding single quotes from each value here.
TMPENV=$(mktemp)
trap "rm -f $TMPENV" EXIT

# Always set these overrides first so the JAR uses container names
cat > "$TMPENV" << 'FIXED'
PG_HOST=postgres
PG_PORT=5432
MONGO_HOST=mongodb
MONGO_PORT=27017
FIXED

# Append all vars from .env, stripping surrounding single quotes from values,
# skipping comments, empty lines, and the four host/port vars overridden above.
if [[ -f "$DIR/.env" ]]; then
  grep -v '^\s*#' "$DIR/.env" \
    | grep -v '^\s*$' \
    | grep -v '^PG_HOST=' \
    | grep -v '^PG_PORT=' \
    | grep -v '^MONGO_HOST=' \
    | grep -v '^MONGO_PORT=' \
    | sed "s/='\(.*\)'$/=\1/" \
    >> "$TMPENV"
fi

# Pass optional caller-provided overrides
[[ -n "${INITIAL_LOAD_DRY_RUN:-}" ]] && echo "INITIAL_LOAD_DRY_RUN=$INITIAL_LOAD_DRY_RUN" >> "$TMPENV"
[[ -n "${CSU_MODE:-}" ]]             && echo "CSU_MODE=$CSU_MODE"                           >> "$TMPENV"

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
