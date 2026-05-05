#!/usr/bin/env bash
# Run from: ~/MATFYZ/magistr/1_semestr/java/sreality_scraper
set -euo pipefail
BASE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$BASE/initial-load/src/main/java/com/sreality/pipeline/initialload"
mkdir -p "$BASE/initial-load/src/main/resources"
echo "initial-load directory structure created under $BASE/initial-load"
