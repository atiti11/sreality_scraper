# CLAUDE.md — Sreality Scraper Project Context

This file gives a new Claude session full context about this project so work
can continue without re-explaining decisions already made.

---

## What This Project Is

A Java + Python data pipeline that:
1. Scrapes Czech real estate listings from the Sreality API
2. Stores raw data in MongoDB (staging queue)
3. Enriches and loads into Postgres (source of truth)
4. Runs on a Hetzner server (1 CPU / 2 GB RAM) orchestrated by Airflow
5. Sends a Telegram summary report after each run

It also serves three academic purposes simultaneously:
- Java zápočtový program (MFF UK) — core logic must be in Java
- Data na webu homework (parts 3–6 still TODO) — RDF triplification of the data
- Úvod do datového inženýrství — pipeline/ETL design

---

## Repository Structure

```
sreality_scraper/
├── src/                          # Original scraper (Java, existing code)
├── pipeline/                     # New pipeline JARs (built by CI)
│   ├── pom.xml                   # Parent Maven POM for all 5 pipeline JARs
│   ├── schema.sql                # Postgres schema (reference copy)
│   ├── shared/                   # Shared library: PostgresConnectionPool, TableRouter, ContentHasher
│   ├── jar1-scraper/             # Sreality API → MongoDB staging queue
│   ├── jar2-ruian/               # RUIAN VFR XML → dim_* geography tables
│   ├── jar3-csu/                 # CSU XLSX → fact_obec_stats + obec_successor
│   ├── jar4-enricher/            # MongoDB → Postgres fact tables (spatial join, SCD)
│   ├── jar5-reporter/            # Postgres → Telegram report
│   ├── jars/                     # Built JARs land here (via copy_jars.sh / CI)
│   └── airflow/dags/             # Airflow DAG definitions (Python)
├── initial-load/                 # SEPARATE one-time load: all MongoDB → Postgres
│   └── src/.../InitialLoadMain   # Non-destructive, idempotent, uses _first_seen_at
├── postgres-init/
│   └── 01-schema.sh              # Full schema applied by Postgres container on first start
├── mongo-init/                   # MongoDB init scripts (existing)
├── docker-compose.yml            # Base: MongoDB + scraper
├── docker-compose.pipeline.yml   # Extension: Postgres + Airflow (Java 21 image)
├── Dockerfile                    # Original scraper image
├── Dockerfile.airflow            # Airflow + Java 21 JRE
├── .github/workflows/deploy.yml  # CI: build JARs on runner, SCP to server, restart
├── run-jar.sh                    # Helper: runs a JAR inside Docker network
└── .env.example                  # All env var definitions
```

---

## The 5-JAR Pipeline Architecture

### Data flow
```
Sreality API
    ↓
JAR 1 (scraper) — compares (hash_id, content_hash) against Postgres bulk lookup
    ↓                only changed/new estates written to MongoDB
MongoDB (staging queue — small, fast-rotating)
    ↓                ↓
JAR 2 (ruian)   JAR 3 (csu)   ← run in parallel after scrape
    ↓                ↓
JAR 4 (enricher) — reads MongoDB, spatial join, SCD Type 2 write, deletes from Mongo
    ↓
Postgres (source of truth, forever)
    ↓
JAR 5 (reporter) — queries Postgres, sends Telegram
```

### Airflow DAG (`sreality_pipeline`, every 12h)
```
scrape → [ruian_check, csu_update] → enrich → report
```

### JAR responsibilities

| JAR | Main class | Input | Output | Key dependency |
|-----|-----------|-------|--------|---------------|
| shared | — | — | — | Used by all JARs |
| jar1-scraper | ScraperMain | Sreality API | MongoDB staging | PostgresLookup for change detection |
| jar2-ruian | RuianMain | CUZK VFR XML (~300-500MB) | dim_kraj/okres/obec/cast_obce | Downloads monthly, freshness check via URL date |
| jar3-csu | CsuMain | CSU XLSX files | fact_obec_stats, obec_successor | Modes: full (once) / update (yearly) |
| jar4-enricher | EnricherMain | MongoDB collections | 14 fact tables, estate_field_changes, estate_detail | SpatialJoiner, EnricherLoader |
| jar5-reporter | ReporterMain | Postgres | Telegram message | ReportQuery, TelegramReporter |

