"""
sreality_pipeline_dag.py — Main Airflow DAG for the Sreality data pipeline.

Schedule: every 12 hours (0 0,12 * * *)

Task flow:
  scrape → ruian_check → csu_update → enrich → report

  scrape:      JAR 1 — downloads changed/new estates from Sreality API into MongoDB
  ruian_check: JAR 2 — updates RUIAN geography dimensions if a new snapshot is available
  csu_update:  JAR 3 — appends latest CSU statistics year (update mode)
  enrich:      JAR 4 — drains MongoDB queue, spatial join, writes to Postgres fact tables
  report:      JAR 5 — queries Postgres, sends Telegram summary

All JARs are pre-built by the jar-builder container and mounted at /jars.
Each task runs as a BashOperator calling: java -jar /jars/jarN-name.jar

Environment variables are inherited from the airflow-scheduler container,
which gets them from docker-compose.pipeline.yml.
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import ShortCircuitOperator

# ---------------------------------------------------------------------------
# Default args — conservative retry policy for a low-resource server
# ---------------------------------------------------------------------------
default_args = {
    "owner":            "pipeline",
    "depends_on_past":  False,
    "retries":          1,
    "retry_delay":      timedelta(minutes=5),
    "email_on_failure": False,
    "email_on_retry":   False,
}

# ---------------------------------------------------------------------------
# Java command template
# -Xmx sets max heap. Keep low to share 2GB RAM with Postgres + Mongo + Airflow.
# ---------------------------------------------------------------------------
JAVA_CMD = "java -Xmx512m -jar /jars/{jar}.jar"

# ---------------------------------------------------------------------------
# DAG
# ---------------------------------------------------------------------------
with DAG(
    dag_id="sreality_pipeline",
    description="Sreality estate data pipeline: scrape → RUIAN → CSU → enrich → report",
    schedule_interval="0 0,12 * * *",   # 00:00 and 12:00 every day
    start_date=datetime(2024, 1, 1),
    catchup=False,
    max_active_runs=1,                  # never run two pipeline instances in parallel
    default_args=default_args,
    tags=["pipeline", "sreality"],
) as dag:

    # -------------------------------------------------------------------------
    # Task 1: Scrape Sreality API → MongoDB staging queue
    # -------------------------------------------------------------------------
    scrape = BashOperator(
        task_id="scrape",
        bash_command=JAVA_CMD.format(jar="jar1-scraper"),
        env={
            **os.environ,
            "PER_PAGE":          "{{ var.value.get('PER_PAGE', '100') }}",
            "REQUEST_DELAY_MS":  "{{ var.value.get('REQUEST_DELAY_MS', '500') }}",
        },
        # Scraping can take up to 3 hours for a full run
        execution_timeout=timedelta(hours=4),
    )

    # -------------------------------------------------------------------------
    # Task 2: RUIAN dimension refresh (runs weekly — skips itself if up to date)
    # -------------------------------------------------------------------------
    ruian_check = BashOperator(
        task_id="ruian_check",
        bash_command=JAVA_CMD.format(jar="jar2-ruian"),
        # JAR 2 exits 0 with "already current" log if no update needed.
        # RUIAN is large — allow up to 30 minutes for download + parse.
        execution_timeout=timedelta(minutes=30),
    )

    # -------------------------------------------------------------------------
    # Task 3: CSU statistics update (incremental — appends new year rows only)
    # -------------------------------------------------------------------------
    csu_update = BashOperator(
        task_id="csu_update",
        bash_command=JAVA_CMD.format(jar="jar3-csu"),
        env={
            **os.environ,
            "CSU_MODE": "update",
            # CSU_XLSX_URLS is set in docker-compose from .env
        },
        execution_timeout=timedelta(minutes=15),
    )

    # -------------------------------------------------------------------------
    # Task 4: Enrich MongoDB queue → Postgres fact tables
    # -------------------------------------------------------------------------
    enrich = BashOperator(
        task_id="enrich",
        bash_command=JAVA_CMD.format(jar="jar4-enricher"),
        # Enrichment batch size depends on how many estates changed since last run.
        # Typically fast (minutes), but allow 2h for large initial runs.
        execution_timeout=timedelta(hours=2),
    )

    # -------------------------------------------------------------------------
    # Task 5: Generate and send Telegram report
    # -------------------------------------------------------------------------
    report = BashOperator(
        task_id="report",
        bash_command=JAVA_CMD.format(jar="jar5-reporter"),
        execution_timeout=timedelta(minutes=5),
        # Report failure should not fail the whole DAG run
        trigger_rule="all_done",
    )

    # -------------------------------------------------------------------------
    # Dependencies
    #
    # scrape must complete before enrich (MongoDB queue must be populated).
    # ruian_check and csu_update can run in parallel after scrape,
    # but both must finish before enrich (dimension tables must be current).
    # report runs last regardless of whether enrich succeeded (trigger_rule=all_done).
    # -------------------------------------------------------------------------
    scrape >> [ruian_check, csu_update] >> enrich >> report
