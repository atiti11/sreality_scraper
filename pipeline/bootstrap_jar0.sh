#!/usr/bin/env bash
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$DIR/jar0-initial-load/src/main/java/com/sreality/pipeline/initialload"
mkdir -p "$DIR/jar0-initial-load/src/main/resources"
echo "jar0-initial-load directories created."
