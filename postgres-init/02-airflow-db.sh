#!/usr/bin/env bash
# postgres-init/02-airflow-db.sh
#
# Creates a dedicated database for Airflow metadata.
# Runs after 01-schema.sh on first container start.
# The sreality user (POSTGRES_USER) is granted full access.
set -e

psql -v ON_ERROR_STOP=1 \
     --username "$POSTGRES_USER" \
     --dbname   "$POSTGRES_DB" \
<< SQL
CREATE DATABASE airflow_db;
GRANT ALL PRIVILEGES ON DATABASE airflow_db TO "$POSTGRES_USER";
SQL

echo "=== airflow_db created ==="