---

## Postgres Schema

### Geography dimensions (from RUIAN)
```
dim_kraj → dim_okres → dim_obec → dim_cast_obce
```
- `dim_cast_obce` has bbox (min/max lat/lon) and centroid — used for spatial join
- Spatial join: point-in-bbox, tiebreak by nearest centroid. No PostGIS needed.
- Extinct municipalities stay in `dim_obec` with `is_active=false` so historical FK references remain valid
- `obec_successor` maps extinct `obec_kod` → current `obec_kod` (from CSU OD_KAM sheet)

### Fact tables (14 total)
Split by **property type × deal type**:

| | Sale | Rent | Auction |
|---|---|---|---|
| Apartment | ✅ | ✅ | ✅ |
| House | ✅ | ✅ | ✅ |
| Land | ✅ | ✅ | ✅ |
| Commercial | ✅ | ✅ | ✅ |
| Other | ✅ | ✅ | — |

All fact tables use **SCD Type 2**: `valid_from` / `valid_to` (NULL = current row), `is_active`, `content_hash`.

### Key side tables
- `fact_obec_stats` — CSU statistics per (obec_id, year), full history including extinct municipalities
- `estate_field_changes` — unified log of every tracked field change across all 14 fact tables
- `estate_detail` — latest description/locality text per estate (not versioned, always overwritten)
- `ruian_metadata` — single-row freshness tracker for RUIAN snapshots

### Schema applied by
`postgres-init/01-schema.sh` — runs automatically on first Postgres container start.
Also seeds `dim_date` for 2020–2035.

---

## Key Design Decisions (already settled — do not revisit)

### MongoDB role
MongoDB is a **pure staging queue**. Documents are deleted by the enricher immediately after successful Postgres write. The regular pipeline keeps MongoDB nearly empty (only estates that changed since last run). The initial-load is an exception — it does not delete.

### Change detection
JAR 1 bulk-loads `(hash_id → content_hash)` from Postgres at the start of each category run into a `HashMap`. Each API estate is hashed (FNV-1a 64-bit, in `ContentHasher`) and compared. Only deltas hit MongoDB.

### Content hash fields
Price + usable_area + plot_area + garden_area + sub_category + all labels (English) + all boolean features. Excludes cosmetic fields (image count, description text).

### Energy label
`energy_efficiency_label` (English, from `appendRecommendationsData()`) is used, NOT `energy_rating_label` (Czech, from `appendItems()`). This is fixed throughout EnricherLoader and ContentHasher.

### Spatial join precision
Bounding box (~500m expansion from centroid) with centroid tiebreaker. Good enough for obec/cast_obce level. Known limitation: bbox overlap in dense urban areas (Prague). PostGIS not needed or used.

### CSU municipality succession
`obec_successor` table seeded from CSU `OD_KAM` sheet (loaded by JAR 3 in `full` mode). Extinct municipality stats are stored under their own `obec_id` and rolled up at query time via `obec_successor`. The `CsuLoader` resolves `obec_kod` → `obec_id` with a fallback through `obec_successor`.

### SCD Type 2 hooks in EnricherLoader
`EnricherLoader` has three `protected` hook methods that `InitialEnricher` overrides:
- `resolveValidFrom(doc)` → uses `_first_seen_at` instead of today
- `resolveValidTo(doc)` → closes rows for inactive estates using `_last_seen_at`
- `resolveIsActive(doc)` → reads MongoDB `active` field instead of defaulting to true

---

## Infrastructure

### Server
Hetzner, 1 CPU / 2 GB RAM. All services run in Docker.

### Memory allocation
| Service | Limit |
|---------|-------|
| Postgres | 300 MB |
| MongoDB | ~300 MB |
| Airflow webserver | 400 MB |
| Airflow scheduler | 500 MB |
| JAR (during run) | 512 MB heap (`-Xmx512m`) |

JARs run sequentially via Airflow so heap is not additive.

### Airflow configuration (tuned for low resources)
```
LocalExecutor (no Redis/Celery)
PARSING_PROCESSES=1
MAX_DAGRUNS_TO_CREATE_PER_LOOP=2
PARALLELISM=4
MAX_ACTIVE_TASKS_PER_DAG=2
```

