# Homework 1 — Sreality Real Estate Data Warehouse

## Overview

This project builds a **galaxy schema data warehouse** from three data sources:

1. **Sreality.cz** — Czech real estate listings (scraped via Java scraper → MongoDB → this ETL)
2. **RUIAN / ČÚZK** — Czech geographical hierarchy (kraj → okres → obec → část obce), fetched via ArcGIS REST API and monthly VFR XML snapshot
3. **CSU MOS** — Czech municipal population data, downloaded as CSV from opendata.csu.gov.cz

The Python script in this folder reads a 1000-row sample from each of three fact tables in the source PostgreSQL warehouse and loads them — along with all referenced dimension rows — into the target server at `webik.ms.mff.cuni.cz`.

---

## System Requirements

- Python 3.11+
- `psycopg2-binary`
- `python-dotenv`
- Network access to both PostgreSQL servers

---

## Installation

```bash
cd homework1
pip install psycopg2-binary python-dotenv
cp .env.example .env
# Edit .env with real passwords
python etl.py
```

---

## Scripts

| File | Description |
|------|-------------|
| `etl.py` | Main ETL script — extract, transform, load |
| `.env.example` | Template for required environment variables |

---

## Inputs and Outputs

**Inputs:** PostgreSQL source warehouse (`sreality_dw`, schema `dw`) running on a private VPS, populated by the Java ETL pipeline in `../etl/`.

**Outputs:** Galaxy schema in the target PostgreSQL server (`ndbi046`, schema = your username) containing:
- 6 dimension tables
- 3 fact tables (≤ 1000 rows each)

---

## ETL Workflow

```
┌─────────────────────────────────────────────────────────────┐
│                     DATA SOURCES                            │
│                                                             │
│  Sreality.cz API    RUIAN ArcGIS REST    CSU MOS CSV        │
│  (JSON scraping)    (GeoJSON paginated)  (file download)    │
└──────────┬──────────────────┬───────────────────┬──────────┘
           │                  │                   │
           ▼                  ▼                   ▼
┌──────────────────┐ ┌────────────────┐ ┌─────────────────┐
│   MongoDB        │ │  RUIAN VFR XML │ │  MOS CSV        │
│  (operational    │ │  + ArcGIS API  │ │  (population    │
│   store)         │ │  (geography)   │ │   per obec)     │
└──────────┬───────┘ └───────┬────────┘ └────────┬────────┘
           │                 │                    │
           └─────────────────┴────────────────────┘
                             │
                    ┌────────▼────────┐
                    │   Java ETL      │
                    │  (Transform +   │
                    │   Load to PG)   │
                    └────────┬────────┘
                             │
                    ┌────────▼─────────────────┐
                    │  Source PostgreSQL        │
                    │  sreality_dw / schema dw  │
                    │                           │
                    │  fact_sale_snapshot       │
                    │  fact_rent_snapshot       │
                    │  fact_auction_snapshot    │
                    │  dim_kraj / dim_okres     │
                    │  dim_obec / dim_cast_obce │
                    │  dim_agency / dim_date    │
                    └────────┬─────────────────┘
                             │
                    ┌────────▼─────────────────┐
                    │  etl.py (this script)    │
                    │  Extract 1000 rows/fact  │
                    │  + referenced dims       │
                    │  Remap surrogate keys    │
                    │  Load to target PG       │
                    └────────┬─────────────────┘
                             │
                    ┌────────▼─────────────────┐
                    │  Target PostgreSQL        │
                    │  ndbi046 / schema=user    │
                    └──────────────────────────┘
```

---

## ETL Steps

### Extraction
- **Sreality listings**: Java scraper polls the Sreality.cz REST API (JSON), stores raw documents in MongoDB. Extraction method: API calls + web scraping.
- **RUIAN geography**: Downloaded monthly as VFR XML ZIP from ČÚZK (`services.cuzk.gov.cz`) and also via ArcGIS REST GeoJSON API. Extraction method: file download + REST API.
- **CSU demographics**: Population per municipality downloaded as CSV from `opendata.csu.gov.cz`. Extraction method: file download.

### Transformation

| # | Transformation | Justification |
|---|---------------|---------------|
| T1 | **Joining datasets** | RUIAN obec + CSU demographics joined on `kod_obce` to enrich `dim_obec` with population |
| T2 | **Spatial join** | Estate GPS coordinates matched to municipality polygon (JTS point-in-polygon) to assign `obec_id` |
| T3 | **Filtering** | Estates missing GPS, property type, or hash_id are removed — unusable for geographic analysis |
| T4 | **Handling missing values** | Null demographics, null prices, null areas preserved as NULL rather than dropped or imputed |
| T5 | **Derived attributes** | `price_asked_per_m2 = price / usable_area_m2`; `sreality_url` constructed from hash_id |
| T6 | **Adding surrogate keys** | SERIAL PKs on all dimension tables; BIGSERIAL on fact tables — natural keys alone are not stable |
| T7 | **Deduplication** | RUIAN duplicate entries removed by `kod_obce`; agencies deduplicated by `sreality_id` |
| T8 | **SCD Type 2 versioning** | New fact row only when price or `is_active` changes; `valid_to` closed on old row — tracks price history |
| T9 | **Date/number format conversion** | ISO date strings → `LocalDate`; Czech number format (non-breaking spaces, commas) → numeric |

