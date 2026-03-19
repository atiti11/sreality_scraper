# AGENTS.md — Sreality Scraper

This file is intended for AI coding agents working on this project.
Read it fully before making any changes.

---

## Project Overview

A Java 21 Maven application that periodically scrapes the unofficial
sreality.cz REST API and persists real estate listings into MongoDB.

The scraper is **stateless** — it runs once and exits. Periodic execution
is managed externally (cron, systemd timer, or docker restart policy).

---

## Repository Structure

```
sreality_scraper/
├── src/main/java/com/sreality/scraper/
│   ├── Main.java                        ← entry point, wires everything, runs once
│   ├── config/
│   │   ├── AppConfig.java               ← all env vars + defaults
│   │   ├── CategoryConfig.java          ← category_main_cb / category_type_cb / sub_cb → English labels + MongoDB collection names
│   │   └── LabelConfig.java             ← property feature labels, POI labels, ownership, building type, energy rating
│   ├── http/
│   │   └── SrealityHttpClient.java      ← Apache HttpClient5 wrapper (User-Agent, timeouts, SrealityHttpException)
│   ├── db/
│   │   └── MongoRepository.java         ← upsert by hash_id, change-detection query, auto-index on first use
│   ├── model/
│   │   └── EstateDocumentBuilder.java   ← merges listing + detail JsonNodes into one MongoDB Document
│   ├── scraper/
│   │   ├── EstateScraper.java           ← main scrape loop over all 15 category combinations
│   │   └── ScrapeRunReport.java         ← per-run stats + incomplete estate records, saved to scrape_runs
│   └── util/
│       ├── HashUtil.java                ← MD5(hash_id | price | name | labels) for change detection
│       └── DateParser.java              ← parses Czech date strings ("Dnes", "Včera", "d.M.yyyy") → LocalDate
├── src/main/resources/
│   └── logback.xml                      ← console + rolling file logging
├── mongo-init/
│   └── 01-init-scraper.js               ← creates MongoDB user + indexes on first container boot
├── Dockerfile                           ← multi-stage: Maven build → slim JRE Alpine runtime
├── docker-compose.yml                   ← two services: scraper + mongodb
├── docker-compose.prod.yml              ← production overrides (resource limits, pinned image versions)
├── pom.xml                              ← Java 21, fat JAR via maven-shade-plugin
├── .env.example                         ← template for secrets (copy to .env)
└── AGENTS.md                            ← this file
```

---

## API Being Scraped

Sreality does **not** have an official public API. Everything is reverse-engineered
from the browser's XHR traffic. Two endpoints are used:

### 1. Listing endpoint (paginated)
```
GET https://www.sreality.cz/api/cs/v2/estates
    ?category_main_cb=<1-5>
    &category_type_cb=<1-3>
    &locality_country_id=10001
    &per_page=<PER_PAGE>
    &page=<N>
```
Returns a summary of each estate. Does **not** include a last-updated timestamp.

### 2. Count endpoint
```
GET https://www.sreality.cz/api/cs/v2/estates/count
    ?category_main_cb=<1-5>
    &category_type_cb=<1-3>
    &locality_country_id=10001
```
Returns `{ "result_size": N }`.

### 3. Detail endpoint
```
GET https://www.sreality.cz/api/cs/v2/estates/<hash_id>
```
Returns full estate detail including description, seller info, images, and
the `items[]` array which contains the `"type": "edited"` entry ("Aktualizace")
with the last-update date as a Czech human-readable string.

### Important API notes
- A **browser-like User-Agent header is required** — bare requests may get 403.
- The API is unofficial and undocumented — field names or structure may change.
- No authentication is required.
- A polite delay of 300ms between requests is enforced in `EstateScraper`.

---

## Key Design Decisions

### Change detection
To avoid unnecessary detail fetches on periodic re-runs, each estate has a
`_content_hash` field in MongoDB:
```
MD5(hash_id | price_czk.value_raw | name | labelsReleased)
```
If the hash matches what is already stored, the estate is skipped entirely.
This is the primary mechanism for making re-runs cheap.

### Two-pass scraping
1. Fetch all listing pages → get `hash_id`, price, name, labels
2. For each estate where hash differs → fetch detail endpoint
3. Merge both into one MongoDB document via `EstateDocumentBuilder`

### MongoDB collections
Each property type × deal type combination has its own collection:
```
apartments_sale          houses_sale          land_sale          commercial_sale          other_sale
apartments_rent          houses_rent          land_rent          commercial_rent          other_rent
apartments_auction       houses_auction       land_auction       commercial_auction       other_auction
apartments_sale_history  houses_sale_history  ...                                        (one per estate collection)
scrape_runs              (one document per run — append only, never updated)
```
Estate collection names are derived in `CategoryConfig.collectionName(categoryMainCb, categoryTypeCb)`.
Each estate collection has a companion `<collection>_history` collection managed by `MongoRepository`.
The `scrape_runs` collection is written by `MongoRepository.saveReport()` at the end of every run.

