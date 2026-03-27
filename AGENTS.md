# AGENTS.md — Sreality Scraper + ETL

This file is intended for AI coding agents working on this project.
Read it fully before making any changes.

---

## Project Overview

Two Java 21 Maven applications sharing a Docker Compose stack:

1. **Scraper** (`./`) — periodically scrapes sreality.cz and stores raw estate
   documents into MongoDB (operational store).
2. **ETL** (`./etl/`) — reads from MongoDB, joins with RUIAN geographical data
   and CSU demographic data, and loads into a PostgreSQL data warehouse.

Both are **stateless** — they run once and exit. Periodic execution is managed
externally (cron, systemd timer, or docker restart policy).

---

## Repository Structure

```
sreality_scraper/
├── src/main/java/com/sreality/scraper/   ← SCRAPER module
│   ├── Main.java                          ← entry point
│   ├── config/
│   │   ├── AppConfig.java                 ← all scraper env vars + defaults
│   │   ├── CategoryConfig.java            ← category codes → English labels + collection names
│   │   └── LabelConfig.java               ← property/POI labels, ownership, building, energy
│   ├── http/
│   │   └── SrealityHttpClient.java        ← Apache HttpClient5 wrapper
│   ├── db/
│   │   └── MongoRepository.java           ← upsert by hash_id, change detection, history
│   ├── model/
│   │   └── EstateDocumentBuilder.java     ← merges listing + detail into one MongoDB Document
│   ├── scraper/
│   │   ├── EstateScraper.java             ← main scrape loop over all 15 category combinations
│   │   └── ScrapeRunReport.java           ← per-run stats, saved to scrape_runs collection
│   └── util/
│       ├── HashUtil.java                  ← MD5 content hash for change detection
│       └── DateParser.java                ← Czech date strings → LocalDate
├── src/main/resources/
│   └── logback.xml
├── mongo-init/
│   └── 01-init-scraper.js                 ← MongoDB user + indexes on first boot
│
├── etl/                                   ← ETL module
│   ├── src/main/java/com/sreality/etl/
│   │   ├── Main.java                      ← entry point, pipeline orchestration
│   │   ├── config/
│   │   │   └── EtlConfig.java             ← all ETL env vars + defaults
│   │   ├── extract/
│   │   │   ├── MongoExtractor.java        ← streams MongoDB in configurable batches
│   │   │   ├── RuianExtractor.java        ← downloads RUIAN GeoJSON (ArcGIS, paginated)
│   │   │   └── CsuExtractor.java          ← downloads + parses CSU demographics CSV
│   │   ├── transform/
│   │   │   ├── DimensionBuilder.java      ← builds dim_* rows, joins RUIAN + CSU
│   │   │   ├── SpatialJoiner.java         ← JTS STRtree point-in-polygon matching
│   │   │   └── FactBuilder.java           ← builds FactSnapshot rows, SCD Type 2 logic
│   │   ├── load/
│   │   │   └── PostgresLoader.java        ← full DDL + upserts + SCD Type 2 + closing views
│   │   └── model/
│   │       ├── DimKraj.java
│   │       ├── DimOkres.java
│   │       ├── DimObec.java               ← carries CSU demographics
│   │       ├── DimCastObce.java           ← carries JTS geometry (in-memory only)
│   │       ├── DimAgency.java
│   │       ├── DimDate.java               ← static helper, not a stored record
│   │       ├── FactSnapshot.java          ← one row per estate state change
│   │       ├── RawEstate.java             ← typed view over MongoDB Document
│   │       └── EtlReport.java             ← run statistics
│   ├── src/main/resources/
│   │   └── logback.xml
│   ├── Dockerfile
│   ├── pom.xml
│   └── README.md                          ← ETL-specific documentation
│
├── Dockerfile                             ← scraper multi-stage build
├── docker-compose.yml                     ← 4 services: mongodb, scraper, postgres, etl
├── docker-compose.prod.yml                ← production overrides
├── pom.xml                                ← scraper Maven build
├── .env.example                           ← template for all secrets (scraper + ETL)
└── AGENTS.md                              ← this file
```

---

## Services (docker-compose.yml)

| Service | Image | Role | Restart policy |
|---|---|---|---|
| `mongodb` | mongo:7.0 | Operational store — scraper writes here | `unless-stopped` |
| `scraper` | built from `./Dockerfile` | Scrapes sreality.cz → MongoDB | `"no"` — triggered by cron |
| `postgres` | postgres:16 | Data warehouse — ETL writes here | `unless-stopped` |
| `etl` | built from `./etl/Dockerfile` | MongoDB → PostgreSQL transform + load | `"no"` — triggered on demand |

Scraper and ETL are both stateless run-once containers. Start/stop them via:
```bash
docker compose run --rm scraper   # run scraper once
docker compose run --rm etl       # run ETL once
```

---

## Data Warehouse Schema (PostgreSQL — schema: `dw`)

### Fact tables (galaxy schema, SCD Type 2)
| Table | Deal type | Price column |
|---|---|---|
| `dw.fact_sale_snapshot` | Sale | `price_asked_czk`, `price_asked_per_m2` |
| `dw.fact_rent_snapshot` | Rent | `price_monthly_czk`, `price_monthly_per_m2` |
| `dw.fact_auction_snapshot` | Auction | `price_starting_bid_czk` |