### Load
- Dimensions loaded with `INSERT ... ON CONFLICT DO NOTHING` (idempotent)
- Surrogate keys remapped from source to destination sequences
- Fact rows inserted after FK remapping
- Full transaction — rolled back on any error

---

## Data Warehouse Schema

Galaxy schema with three fact tables sharing the same geographic and temporal dimensions.

```
fact_sale_snapshot      ─┐
fact_rent_snapshot      ─┼──▶  dim_obec ──▶ dim_okres ──▶ dim_kraj
fact_auction_snapshot   ─┘       │
                                 └──▶ dim_cast_obce
                        ─────▶  dim_agency
                        ─────▶  dim_date
```

### Schema Diagram

```
dim_kraj                dim_okres               dim_obec
─────────────────       ──────────────────────  ──────────────────────────────
PK id                   PK id                   PK id
   kod_kraje (UK)          kod_okresu (UK)          kod_obce (UK)
   nazev_kraje             nazev_okresu             nazev_obce
                        FK kraj_id → dim_kraj    FK okres_id → dim_okres
                                                    population
                                                    population_density
                                                    area_km2
                                                    avg_age
                                                    unemployment_pct

dim_cast_obce           dim_agency              dim_date
─────────────────────   ──────────────────────  ──────────────────
PK id                   PK id                   PK date_id
   kod_cast_obce (UK)      sreality_id (UK)         full_date
   nazev_cast_obce         name                     year / quarter
FK obec_id → dim_obec      url                      month / month_name
                                                    week / day_of_week
                                                    is_weekend

fact_sale_snapshot (measures: price_asked_czk, price_asked_per_m2, usable_area_m2)
fact_rent_snapshot (measures: price_monthly_czk, price_monthly_per_m2, usable_area_m2)
fact_auction_snapshot (measures: price_starting_bid_czk, usable_area_m2)
─────────────────────────────────────────────────────────────────────────────────
PK id (BIGSERIAL)
   hash_id                      -- Sreality estate identifier
   sreality_url                 -- direct link to listing
   property_type                -- Apartment / House / Land / Commercial / Other
   sub_category                 -- e.g. "2+kk", "Rodinný dům"
   valid_from / valid_to        -- SCD Type 2 validity window
FK cast_obce_id → dim_cast_obce
FK obec_id      → dim_obec
FK agency_id    → dim_agency
FK date_id      → dim_date
   [price columns per deal type]
   usable_area_m2
   floor_number / total_floors
   gps_lat / gps_lon
   ownership_label / building_type_label / building_condition_label
   energy_rating_label
   is_new_building / is_furnished / has_balcony / ... (boolean amenities)
   is_active
   first_seen_date
   advert_images_count / has_floor_plan / has_video
```

### Schema Design Decisions

- **Galaxy schema** chosen because there are three distinct fact tables (sale, rent, auction) with different price measures but shared geographic and temporal dimensions. A single star schema would force a union of incompatible price columns.
- **Snowflake geography** (kraj → okres → obec → cast_obce) reflects the real administrative hierarchy — not flattened — because analysts query at different granularities.
- **SCD Type 2** on facts captures price history over time, which is the core analytical value of the dataset.
- **dim_date** is a standard calendar dimension enabling time-series analysis (weekly, monthly, quarterly price trends).

---

## AI Usage Report

### Tools used
Claude (Anthropic) was used extensively throughout this project.

### Tasks AI assisted with

- **Architecture design**: The galaxy schema structure, SCD Type 2 approach, and snowflake geography hierarchy were designed collaboratively.
- **Java ETL code generation**: The full Java ETL pipeline (`CsuExtractor`, `RuianVfrExtractor`, `DimensionBuilder`, `PostgresLoader`, etc.) was written with AI assistance.
- **Debugging**: Multiple bugs were diagnosed with AI help — including the wrong VFR file (`ST_UZSZ` vs `ST_UKSG`), the stale Docker image issue, the wrong MOS indicator code (`010100` vs `010000`), the leading-zero mismatch between MOS `koduzemi` and RUIAN `kod_obce`, the `VARCHAR(20)` overflow on `sub_category`, and the Docker memory limit vs JVM heap mismatch.
- **Python ETL script**: The `etl.py` submission script was generated by AI based on the existing Java schema.
- **Documentation**: This README was generated by AI based on the actual system implementation.

### Critical reflection

The AI was highly effective at generating boilerplate code and diagnosing bugs from log output. However, it made several mistakes that required human correction:
- It initially underestimated memory requirements (set `Xmx768m` but container limit at `640m`).
- It suggested the MOS CSV parser was correct when the root cause was actually a wrong indicator code — this required checking the actual CSV data online to confirm.
- The `VUSC_FK_TO_NUTS2` mapping logic in `DimensionBuilder` was complex enough that the AI's first version was incomplete.

The AI cannot replace understanding of the domain (Czech administrative geography, RUIAN data structure) or the ability to read and interpret real log output critically. All AI-generated code was reviewed and tested before deployment.

### Conversation links
- This entire project was developed in a single conversation with Claude Sonnet at claude.ai.
