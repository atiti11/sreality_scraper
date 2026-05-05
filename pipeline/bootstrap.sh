#!/usr/bin/env bash
# bootstrap.sh — creates all pipeline directory structure
# Run once from: ~/MATFYZ/magistr/1_semestr/java/sreality_scraper/pipeline
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
echo "Creating pipeline structure under $DIR"

mkdir -p "$DIR/shared/src/main/java/com/sreality/pipeline/shared/db"
mkdir -p "$DIR/shared/src/main/java/com/sreality/pipeline/shared/model"
mkdir -p "$DIR/jar1-scraper/src/main/java/com/sreality/pipeline/scraper/db"
mkdir -p "$DIR/jar1-scraper/src/main/resources"
mkdir -p "$DIR/jar2-ruian/src/main/java/com/sreality/pipeline/ruian/model"
mkdir -p "$DIR/jar2-ruian/src/main/java/com/sreality/pipeline/ruian/extract"
mkdir -p "$DIR/jar2-ruian/src/main/java/com/sreality/pipeline/ruian/load"
mkdir -p "$DIR/jar2-ruian/src/main/resources"
mkdir -p "$DIR/jar3-csu/src/main/java/com/sreality/pipeline/csu/model"
mkdir -p "$DIR/jar3-csu/src/main/java/com/sreality/pipeline/csu/extract"
mkdir -p "$DIR/jar3-csu/src/main/java/com/sreality/pipeline/csu/load"
mkdir -p "$DIR/jar3-csu/src/main/resources"
mkdir -p "$DIR/jar4-enricher/src/main/java/com/sreality/pipeline/enricher/model"
mkdir -p "$DIR/jar4-enricher/src/main/java/com/sreality/pipeline/enricher/spatial"
mkdir -p "$DIR/jar4-enricher/src/main/java/com/sreality/pipeline/enricher/load"
mkdir -p "$DIR/jar4-enricher/src/main/resources"
mkdir -p "$DIR/jar5-reporter/src/main/java/com/sreality/pipeline/reporter"
mkdir -p "$DIR/jar5-reporter/src/main/resources"
mkdir -p "$DIR/airflow/dags"

echo "All directories created. Run: bash write_sources.sh"
