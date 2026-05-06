"""
sreality_pipeline_dag.py — Main Airflow DAG for the Sreality data pipeline.

Schedule: every 12 hours (0 0,12 * * *)

Task flow:
  scrape
    ↓
  [ruian_download,  csu_download]   ← curl downloads run in parallel
    ↓                  ↓
  ruian_check      csu_update       ← JARs process the pre-downloaded files
    ↓                  ↓
         enrich
            ↓
          report

Downloads use curl (--retry 5 --continue-at -) for reliable large-file handling.
RUIAN is ~332 MB, CSU files are smaller but also benefit from retry logic.

Downloaded files are written to /tmp inside the Airflow scheduler container
and passed to the JAR tasks via RUIAN_LOCAL_XML / CSU_LOCAL_FILES env vars.
Files are cleaned up after the JAR task completes.
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator

# ---------------------------------------------------------------------------
# Default args
# ---------------------------------------------------------------------------
default_args = {
    "owner":            "pipeline",
    "depends_on_past":  False,
    "retries":          2,
    "retry_delay":      timedelta(minutes=5),
    "email_on_failure": False,
    "email_on_retry":   False,
}

JAVA_CMD = "java -Xmx512m -jar /jars/{jar}.jar"

# ---------------------------------------------------------------------------
# RUIAN URL helper — last day of previous month
# Airflow templating: logical_date gives the execution date
# ---------------------------------------------------------------------------
RUIAN_DOWNLOAD_CMD = """
set -e

# Determine URL: try current month first, fall back to previous months
for MONTHS_BACK in 0 1 2 3 4 5; do
    YEAR=$(date -d "$(date +%Y-%m-01) -${MONTHS_BACK} month" +%Y)
    MONTH=$(date -d "$(date +%Y-%m-01) -${MONTHS_BACK} month" +%m)
    LAST_DAY=$(date -d "${YEAR}-${MONTH}-01 +1 month -1 day" +%d)
    URL="https://services.cuzk.gov.cz/vfr/${YEAR}${MONTH}/${YEAR}${MONTH}${LAST_DAY}_ST_UKSG.xml.zip"
    echo "Trying RUIAN URL: ${URL}"
    HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" --head "${URL}" 2>/dev/null || echo "000")
    if [ "${HTTP_CODE}" = "200" ] || [ "${HTTP_CODE}" = "405" ]; then
        echo "Found available RUIAN: ${URL}"
        RUIAN_URL="${URL}"
        RUIAN_FILENAME="${YEAR}${MONTH}${LAST_DAY}_ST_UKSG"
        break
    fi
    echo "HTTP ${HTTP_CODE} — trying previous month"
done

if [ -z "${RUIAN_URL:-}" ]; then
    echo "ERROR: No RUIAN snapshot found in last 6 months"
    exit 1
fi

ZIP_PATH="/tmp/${RUIAN_FILENAME}.zip"
XML_PATH="/tmp/${RUIAN_FILENAME}.xml"

# Skip download if already cached from a previous run today
if [ -f "${XML_PATH}" ]; then
    echo "RUIAN XML already cached at ${XML_PATH} — skipping download"
    echo "${XML_PATH}" > /tmp/ruian_xml_path.txt
    exit 0
fi

echo "Downloading ${RUIAN_URL} → ${ZIP_PATH}"
curl \
    --retry 5 \
    --retry-delay 10 \
    --retry-max-time 600 \
    --continue-at - \
    --location \
    --fail \
    --progress-bar \
    -o "${ZIP_PATH}" \
    "${RUIAN_URL}"

echo "Extracting ${ZIP_PATH}"
unzip -o "${ZIP_PATH}" -d /tmp/
rm -f "${ZIP_PATH}"

echo "RUIAN XML ready: ${XML_PATH}"
ls -lh "${XML_PATH}"

# Write path for the ruian_check task to read
echo "${XML_PATH}" > /tmp/ruian_xml_path.txt
"""

RUIAN_JAR_CMD = """
set -e
XML_PATH=$(cat /tmp/ruian_xml_path.txt 2>/dev/null || echo "")
if [ -z "${XML_PATH}" ] || [ ! -f "${XML_PATH}" ]; then
    echo "ERROR: RUIAN XML path not found. Run ruian_download first."
    exit 1
fi
echo "Using RUIAN_LOCAL_XML=${XML_PATH}"
export RUIAN_LOCAL_XML="${XML_PATH}"
""" + JAVA_CMD.format(jar="jar2-ruian") + """
# Clean up after successful load
rm -f "${XML_PATH}"
rm -f /tmp/ruian_xml_path.txt
echo "RUIAN XML cleaned up."
"""

# ---------------------------------------------------------------------------
# CSU download command
# CSU_XLSX_URLS is comma-separated list of XLSX URLs from .env / docker-compose
# ---------------------------------------------------------------------------
CSU_DOWNLOAD_CMD = """
set -e

if [ -z "${CSU_XLSX_URLS:-}" ]; then
    echo "CSU_XLSX_URLS not set — skipping CSU download"
    echo "" > /tmp/csu_local_files.txt
    exit 0
fi

mkdir -p /tmp/csu_downloads/
LOCAL_FILES=""