### Mapping strategy
All known API codes are translated to English human-readable strings.
Unknown codes are stored as-is (e.g. `"unknown_42"`).
All mappings live in `config/CategoryConfig.java` and `config/LabelConfig.java` —
**this is the correct place to add new mappings**.

### History (delta storage)
Every estate collection `<col>` has a companion `<col>_history` collection.
When an estate changes, only the **old values** of the changed fields are stored
in a history entry — the current values are always in the main document.
This is a delta/diff approach (Option A: nested objects like `seller` and `images`
are compared and stored as atomic blobs).

History entry structure:
```json
{
  "hash_id":       123,
  "recorded_at":   "2026-03-25T08:14:22Z",
  "change_number": 3,
  "reason":        "content_changed" | "corruption_repaired",
  "delta": {
    "price_czk_value": 9700000,
    "seller": { ... old seller object ... }
  }
}
```

Trigger rules for writing a history entry:
- Content hash changed (name/price/labels differ) → delta of all changed fields
- Hash unchanged BUT was corrupted and detail now fixed → detail-only delta
- Detail failed again on already-corrupted doc → NO entry (nothing new to record)
- Brand new estate (first insert) → NO entry (nothing to diff against)

Fields excluded from delta: `_id`, `_scraped_at`, `_updated_at`, `_update_count`,
`_first_seen_at`, `_content_hash`, `_detail_preserved_from_previous`.

To reconstruct the full state of an estate at any point in time:
1. Take the current document from the estate collection
2. Fetch all history entries for that `hash_id`, sorted by `recorded_at` descending
3. Apply deltas in reverse until you reach the target date

### 404 / 410 handling
If the detail endpoint returns 404 or 410, the estate is stored using listing data only.
`_detail_available: false` is set on the document.
The count of gone estates is tracked in `ScrapeStats.goneEstates` and printed in the summary.

### Update tracking
Every document carries these metadata fields to track its history:

| Field                 | Type      | Set on         | Meaning                                          |
|-----------------------|-----------|----------------|--------------------------------------------------|
| `_first_seen_at`      | ISO string| Insert only    | When the estate was first scraped                |
| `_updated_at`         | ISO string| Insert + update| When the document was last changed               |
| `_update_count`       | int       | Insert + update| How many times the content hash changed          |
| `_scraped_at`         | ISO string| Every upsert   | When the scraper last visited this estate        |
| `_content_hash`       | MD5 hex   | Every upsert   | Hash of price + name + labels for change detection|
| `_detail_available`   | bool      | Every upsert   | Whether detail endpoint returned data            |
| `last_update_corrupted`| bool     | Every upsert   | **true** = last scrape stored listing data only (detail failed); **false** = fully complete. Use this for quick corruption queries. |
| `_detail_preserved_from_previous` | bool | Update only | Set to **true** when detail fetch failed but the previous document was complete — detail fields were backfilled from the prior version so no data was lost. |

Note: `_scraped_at` updates on every run (even skipped ones do not update it — skipped means
the document was not touched at all). `_updated_at` only changes when the content hash differs.

---

## Environment Variables

| Variable                  | Default                                      | Description                                      |
|---------------------------|----------------------------------------------|--------------------------------------------------|
| `MONGO_HOST`              | `mongodb`                                    | MongoDB hostname                                 |
| `MONGO_PORT`              | `27017`                                      | MongoDB port                                     |
| `MONGO_DATABASE`          | `sreality`                                   | Database name                                    |
| `MONGO_USERNAME`          | `scraper`                                    | MongoDB username                                 |
| `MONGO_PASSWORD`          | `changeme`                                   | MongoDB password — **change in production**      |
| `SREALITY_BASE_URL`       | `https://www.sreality.cz/api/cs/v2/estates`  | Base URL for the API                             |
| `PER_PAGE`                | `100`                                        | Estates per listing API call (max ~100 is safe)  |
| `MAX_ESTATES`             | `0`                                          | Dev limiter: stop after N estates (0 = unlimited)|
| `HTTP_CONNECT_TIMEOUT_MS` | `10000`                                      | HTTP connect timeout in milliseconds             |
| `HTTP_READ_TIMEOUT_MS`    | `30000`                                      | HTTP read timeout in milliseconds                |

Copy `.env.example` to `.env` and fill in real values before running.

---

## How to Build and Run

### Local development (quick test with limiter)
```bash
cp .env.example .env
# Set MAX_ESTATES=20 in .env for a quick test run

docker compose up --build
```

