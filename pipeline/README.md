# Sreality Pipeline

End-to-end estate data pipeline: Sreality API → MongoDB (staging) → Postgres (enriched).

## Architecture

```
Sreality API
    │
    ▼
JAR 1 (scraper)     ← compares against Postgres, writes deltas to MongoDB
    │
    ▼
MongoDB (queue)     ← staging only; drained after every enrichment run
    │
    ├── JAR 2 (ruian)    ← monthly RUIAN dimension refresh (runs in parallel)
    ├── JAR 3 (csu)      ← yearly CSU stats update        (runs in parallel)
    │
    ▼
JAR 4 (enricher)    ← spatial join, SCD Type 2 write, field change log → Postgres
    │
    ▼
JAR 5 (reporter)    ← queries Postgres, sends Telegram summary
```

## First-time setup

```bash
# 1. Copy and fill in environment variables
cp .env.example .env

# 2. Generate Airflow Fernet key
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
# Paste output into .env → AIRFLOW_FERNET_KEY

# 3. Build JARs (requires Java 21 + Maven locally, or use jar-builder container)
cd pipeline && mvn package -DskipTests

# 4. Start infrastructure
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d postgres mongodb

# 5. Schema is applied automatically by Postgres on first start (schema.sql)

# 6. Start Airflow
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml run --rm airflow-init
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d airflow-webserver airflow-scheduler

# 7. Run RUIAN load first (dimensions must exist before enrichment)
#    Trigger manually in Airflow UI or:
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml \
  run --rm airflow-scheduler airflow tasks test sreality_pipeline ruian_check 2024-01-01

# 8. Run CSU full load ONCE (seeds obec_successor + all historical years)
#    Set CSU_XLSX_URLS in .env first, then:
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml \
  run --rm airflow-scheduler airflow dags trigger csu_full_load

# 9. The main pipeline (sreality_pipeline) runs automatically every 12h.
#    Trigger first run manually via Airflow UI at http://localhost:8080
```

## JAR summary

| JAR | Name | Input | Output |
|-----|------|-------|--------|
| 1 | scraper  | Sreality API | MongoDB (staging queue) |
| 2 | ruian    | CUZK VFR XML | dim_kraj/okres/obec/cast_obce |
| 3 | csu      | CSU XLSX     | fact_obec_stats + obec_successor |
| 4 | enricher | MongoDB      | 14 fact tables + estate_field_changes |
| 5 | reporter | Postgres     | Telegram message |

## Key design decisions

- **MongoDB is a staging queue only** — documents are deleted after successful Postgres write.
- **Change detection via Postgres** — JAR 1 loads (hash_id → content_hash) from Postgres at the
  start of each category run. Only estates with a different hash are written to MongoDB.
- **SCD Type 2** — all fact tables track history via valid_from/valid_to. valid_to IS NULL = current row.
- **field_changes log** — every tracked field change is recorded in estate_field_changes for analytics.
- **Bounding-box spatial join** — no PostGIS needed. CastObce centroids from RUIAN VFR XML,
  expanded by ~500m, used for point-in-bbox lookup with centroid tiebreaker.
- **CSU succession** — OD_KAM sheet maps extinct municipality codes to successors.
  Historical stats are stored under the original obec_id (kept in dim_obec with is_active=false).

## Postgres schema

See `pipeline/schema.sql` for the full schema with comments.

Key tables:
- `dim_kraj / dim_okres / dim_obec / dim_cast_obce` — RUIAN geography hierarchy
- `obec_successor` — municipality merge/split mapping (from CSU OD_KAM)
- `fact_obec_stats` — CSU statistics (obec_id, year) — full history
- `fact_apartment_sale` etc. — 14 estate fact tables split by property type × deal type
- `estate_field_changes` — unified field change log across all fact tables
- `estate_detail` — latest description text per estate (not versioned)
- `ruian_metadata` — freshness tracking for RUIAN snapshots

## Memory footprint (1 CPU / 2 GB Hetzner)

| Service | Limit |
|---------|-------|
| Postgres | 300 MB |
| MongoDB | ~300 MB |
| Airflow webserver | 400 MB |
| Airflow scheduler | 500 MB |
| JAR (during run) | 512 MB heap |
| **Total** | **~2 GB** |

JARs run sequentially (one at a time) so heap is not additive.