All fact tables have `valid_from DATE` and `valid_to DATE` (NULL = current state).
A new row is inserted only when `price` or `is_active` changes. This is SCD Type 2
applied to the fact table itself — minimises duplicate rows while preserving full history.

### Dimension tables (SCD Type 1 — always overwrite)
| Table | Contents |
|---|---|
| `dw.dim_kraj` | Czech regions (14 rows) |
| `dw.dim_okres` | Districts (77 rows) |
| `dw.dim_obec` | Municipalities (~6,200 rows) + CSU demographics |
| `dw.dim_cast_obce` | Parts of municipalities (~15,000 rows) |
| `dw.dim_agency` | Real estate agencies (built incrementally) |
| `dw.dim_date` | Date dimension (pre-generated 2024–2030) |

### Derived views (live SQL — no storage, always consistent)
| View | Source | Contents |
|---|---|---|
| `dw.v_sale_closing` | `fact_sale_snapshot` | Closed sale listings with price trajectory |
| `dw.v_rent_closing` | `fact_rent_snapshot` | Closed rent listings with price trajectory |
| `dw.v_auction_closing` | `fact_auction_snapshot` | Closed auction listings |

---

## API Being Scraped (scraper only)

Sreality does **not** have an official public API. Everything is reverse-engineered
from browser XHR traffic. Three endpoints are used:

```
GET /api/cs/v2/estates/count?category_main_cb=N&category_type_cb=N&locality_country_id=10001
GET /api/cs/v2/estates?category_main_cb=N&category_type_cb=N&locality_country_id=10001&per_page=N&page=N
GET /api/cs/v2/estates/<hash_id>
```

- A browser-like User-Agent header is required — bare requests may get 403.
- The API is unofficial — field names or structure may change without notice.
- A polite delay (`REQUEST_DELAY_MS`) is enforced between requests.

---

## External Data Sources (ETL only)

| Source | URL | Format | Notes |
|---|---|---|---|
| RUIAN cast_obce | geodata.gov.cz ArcGIS FeatureServer | GeoJSON | Paginated, ~15k features |
| RUIAN obec | geodata.gov.cz ArcGIS FeatureServer | GeoJSON | ~6,200 features |
| RUIAN okres | geodata.gov.cz ArcGIS FeatureServer | GeoJSON | 77 features |
| RUIAN kraj | geodata.gov.cz ArcGIS FeatureServer | GeoJSON | 14 features |
| CSU demographics | czso.cz CSV download | CSV (Windows-1250, semicolon) | Population, area, avg age per obec |

All URLs are configurable via environment variables. Defaults point to the live endpoints.

---

## Key Design Decisions

### Scraper: MAX_ESTATES is a per-category limit
`MAX_ESTATES` limits the number of estates scraped **per collection** (per category ×
deal-type combination), not globally across the whole run. This ensures a representative
sample from every collection — apartments_sale, apartments_rent, houses_sale, etc. —
rather than filling only the first collection and stopping.

Example: `MAX_ESTATES=20` → at most 20 estates from each of the 15 collections
= at most 300 total estates per run.

Set `MAX_ESTATES=0` (or leave unset) for a full unlimited scrape.

When `MAX_ESTATES` is active, the `markInactiveNotSeenSince` step is **skipped** for
each category — we only scraped a partial window of the listing so we cannot reliably
determine which estates have genuinely disappeared vs. simply fell outside the sample.
Inactive marking only runs on full (unlimited) scrapes.

### Scraper: change detection
Each estate has a `_content_hash` field: `MD5(hash_id | price_czk.value_raw | name | labelsReleased)`.
If the hash matches the stored value and `last_update_corrupted=false`, the estate is skipped.
This makes re-runs cheap — only changed estates trigger a detail fetch.

### Scraper: two-pass scraping
1. Fetch listing pages → hash_id, price, name, labels
2. For changed estates only → fetch detail endpoint
3. Merge into one MongoDB document via `EstateDocumentBuilder`

### ETL: streaming batches
MongoDB is streamed in batches of 500 (configurable via `ETL_BATCH_SIZE`).
Each batch is processed and discarded before the next is read. This keeps heap flat
regardless of collection size. Peak heap during ETL is ~130 MB within a 256 MB budget.

### ETL: spatial join strategy
1. Try `cast_obce` STRtree (JTS R-tree, precise point-in-polygon)
2. If no match → fall back to nearest `obec` centroid
This gives Praha MČ / Brno obvody resolution where available, obec elsewhere.

### ETL: SCD Type 2 on facts
New fact row only when `price` or `is_active` changes. `valid_to` is set on the old row.
Unchanged estates are skipped — no row written. This prevents near-duplicate row explosion.

### ETL: SCD Type 1 on dimensions
All dimension tables are upserted by natural key on every run.
Always reflects the latest RUIAN boundaries and CSU demographics. No history kept.

