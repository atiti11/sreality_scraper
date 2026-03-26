# Sreality ETL — Data Warehouse Pipeline

Stateless Java ETL that reads real estate data from MongoDB, enriches it with
Czech geographical and demographic reference data, and loads the result into a
PostgreSQL data warehouse using a galaxy schema.

---

## Overview

```
MongoDB (operational store)          PostgreSQL (data warehouse)
  apartments_sale                      dw.fact_sale_snapshot
  apartments_rent          ETL         dw.fact_rent_snapshot
  houses_sale           ─────────▶     dw.fact_auction_snapshot
  houses_rent              +           dw.dim_obec  (+ demographics)
  ...                   RUIAN          dw.dim_cast_obce
                           +           dw.dim_okres
                          CSU          dw.dim_kraj
                                       dw.dim_agency
                                       dw.dim_date
                                       dw.v_sale_closing    [VIEW]
                                       dw.v_rent_closing    [VIEW]
                                       dw.v_auction_closing [VIEW]
```

---

## System Requirements

- Docker + Docker Compose (same stack as the scraper)
- The scraper must have run at least once so MongoDB contains estate data
- Network access to:
  - `services6.arcgis.com` — RUIAN geographical data (geodata.gov.cz ArcGIS)
  - `www.czso.cz` — CSU demographic CSV
- Memory: 256 MB heap allocated to the ETL container

---

## Installation

```bash
# 1. Copy env template (if not already done for the scraper)
cp .env.example .env

# 2. Edit .env — set real passwords for Mongo and Postgres
#    The defaults work for local development.

# 3. Start all services (MongoDB + PostgreSQL + Scraper on demand + ETL on demand)
docker compose up -d mongodb postgres
```

---

## Running the ETL

```bash
# Run once (creates schema on first run, upserts on subsequent runs)
docker compose run --rm etl

# View logs
docker compose logs etl

# Or tail live:
docker compose run --rm etl 2>&1 | tee etl-run.log
```

### Automating with cron

Add to your server's crontab to run every Monday at 03:00:

```cron
0 3 * * 1  cd /path/to/sreality_scraper && docker compose run --rm etl >> /var/log/sreality-etl.log 2>&1
```

The ETL is stateless and idempotent — safe to run as often as needed.

---

## Data Sources

| Source | What | Format | How fetched |
|--------|------|--------|-------------|
| MongoDB | Estate listings (scraped from Sreality) | BSON documents | MongoDB driver, streaming |
| RUIAN (geodata.gov.cz) | Municipality boundaries (cast_obce, obec, okres, kraj) | GeoJSON via ArcGIS REST | HTTP GET, paginated |
| CSU (czso.cz) | Population, density, area, avg age per municipality | CSV (Windows-1250, semicolon-delimited) | HTTP GET |

---

## Transformations

| # | Transformation | Where | Justification |
|---|---|---|---|
| 1 | Joining datasets | `DimensionBuilder` | RUIAN obec + CSU demographics joined on `kod_obce` |
| 2 | Spatial join | `SpatialJoiner` | Estate GPS → cast_obce polygon (JTS point-in-polygon) with fallback to obec centroid |
| 3 | Filtering | `RawEstate.isUsable()` | Removes estates missing GPS, property type, or hash_id |
| 4 | Handling missing values | `RawEstate`, `DimensionBuilder` | Nullable demographics, null area strings, fallback price fields |
| 5 | Derived attributes | `FactBuilder` | `price_per_m2 = price / usable_area_m2`; `sreality_url` from hash_id |
| 6 | Adding surrogate keys | `PostgresLoader` | `SERIAL` PKs on all dimension tables; `BIGSERIAL` on fact tables |
| 7 | Deduplication | `DimensionBuilder`, `FactBuilder` | RUIAN duplicate entries removed by code; agencies deduplicated by sreality_id |
| 8 | Date/number format conversion | `RawEstate`, `CsuExtractor` | ISO strings → `LocalDate`; Czech number format (spaces/commas) → numeric |
| 9 | SCD Type 2 versioning | `PostgresLoader.upsertFactSnapshots` | New fact row only when price or is_active changes; `valid_to` closed on old row |
| 10 | Aggregation | `v_sale_closing`, `v_rent_closing`, `v_auction_closing` | Per-estate closing summary derived from snapshot history |

---

## Schema — Galaxy Schema

Three snapshot fact tables share a snowflake location hierarchy and shared dimensions.

```
fact_sale_snapshot      ─┐
fact_rent_snapshot      ─┼──▶  dim_cast_obce ──▶ dim_obec ──▶ dim_okres ──▶ dim_kraj
fact_auction_snapshot   ─┘         ↑
                                dim_agency
                                dim_date

Derived views (live SQL — no storage):
  v_sale_closing
  v_rent_closing
  v_auction_closing
```

### Fact tables — SCD Type 2

Each fact table has `valid_from` and `valid_to` columns:

| Scenario | Action |
|---|---|
| New estate (no existing row) | INSERT with `valid_from = today`, `valid_to = NULL` |
| Estate changed (price or active status) | UPDATE old row `valid_to = today`; INSERT new row |
| Estate unchanged | Skip — no row written |

**Current state**: `WHERE valid_to IS NULL`
**Historical state**: `WHERE valid_from <= '2026-03-01' AND (valid_to > '2026-03-01' OR valid_to IS NULL)`

### Dimension tables — SCD Type 1

All dimensions (location, agency, date) are overwritten with the latest data on every ETL run.
No history is kept in dimensions — geography and demographics always reflect the most recent values.

### Closing views

