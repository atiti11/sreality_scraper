#!/usr/bin/env bash
# copy_jars.sh — copies built pipeline JARs to pipeline/jars/ for Docker mounting
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"

mkdir -p "$DIR/jars"

for jar in jar1-scraper jar2-ruian jar3-csu jar4-enricher jar5-reporter; do
    src="$DIR/$jar/target/$jar.jar"
    if [ -f "$src" ]; then
        cp "$src" "$DIR/jars/"
        echo "Copied: $jar.jar"
    else
        echo "MISSING: $src — run 'mvn package -DskipTests' first"
        exit 1
    fi
done

echo ""
echo "All JARs copied to $DIR/jars/"
echo "Restart airflow-scheduler to pick up new JARs if already running."
