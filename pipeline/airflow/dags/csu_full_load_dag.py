"""
csu_full_load_dag.py — One-time full CSU historical data load.

Run manually ONCE after the RUIAN dimensions are loaded.
Loads all historical years of CSU statistics and seeds the obec_successor table
from OD_KAM sheets.

After this DAG succeeds, disable it — subsequent loads use the incremental
update in sreality_pipeline_dag.py (task: csu_update).

Trigger manually:
  airflow dags trigger csu_full_load
"""

from __future__ import annotations

from datetime import datetime, timedelta
from airflow import DAG
from airflow.operators.bash import BashOperator

default_args = {
    "owner":            "pipeline",
    "depends_on_past":  False,
    "retries":          0,
    "email_on_failure": False,
}

with DAG(
    dag_id="csu_full_load",
    description="One-time full CSU historical load — run once then disable",
    schedule_interval=None,   # manual trigger only
    start_date=datetime(2024, 1, 1),
    catchup=False,
    max_active_runs=1,
    default_args=default_args,
    tags=["pipeline", "csu", "one-time"],
) as dag:

    full_load = BashOperator(
        task_id="csu_full_load",
        bash_command="java -Xmx512m -jar /jars/jar3-csu.jar",
        env={
            "CSU_MODE": "full",
            # CSU_XLSX_URLS must be set in .env with all historical XLSX URLs
            # comma-separated, e.g.:
            # CSU_XLSX_URLS=https://...CZ020A.xlsx,https://...CZ020B.xlsx,...
        },
        execution_timeout=timedelta(hours=2),
    )