echo "${CSU_XLSX_URLS}" | tr ',' '\\n' | while IFS= read -r URL; do
    URL=$(echo "${URL}" | tr -d '[:space:]')
    [ -z "${URL}" ] && continue

    FILENAME=$(basename "${URL}")
    DEST="/tmp/csu_downloads/${FILENAME}"

    if [ -f "${DEST}" ]; then
        echo "Already cached: ${DEST} — skipping"
    else
        echo "Downloading CSU: ${URL} → ${DEST}"
        curl \
            --retry 5 \
            --retry-delay 10 \
            --retry-max-time 300 \
            --continue-at - \
            --location \
            --fail \
            --progress-bar \
            -o "${DEST}" \
            "${URL}"
        echo "Downloaded: ${DEST} ($(ls -lh ${DEST} | awk '{print $5}'))"
    fi
done

# Build comma-separated list of local files for the JAR
LOCAL_FILES=$(ls /tmp/csu_downloads/*.xlsx 2>/dev/null | tr '\\n' ',' | sed 's/,$//')
echo "${LOCAL_FILES}" > /tmp/csu_local_files.txt
echo "CSU local files: ${LOCAL_FILES}"
"""

CSU_JAR_CMD = """
set -e
LOCAL_FILES=$(cat /tmp/csu_local_files.txt 2>/dev/null || echo "")

if [ -z "${LOCAL_FILES}" ]; then
    echo "No CSU local files — using CSU_XLSX_URLS directly"
    # Fall back to original URL-based download in the JAR
    export CSU_MODE=update
else
    echo "Using CSU_LOCAL_FILES=${LOCAL_FILES}"
    export CSU_LOCAL_FILES="${LOCAL_FILES}"
    export CSU_MODE=update
fi
""" + JAVA_CMD.format(jar="jar3-csu") + """
# Clean up after successful load
rm -rf /tmp/csu_downloads/
rm -f /tmp/csu_local_files.txt
echo "CSU files cleaned up."
"""

# ---------------------------------------------------------------------------
# DAG
# ---------------------------------------------------------------------------
with DAG(
    dag_id="sreality_pipeline",
    description="Sreality estate data pipeline: scrape → download → RUIAN/CSU → enrich → report",
    schedule_interval="0 0,12 * * *",
    start_date=datetime(2024, 1, 1),
    catchup=False,
    max_active_runs=1,
    default_args=default_args,
    tags=["pipeline", "sreality"],
) as dag:

    # -------------------------------------------------------------------------
    # Task 1: Scrape Sreality API → MongoDB staging queue
    # -------------------------------------------------------------------------
    scrape = BashOperator(
        task_id="scrape",
        bash_command=JAVA_CMD.format(jar="jar1-scraper"),
        execution_timeout=timedelta(hours=4),
    )

    # -------------------------------------------------------------------------
    # Task 2a: Download RUIAN zip via curl (retry + resume)
    # -------------------------------------------------------------------------
    ruian_download = BashOperator(
        task_id="ruian_download",
        bash_command=RUIAN_DOWNLOAD_CMD,
        # Allow plenty of time — 332 MB on a slow link can take a while
        execution_timeout=timedelta(minutes=45),
        retries=3,
        retry_delay=timedelta(minutes=2),
    )

    # -------------------------------------------------------------------------
    # Task 2b: Load RUIAN XML into Postgres dimension tables
    # -------------------------------------------------------------------------
    ruian_check = BashOperator(
        task_id="ruian_check",
        bash_command=RUIAN_JAR_CMD,
        execution_timeout=timedelta(minutes=30),
    )

    # -------------------------------------------------------------------------
    # Task 3a: Download CSU XLSX files via curl
    # -------------------------------------------------------------------------
    csu_download = BashOperator(
        task_id="csu_download",
        bash_command=CSU_DOWNLOAD_CMD,
        execution_timeout=timedelta(minutes=30),
        retries=3,
        retry_delay=timedelta(minutes=2),
    )

    # -------------------------------------------------------------------------
    # Task 3b: Load CSU stats into Postgres
    # -------------------------------------------------------------------------
    csu_update = BashOperator(
        task_id="csu_update",
        bash_command=CSU_JAR_CMD,
        execution_timeout=timedelta(minutes=15),
    )

    # -------------------------------------------------------------------------
    # Task 4: Enrich MongoDB queue → Postgres fact tables
    # -------------------------------------------------------------------------
    enrich = BashOperator(
        task_id="enrich",
        bash_command=JAVA_CMD.format(jar="jar4-enricher"),
        execution_timeout=timedelta(hours=2),
    )

    # -------------------------------------------------------------------------
    # Task 5: Generate and send Telegram report
    # -------------------------------------------------------------------------
    report = BashOperator(
        task_id="report",
        bash_command=JAVA_CMD.format(jar="jar5-reporter"),
        execution_timeout=timedelta(minutes=5),
        trigger_rule="all_done",
    )

    # -------------------------------------------------------------------------
    # Dependencies
    #
    # scrape runs first (populates MongoDB queue).
    # ruian_download and csu_download run in parallel after scrape.
    # Their respective JAR tasks run after the download completes.
    # enrich runs after both ruian_check and csu_update are done.
    # report runs last regardless of enrich success.
    # -------------------------------------------------------------------------
    scrape >> [ruian_download, csu_download]
    ruian_download >> ruian_check
    csu_download   >> csu_update
    [ruian_check, csu_update] >> enrich >> report