### Docker compose files
- `docker-compose.yml` — MongoDB + original scraper (existing, unchanged)
- `docker-compose.pipeline.yml` — extends base with Postgres + Airflow
- Both must be specified together: `-f docker-compose.yml -f docker-compose.pipeline.yml`
- Airflow uses `Dockerfile.airflow` (base apache/airflow + openjdk-21-jre-headless)

### Running JARs on server
Use `run-jar.sh` which wraps `docker run --rm --network scraper-shared-net eclipse-temurin:21-jre`:
```bash
bash run-jar.sh jar2-ruian
bash run-jar.sh initial-load
INITIAL_LOAD_DRY_RUN=true bash run-jar.sh initial-load
```

---

## CI/CD (GitHub Actions)

`.github/workflows/deploy.yml` — two jobs:

**Job 1: build** (on GitHub runner — free CPU)
1. Install original scraper into `.m2`
2. Build pipeline JARs (`mvn package -DskipTests`)
3. Install pipeline modules into `.m2`
4. Build initial-load JAR
5. Stage + upload as artifacts

**Job 2: deploy** (order matters to avoid git conflicts)
1. SSH: `git pull` + write `.env`  ← must happen BEFORE SCP
2. SCP: copy `pipeline/jars/*.jar` to server
3. SCP: copy `initial-load/target/initial-load.jar` to server
4. SSH: restart Docker services

**GitHub Secrets required:**
`DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH`,
`MONGO_ROOT_USERNAME`, `MONGO_ROOT_PASSWORD`, `MONGO_USERNAME`, `MONGO_PASSWORD`,
`PG_USERNAME`, `PG_PASSWORD`,
`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`,
`AIRFLOW_FERNET_KEY`, `AIRFLOW_SECRET_KEY`, `AIRFLOW_PASSWORD`

**GitHub Variables required:**
`MONGO_HOST`, `MONGO_PORT`, `MONGO_HOST_PORT`, `MONGO_DATABASE`,
`PG_HOST`, `PG_PORT`, `PG_HOST_PORT`, `PG_DATABASE`, `PG_SCHEMA`,
`SREALITY_BASE_URL`, `PER_PAGE`, `MAX_ESTATES`,
`HTTP_CONNECT_TIMEOUT_MS`, `HTTP_READ_TIMEOUT_MS`, `REQUEST_DELAY_MS`,
`AIRFLOW_USER`, `AIRFLOW_PORT`, `CSU_XLSX_URLS`, `CSU_YEAR`

---

## First-time Server Setup (one-time, manual)

Prerequisites: GitHub Actions deploy has run successfully at least once.

```bash
# On the server
cd $DEPLOY_PATH

# 1. Start MongoDB + Postgres
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d mongodb postgres

# 2. Init Airflow
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml run --rm airflow-init
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d airflow-webserver airflow-scheduler

# 3. Load RUIAN (required before enricher can run)
bash run-jar.sh jar2-ruian

# 4. Initial MongoDB → Postgres load (non-destructive)
INITIAL_LOAD_DRY_RUN=true bash run-jar.sh initial-load   # check counts first
bash run-jar.sh initial-load                               # full load

# 5. Verify in Postgres (see initial-load/README.md for queries)

# 6. CSU full load (set CSU_XLSX_URLS in .env first)
bash run-jar.sh jar3-csu   # with CSU_MODE=full in env

# 7. Enable sreality_pipeline DAG in Airflow UI (http://server:8080)
```

---

## What Is NOT Done Yet (TODO)

### Data na webu homework (parts 3–6)
The assignment (`pipeline/` contains the triplification scripts from parts 1–2 in
`C:\Users\PC\MATFYZ\magistr\2_semestr\data_na__webu\homework`):
- **Part 3**: Silk link discovery (internal + external LOD linking)
- **Part 4**: SPARQL queries (2× SELECT, 2× CONSTRUCT, 1× ASK, 1× DESCRIBE)
- **Part 5**: Java web app with Jena/RDF4J + SHACL shapes (10+ shapes)
- **Part 6**: HTML presentation page

The pipeline data (Postgres) will feed this — but the RDF/Semantic Web layer is separate.