### Full scrape
```bash
# Set MAX_ESTATES=0 in .env (or leave unset)
docker compose up --build
```

### Production deployment
```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

### Build the JAR manually
```bash
mvn package -DskipTests
java -jar target/sreality-scraper-1.0-SNAPSHOT.jar
```

---

## Adding New Code

### Adding a new label / code mapping
Edit `config/LabelConfig.java` or `config/CategoryConfig.java`.
Add a new `Map.entry(...)` to the appropriate map.
Add a human-readable English label and optionally a description comment.

### Adding a new field from the API
If a new field appears in the listing response: add extraction to `EstateDocumentBuilder.build()`.
If it appears in the detail response: add it to `appendDetailFields()` or `appendItems()`.
If it has a code that needs mapping: add the mapping to `LabelConfig` or `CategoryConfig` first.

### Adding a new category combination
Add the new `category_main_cb` value to `CATEGORY_MAIN_CBS` in `EstateScraper.java`
and add its mapping to `CategoryConfig.PROPERTY_TYPE`.

---

## What NOT to Do

- **Do not** add scheduling logic inside the Java app — the scraper exits after one run.
  Use cron or a docker restart policy instead.
- **Do not** commit `.env` — it contains secrets. Only `.env.example` is committed.
- **Do not** change the `hash_id` field name — it is the upsert key in MongoDB.
- **Do not** change the `_content_hash` field name — it is used for change detection.
- **Do not** add authentication handling for the sreality API — it is not needed.
- **Do not** remove the `REQUEST_DELAY_MS` sleep in `EstateScraper` — it keeps
  the scraper polite and avoids rate limiting / IP bans.
- **Do not** use `Map.of()` with more than 10 entries — use `Map.ofEntries()` instead
  (Java limitation). This is already done correctly in the codebase.

---

## Known Limitations and Gotchas

- The sreality API is **unofficial** — field structure can change without notice.
  If scraping suddenly breaks, check whether the API response shape has changed.
- The `"Aktualizace"` (last update) field in the detail endpoint is a human-readable
  Czech string, not a timestamp. `DateParser` handles known values; unknown formats
  are stored as raw strings.
- The listing endpoint does **not** expose a last-updated timestamp per estate.
  Change detection relies on the content hash of price + name + labels only.
- `category_main_cb` values beyond 5 and `category_type_cb` values beyond 3 are
  stored with an `"unknown_N"` label — add them to `CategoryConfig` if discovered.
- MongoDB collection names are derived from the category codes. If sreality ever
  adds new categories, new collections will be auto-created on first insert.
- The detail endpoint returns **HTTP 410 Gone** with body `{"logged_in": false}` for
  estates that existed in the search index but were sold/withdrawn before the detail
  fetch. The `logged_in: false` body is sreality's generic minimal 410 response and
  has **nothing to do with authentication** — it is a red herring. These estates are
  stored with listing data only (`_detail_available: false`) and counted as `goneEstates`
  in the scrape summary. Expect roughly 1–3% of estates per run to fall into this category.

---

## Dependencies (key ones)

| Dependency                        | Version  | Purpose                        |
|-----------------------------------|----------|--------------------------------|
| `mongodb-driver-sync`             | 5.1.2    | MongoDB Java driver            |
| `httpclient5`                     | 5.3.1    | HTTP requests                  |
| `jackson-databind`                | 2.17.2   | JSON parsing                   |
| `logback-classic`                 | 1.5.6    | Logging implementation         |
| `slf4j-api`                       | 2.0.13   | Logging facade                 |

Full dependency list is in `pom.xml`.

---

## Keeping This File Up to Date

**Any agent that makes a structural change to this project must update AGENTS.md
to reflect that change before considering the task complete.**

Specifically, update this file when you:

- **Add, rename, or delete a file or directory** — update the Repository Structure tree
- **Add a new environment variable** — add a row to the Environment Variables table
  and add the variable with its default to `AppConfig.java` and `.env.example`
- **Add a new API endpoint or change how an existing one is called** — update the
  API Being Scraped section
- **Add a new MongoDB collection or change the collection naming scheme** — update
  the MongoDB collections block under Key Design Decisions
- **Add a new mapping category** (e.g. a new `LabelConfig` map or `CategoryConfig` entry) —
  add a note under Adding New Code
- **Change a key design decision** (hash fields, upsert key, two-pass logic, delay) —
  update the relevant subsection and the What NOT to Do list if needed
- **Add, upgrade, or remove a dependency** — update the Dependencies table and `pom.xml`
- **Discover a new API gotcha or limitation** — add it to Known Limitations and Gotchas

When updating, keep the same tone and formatting as the rest of the file.
Do not add speculative or forward-looking content — only document what is
actually implemented and verified.