`v_sale_closing`, `v_rent_closing`, `v_auction_closing` are SQL views (not tables) that
aggregate snapshot rows for estates that have gone inactive. They compute:

- `listed_date` — when the estate first appeared
- `closed_date` — last date it was seen
- `days_on_market`
- `initial_asking_price_czk` / `final_asking_price_czk`
- `total_price_changes` and `pct_price_reduction`

The views are live queries — always consistent with the snapshot tables, no refresh needed.

---

## Environment Variables

| Variable | Default | Description |
|---|---|---|
| `MONGO_HOST` | `mongodb` | MongoDB hostname |
| `MONGO_PORT` | `27017` | MongoDB port |
| `MONGO_DATABASE` | `sreality` | MongoDB database |
| `MONGO_USERNAME` | `scraper` | MongoDB username |
| `MONGO_PASSWORD` | `changeme` | MongoDB password |
| `PG_HOST` | `postgres` | PostgreSQL hostname |
| `PG_PORT` | `5432` | PostgreSQL port |
| `PG_DATABASE` | `sreality_dw` | PostgreSQL database |
| `PG_USERNAME` | `etl` | PostgreSQL username |
| `PG_PASSWORD` | `changeme` | PostgreSQL password |
| `PG_SCHEMA` | `dw` | PostgreSQL schema for all warehouse tables |
| `ETL_BATCH_SIZE` | `500` | Estates processed per batch (affects peak heap) |
| `ETL_HTTP_TIMEOUT_MS` | `60000` | Timeout for RUIAN/CSU downloads |
| `RUIAN_CAST_OBCE_URL` | (default) | Override RUIAN cast_obce endpoint |
| `RUIAN_OBEC_URL` | (default) | Override RUIAN obec endpoint |
| `RUIAN_OKRES_URL` | (default) | Override RUIAN okres endpoint |
| `RUIAN_KRAJ_URL` | (default) | Override RUIAN kraj endpoint |
| `CSU_DEMOGRAPHICS_URL` | (default) | Override CSU demographics CSV URL |

---

## Memory Design

The ETL is designed to run on a 4 GB server shared with MongoDB and PostgreSQL.
Total heap is capped at 256 MB (`-Xmx256m`). Memory is kept flat by:

- **Streaming MongoDB** in batches of 500 documents — no full collection in memory
- **Projecting only needed fields** from MongoDB — not loading images, descriptions, etc.
- **Loading RUIAN fully** into memory (~50 MB for ~15k cast_obce polygons with JTS geometry)
- **Loading CSU fully** into memory (~1 MB for ~6,200 municipality rows)
- **Discarding each batch** after it is loaded — eligible for GC before the next batch

Approximate steady-state heap during fact loading:
- JTS spatial index (RUIAN cast_obce polygons): ~40–60 MB
- CSU demographics map: ~2 MB
- One batch of 500 estates (RawEstate objects): ~5 MB
- Active JDBC prepared statements + batch: ~10 MB
- JVM overhead: ~50 MB
- **Total: ~120–130 MB** — well within the 256 MB budget

---

## Package Structure

```
etl/src/main/java/com/sreality/etl/
├── Main.java                    ← entry point, pipeline orchestration
├── config/
│   └── EtlConfig.java           ← all env vars + defaults
├── extract/
│   ├── MongoExtractor.java      ← streams estates from MongoDB in batches
│   ├── RuianExtractor.java      ← downloads RUIAN GeoJSON (paginated)
│   └── CsuExtractor.java        ← downloads + parses CSU demographics CSV
├── transform/
│   ├── DimensionBuilder.java    ← builds dim_* rows, joins RUIAN + CSU
│   ├── SpatialJoiner.java       ← JTS STRtree point-in-polygon matching
│   └── FactBuilder.java         ← builds FactSnapshot rows, SCD Type 2 logic
├── load/
│   └── PostgresLoader.java      ← DDL, upserts, SCD Type 2, closing views
└── model/
    ├── DimKraj.java
    ├── DimOkres.java
    ├── DimObec.java
    ├── DimCastObce.java
    ├── DimAgency.java
    ├── DimDate.java
    ├── FactSnapshot.java
    ├── RawEstate.java
    └── EtlReport.java
```

---

## Connecting to PostgreSQL

From outside Docker (e.g. DBeaver, psql):

```
Host:     localhost
Port:     5433   (mapped from container port 5432)
Database: sreality_dw
Username: etl
Password: (from .env)
Schema:   dw
```

Useful queries:

```sql
-- How many estates per property type (current snapshot)
SELECT property_type, COUNT(DISTINCT hash_id)
FROM dw.fact_sale_snapshot
WHERE valid_to IS NULL
GROUP BY property_type;

-- Average asking price per m² by Praha district
SELECT c.nazev_cast_obce, AVG(f.price_asked_per_m2)
FROM dw.fact_sale_snapshot f
JOIN dw.dim_cast_obce c ON f.cast_obce_id = c.id
JOIN dw.dim_obec o ON c.obec_id = o.id
WHERE o.nazev_obce = 'Praha'
  AND f.property_type = 'Apartment'
  AND f.valid_to IS NULL
GROUP BY c.nazev_cast_obce
ORDER BY AVG(f.price_asked_per_m2) DESC;

-- Closed listings with price reductions > 10%
SELECT hash_id, listed_date, closed_date, days_on_market,
       initial_asking_price_czk, final_asking_price_czk, pct_price_reduction
FROM dw.v_sale_closing
WHERE pct_price_reduction > 10
ORDER BY pct_price_reduction DESC;
```