### CsuXlsxParser column indices
`CsuXlsxParser.java` has placeholder column constants (`COL_POPULATION=1`, etc.).
These must be verified against a real XLSX file before the first CSU load:
```bash
# Open one XLSX and check actual column positions for:
# population, births, deaths, migration_balance, marriages, divorces, unemployment_pct
```

### MongoDB cleanup after initial load
After verifying the initial load in Postgres, delete MongoDB documents:
```js
// In mongosh on the server
const cols = ["apartments_sale","apartments_rent","apartments_auction",
  "houses_sale","houses_rent","houses_auction","land_sale","land_rent",
  "land_auction","commercial_sale","commercial_rent","commercial_auction",
  "other_sale","other_rent","other_auction"];
cols.forEach(c => { const r = db[c].deleteMany({}); print(c, r.deletedCount); });
```

### Web application (data na webu Part 5)
A Java web app (Apache Jena or Eclipse RDF4J) querying a triplestore loaded with
the RDF data. At least 3 non-trivial SPARQL queries, at least one CONSTRUCT.
RDFa semantic annotations in the HTML output.

---

## MongoDB Field Reference (verified against real documents)

Key fields used by `EnricherLoader` (all flat on the document):

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `hash_id` | Long | listing API | globally unique estate identifier |
| `property_type` | String | CategoryConfig | "Apartment"\|"House"\|"Land"\|"Commercial"\|"Other" |
| `deal_type` | String | CategoryConfig | "Sale"\|"Rent"\|"Auction" |
| `price_czk_value` | Long | listing API | asking price in CZK |
| `gps_lat` / `gps_lon` | Double | listing API | WGS84 |
| `usable_area_m2` | Integer | recommendations_data | already an int, no parsing needed |
| `ownership_label` | String | recommendations_data | English label e.g. "Personal" |
| `building_type_label` | String | recommendations_data | English label e.g. "Brick" |
| `building_condition_label` | String | recommendations_data | English label |
| `energy_efficiency_label` | String | recommendations_data | English label — USE THIS |
| `energy_rating_label` | String | items[] | Czech label — DO NOT USE for hashing |
| `has_balcony` etc. | Boolean | recommendations_data | all boolean features |
| `area_plocha_pozemku` | String | items[] | "800 m²" format, parse needed |
| `area_zahrada` | String | items[] | "200 m²" format |
| `area_podlahova_plocha` | String | items[] | commercial floor area |
| `count_podlazi_z_celkem` | String | items[] | "3" or "3/8" (floor/total) |
| `detail_podlazi` | String | items[] | "1. podlaží" fallback for floor number |
| `_first_seen_at` | String | scraper | ISO-8601 with nanoseconds e.g. "2026-03-26T21:27:31.546838374Z" |
| `_last_seen_at` | String | scraper | same format |
| `active` | Boolean | scraper | false = disappeared from API |
| `agency` | Document | listing API | {id: Int, name: String, url: String} |
| `description` | String | detail API | full text |
| `locality` | String | listing API | human-readable e.g. "Dubická, Česká Lípa" |

---

## Important File Paths

| Purpose | Path |
|---------|------|
| Shared library | `pipeline/shared/src/main/java/com/sreality/pipeline/shared/` |
| EnricherLoader (most complex file) | `pipeline/jar4-enricher/src/main/java/com/sreality/pipeline/enricher/load/EnricherLoader.java` |
| InitialEnricher (subclass) | `initial-load/src/main/java/com/sreality/pipeline/initialload/InitialEnricher.java` |
| SpatialJoiner | `pipeline/jar4-enricher/src/main/java/com/sreality/pipeline/enricher/spatial/SpatialJoiner.java` |
| S-JTSK → WGS84 conversion | `pipeline/jar2-ruian/src/main/java/com/sreality/pipeline/ruian/extract/SjtskToWgs84.java` |
| Airflow main DAG | `pipeline/airflow/dags/sreality_pipeline_dag.py` |
| Postgres schema | `postgres-init/01-schema.sh` (authoritative) |
| Original scraper model | `src/main/java/com/sreality/scraper/model/EstateDocumentBuilder.java` |
| Original scraper config | `src/main/java/com/sreality/scraper/config/CategoryConfig.java` |
| Build instructions | `pipeline/BUILD.md` |
| CI workflow | `.github/workflows/deploy.yml` |
