# Pipeline Build Instructions

## Prerequisites

- Java 21
- Maven 3.9+
- Docker + Docker Compose

## Step 1 — Install the original scraper into local Maven repo

The pipeline JARs depend on classes from the original scraper (EstateDocumentBuilder,
CategoryConfig, AppConfig, MongoRepository, etc.). Maven needs it in the local .m2 cache.

```bash
# From the root of the project (sreality_scraper/)
mvn install -DskipTests
```

This installs `com.sreality:sreality-scraper:1.0-SNAPSHOT` into `~/.m2`.
Only needs to be done once, or when you change the original scraper code.

## Step 2 — Build all pipeline JARs

```bash
cd pipeline
mvn package -DskipTests
```

Produces in each module's `target/` directory:
- `jar1-scraper/target/jar1-scraper.jar`
- `jar2-ruian/target/jar2-ruian.jar`
- `jar3-csu/target/jar3-csu.jar`
- `jar4-enricher/target/jar4-enricher.jar`
- `jar5-reporter/target/jar5-reporter.jar`

## Step 3 — Copy JARs to a shared location (for Docker)

```bash
mkdir -p pipeline/jars
cp pipeline/jar1-scraper/target/jar1-scraper.jar     pipeline/jars/
cp pipeline/jar2-ruian/target/jar2-ruian.jar         pipeline/jars/
cp pipeline/jar3-csu/target/jar3-csu.jar             pipeline/jars/
cp pipeline/jar4-enricher/target/jar4-enricher.jar   pipeline/jars/
cp pipeline/jar5-reporter/target/jar5-reporter.jar   pipeline/jars/
```

Or use the helper script:
```bash
bash pipeline/copy_jars.sh
```

## Step 4 — Configure environment

```bash
cp .env.example .env
# Edit .env — fill in PG_PASSWORD, TELEGRAM_BOT_TOKEN, CSU_XLSX_URLS etc.

# Generate Airflow Fernet key:
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
# Paste into .env → AIRFLOW_FERNET_KEY
```

## Step 5 — Start infrastructure

```bash
# Start MongoDB + Postgres (schema applied automatically on first start)
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d mongodb postgres

# Wait for Postgres to be healthy, then initialise Airflow
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml run --rm airflow-init

# Start Airflow
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d airflow-webserver airflow-scheduler
```

## Step 6 — One-time data loads (run in order)

```bash
# 6a. Load RUIAN geography dimensions (required before enricher can run)
java -jar pipeline/jars/jar2-ruian.jar

# 6b. Full CSU historical load (run once; subsequent runs use incremental update)
# Set CSU_XLSX_URLS in .env first
java -jar pipeline/jars/jar3-csu.jar
# Or trigger via Airflow: airflow dags trigger csu_full_load
```

## Step 7 — Trigger first pipeline run

Open Airflow UI at http://localhost:8080 (admin/admin by default).
Enable and trigger the `sreality_pipeline` DAG.

Subsequent runs happen automatically at 00:00 and 12:00 every day.

## Rebuilding after code changes

```bash
# If you changed original scraper code:
mvn install -DskipTests                     # reinstall scraper into .m2
cd pipeline && mvn package -DskipTests      # rebuild pipeline JARs
bash pipeline/copy_jars.sh                  # copy to jars/ dir
# Restart affected Airflow containers to pick up new JARs

# If you changed only pipeline code (no changes to original scraper):
cd pipeline && mvn package -DskipTests
bash pipeline/copy_jars.sh
```
