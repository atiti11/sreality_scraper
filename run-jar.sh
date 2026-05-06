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
#   bash run-jar.sh initial-load     ← uses initial-load/target/initial-load.jar
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

# Load .env if it exists
ENV_FILE="$DIR/.env"
ENV_ARGS=""
if [[ -f "$ENV_FILE" ]]; then
  # Pass each non-comment, non-empty line as --env
  while IFS= read -r line; do
    [[ "$line" =~ ^#.*$ || -z "$line" ]] && continue
    ENV_ARGS="$ENV_ARGS --env $line"
  done < "$ENV_FILE"
fi

echo "=== Running $JAR_NAME ==="

# shellcheck disable=SC2086
docker run --rm \
  --network scraper-shared-net \
  --env PG_HOST=postgres \
  --env PG_PORT=5432 \
  --env MONGO_HOST=mongodb \
  --env MONGO_PORT=27017 \
  $ENV_ARGS \
  ${EXTRA_MOUNT} \
  -v "$DIR/pipeline/jars:/jars:ro" \
  eclipse-temurin:21-jre \
  java -Xmx512m -jar "$JAR_PATH"

echo "=== $JAR_NAME finished ==="