### MongoDB collections
```
apartments_sale / rent / auction     houses_sale / rent / auction
land_sale / rent / auction           commercial_sale / rent / auction
other_sale / rent / auction
<collection>_history                 scrape_runs
```

---

## Environment Variables

### Scraper
| Variable | Default | Description |
|---|---|---|
| `MONGO_HOST` | `mongodb` | MongoDB hostname |
| `MONGO_PORT` | `27017` | MongoDB port |
| `MONGO_DATABASE` | `sreality` | Database name |
| `MONGO_USERNAME` | `scraper` | MongoDB username |
| `MONGO_PASSWORD` | `changeme` | MongoDB password |
| `SREALITY_BASE_URL` | `https://...` | Sreality API base URL |
| `PER_PAGE` | `100` | Estates per listing page |
| `MAX_ESTATES` | `0` | Per-category dev limiter: max N estates per collection (0 = unlimited) |
| `HTTP_CONNECT_TIMEOUT_MS` | `10000` | Connect timeout |
| `HTTP_READ_TIMEOUT_MS` | `30000` | Read timeout |
| `REQUEST_DELAY_MS` | `500` | Delay between requests |
| `TELEGRAM_BOT_TOKEN` | `` | Optional Telegram notifications |
| `TELEGRAM_CHAT_ID` | `` | Optional Telegram chat ID |

### ETL
| Variable | Default | Description |
|---|---|---|
| `PG_HOST` | `postgres` | PostgreSQL hostname |
| `PG_PORT` | `5432` | PostgreSQL port |
| `PG_DATABASE` | `sreality_dw` | PostgreSQL database |
| `PG_USERNAME` | `etl` | PostgreSQL username |
| `PG_PASSWORD` | `changeme` | PostgreSQL password |
| `PG_SCHEMA` | `dw` | Schema for all warehouse tables |
| `ETL_BATCH_SIZE` | `500` | MongoDB streaming batch size |
| `ETL_HTTP_TIMEOUT_MS` | `60000` | Timeout for RUIAN/CSU downloads |
| `RUIAN_CAST_OBCE_URL` | (default) | Override RUIAN cast_obce endpoint |
| `RUIAN_OBEC_URL` | (default) | Override RUIAN obec endpoint |
| `RUIAN_OKRES_URL` | (default) | Override RUIAN okres endpoint |
| `RUIAN_KRAJ_URL` | (default) | Override RUIAN kraj endpoint |
| `CSU_DEMOGRAPHICS_URL` | (default) | Override CSU CSV URL |

---

## How to Build and Run

```bash
# First time setup
cp .env.example .env
# Edit .env with real passwords

# Start persistent services
docker compose up -d mongodb postgres

# Run scraper once (full scrape)
docker compose run --rm scraper

# Run scraper with per-category limit (good for local testing)
MAX_ESTATES=20 docker compose run --rm scraper

# Run ETL once (requires MongoDB to have estate data)
docker compose run --rm etl

# View logs
docker compose logs -f etl
docker compose logs -f scraper
```

### Cron automation (server)
```cron
# Scraper: nightly at 02:00
0 2 * * *   cd /srv/sreality && docker compose run --rm scraper >> /var/log/sreality-scraper.log 2>&1
# ETL: weekly Monday at 03:00
0 3 * * 1   cd /srv/sreality && docker compose run --rm etl >> /var/log/sreality-etl.log 2>&1
```

---

## What NOT to Do

- **Do not** add scheduling logic inside either Java app — both exit after one run.
- **Do not** commit `.env` — only `.env.example` is committed.
- **Do not** change `hash_id` field name — it is the upsert key throughout.
- **Do not** change `_content_hash` field name — used for scraper change detection.
- **Do not** remove `REQUEST_DELAY_MS` sleep — keeps scraper polite.
- **Do not** use `Map.of()` with more than 10 entries — use `Map.ofEntries()`.
- **Do not** load entire MongoDB collections into memory in the ETL — always stream via `MongoExtractor.streamCollection()`.
- **Do not** store JTS geometry objects in PostgreSQL — they are in-memory only in `DimCastObce` for spatial joins. Only the surrogate keys are stored in PG.
- **Do not** treat `MAX_ESTATES` as a global limit — it is intentionally per-category. Do not revert this to a global counter.

---

## Dependencies

### Scraper
| Dependency | Version | Purpose |
|---|---|---|
| `mongodb-driver-sync` | 5.1.2 | MongoDB Java driver |
| `httpclient5` | 5.3.1 | HTTP requests |
| `jackson-databind` | 2.17.2 | JSON parsing |
| `logback-classic` | 1.5.6 | Logging |

### ETL (additional)
| Dependency | Version | Purpose |
|---|---|---|
| `postgresql` | 42.7.3 | PostgreSQL JDBC driver |
| `HikariCP` | 5.1.0 | JDBC connection pool |
| `jts-core` | 1.19.0 | Spatial join (point-in-polygon) |
| `commons-csv` | 1.11.0 | CSU CSV parsing |

---

## Keeping This File Up to Date

Any agent making a structural change must update AGENTS.md before finishing.
Update when you: add/rename/delete files, add env vars, change design decisions,
add dependencies, discover API gotchas, or change MongoDB collection names.
