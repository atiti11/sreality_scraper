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
#   RUIAN_LOCAL_XML=/path/to/xml bash run-jar.sh jar2-ruian

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

# If RUIAN_LOCAL_XML is set, mount its directory into the container
if [[ -n "${RUIAN_LOCAL_XML:-}" ]]; then
  XML_DIR="$(dirname "$RUIAN_LOCAL_XML")"
  XML_FILE="$(basename "$RUIAN_LOCAL_XML")"
  EXTRA_MOUNT="$EXTRA_MOUNT -v $XML_DIR:/ruian_local:ro"
  export RUIAN_LOCAL_XML="/ruian_local/$XML_FILE"
fi

# Build a clean temp env file — strips surrounding single quotes from values
# (CI writes .env with single-quoted values for Docker Compose compatibility;
# --env-file passes them literally so we must strip the quotes ourselves)
TMPENV=$(mktemp)
trap "rm -f $TMPENV" EXIT

# Always override these so the JAR uses container names, not localhost
cat > "$TMPENV" << 'FIXED'
PG_HOST=postgres
PG_PORT=5432
MONGO_HOST=mongodb
MONGO_PORT=27017
FIXED

# Append all vars from .env, stripping surrounding single quotes
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
[[ -n "${CSU_MODE:-}"             ]] && echo "CSU_MODE=$CSU_MODE"                           >> "$TMPENV"
[[ -n "${RUIAN_LOCAL_XML:-}"      ]] && echo "RUIAN_LOCAL_XML=$RUIAN_LOCAL_XML"             >> "$TMPENV"
[[ -n "${RUIAN_OVERRIDE_URL:-}"   ]] && echo "RUIAN_OVERRIDE_URL=$RUIAN_OVERRIDE_URL"       >> "$TMPENV"

echo "=== Running $JAR_NAME ==="

# Use eclipse-temurin:21-jre-jammy which includes curl
# shellcheck disable=SC2086
docker run --rm \
  --network scraper-shared-net \
  --env-file "$TMPENV" \
  $EXTRA_MOUNT \
  -v "$DIR/pipeline/jars:/jars:ro" \
  eclipse-temurin:21-jre-jammy \
  java -Xmx512m -jar "$JAR_PATH"

echo "=== $JAR_NAME finished ==="
