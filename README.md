# Sreality Scraper

Periodically scrapes [sreality.cz](https://www.sreality.cz) and persists listings to a MongoDB database. Designed to run on a VPS via Docker Compose.

---

## Project structure

```
sreality_scraper/
├── src/main/java/com/sreality/scraper/
│   ├── Main.java                        ← entry point, runs once and exits
│   ├── config/
│   │   ├── AppConfig.java               ← all env vars + defaults
│   │   ├── CategoryConfig.java          ← category code → English label + collection name
│   │   └── LabelConfig.java             ← property / POI / building label mappings
│   ├── http/
│   │   └── SrealityHttpClient.java      ← HTTP client with User-Agent + typed exceptions
│   ├── db/
│   │   └── MongoRepository.java         ← upsert, history, indexes, scrape run report
│   ├── model/
│   │   └── EstateDocumentBuilder.java   ← merges listing + detail into one document
│   ├── scraper/
│   │   ├── EstateScraper.java           ← main loop over all 15 category combinations
│   │   └── ScrapeRunReport.java         ← per-run stats + incomplete estate records
│   └── util/
│       ├── HashUtil.java                ← MD5 change-detection hash
│       └── DateParser.java              ← Czech date string → LocalDate
├── src/main/resources/
│   └── logback.xml                      ← console + rolling file logging
├── mongo-init/
│   └── 01-init-scraper.js               ← creates DB user + indexes on first boot
├── Dockerfile                           ← multi-stage: Maven build → slim JRE Alpine
├── docker-compose.yml                   ← scraper + MongoDB services
├── docker-compose.prod.yml              ← production overrides
├── pom.xml                              ← Java 21, fat JAR via maven-shade-plugin
├── .env.example                         ← template for secrets (copy to .env)
├── AGENTS.md                            ← instructions for AI coding agents
└── README.md                            ← this file
```

---

## Quick start

### 1. Create your `.env` file

```bash
cp .env.example .env
# Edit .env — set real passwords before deploying!
```

### 2. Build & run locally (dev — limited to 20 estates)

```bash
MAX_ESTATES=20 docker compose up --build
```

### 3. Full scrape

```bash
docker compose up --build
```

### 4. Deploy to VPS

```bash
git clone <repo>
cd sreality_scraper

cp .env.example .env
# Edit .env with production passwords

docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

---

## Environment variables

| Variable | Default | Description |
|---|---|---|
| `MONGO_HOST` | `mongodb` | MongoDB hostname |
| `MONGO_PORT` | `27017` | MongoDB port |
| `MONGO_DATABASE` | `sreality` | Database name |
| `MONGO_USERNAME` | `scraper` | MongoDB username |
| `MONGO_PASSWORD` | `changeme` | **Change in production** |
| `SREALITY_BASE_URL` | `https://www.sreality.cz/api/cs/v2/estates` | API base URL |
| `PER_PAGE` | `100` | Estates per listing API call |
| `MAX_ESTATES` | `0` | Dev limiter — stop after N estates (0 = unlimited) |
| `HTTP_CONNECT_TIMEOUT_MS` | `10000` | HTTP connect timeout |
| `HTTP_READ_TIMEOUT_MS` | `30000` | HTTP read timeout |

---

## MongoDB collections

| Collection | Description |
|---|---|
| `apartments_sale`, `houses_rent`, `land_sale`, … | One per property type × deal type (15 total) |
| `apartments_sale_history`, `houses_rent_history`, … | Delta history for each estate collection |
| `scrape_runs` | One document per scrape run — statistics + incomplete estate list |

---

## Services

| Service | Image | Port (host) | Notes |
|---|---|---|---|
| `mongodb` | `mongo:7.0` | `127.0.0.1:27017` | Only accessible locally on the VPS |
| `scraper` | Built from `Dockerfile` | — | Runs once and exits (`restart: "no"`) |

---

## Useful commands

```bash
# View scraper logs
docker compose logs -f scraper

# Run with a dev limit of 50 estates
MAX_ESTATES=50 docker compose up --build

# Restart scraper only (MongoDB keeps running)
docker compose restart scraper

# Open a MongoDB shell
docker compose exec mongodb mongosh -u scraper -p changeme --authenticationDatabase sreality sreality

# Connect via MongoDB Compass (local)
mongodb://scraper:changeme@localhost:27017/sreality

# Connect via MongoDB Compass (VPS — open SSH tunnel first)
ssh -L 27017:localhost:27017 user@your-vps-ip
# then connect Compass to localhost:27017

# Stop everything (data is preserved)
docker compose down

# Stop and wipe the database volume (DESTRUCTIVE)
docker compose down -v
```

---

## Querying corrupted documents

A document is "corrupted" when the last detail fetch failed — it has listing data
but may be missing description, seller info, images, etc.

```javascript
// Count corrupted documents in one collection
db.apartments_sale.countDocuments({ last_update_corrupted: true })

// Count across all estate collections
let total = 0;
["apartments_sale","apartments_rent","apartments_auction",
 "houses_sale","houses_rent","houses_auction",
 "land_sale","land_rent","land_auction",
 "commercial_sale","commercial_rent","commercial_auction",
 "other_sale","other_rent","other_auction"
].forEach(col => total += db[col].countDocuments({ last_update_corrupted: true }));
print("Total corrupted:", total);

// Price history for one estate
db.apartments_sale_history.find({ hash_id: 123456789 }).sort({ recorded_at: -1 })

// Latest scrape run summary
db.scrape_runs.find().sort({ started_at: -1 }).limit(1)
```

The scraper automatically retries corrupted documents on every run until they are fixed.

---

## Document update state machine

The table below shows every possible combination of states when the scraper
processes an estate, and what the outcome is.

| Exists in DB? | Hash changed? | Was corrupted? | Detail result | History written? | `last_update_corrupted` after |
|:---:|:---:|:---:|:---:|:---:|:---:|
| No | — | — | ✅ success | ❌ no (first insert) | `false` |
| No | — | — | ❌ fail | ❌ no (first insert) | `true` |
| Yes | ✅ yes | No | ✅ success | ✅ yes (`content_changed`) | `false` |
| Yes | ✅ yes | No | ❌ fail | ✅ yes (`content_changed`) | `true` (old detail preserved) |
| Yes | ✅ yes | Yes | ✅ success | ✅ yes (`content_changed`) | `false` |
| Yes | ✅ yes | Yes | ❌ fail | ✅ yes (`content_changed`) | `true` |
| Yes | ❌ no | Yes | ✅ success | ✅ yes (`corruption_repaired`) | `false` |
| Yes | ❌ no | Yes | ❌ fail | ❌ no (nothing new) | `true` |
| Yes | ❌ no | No | — | ❌ skipped entirely | unchanged |

**History entries** store only the *old* values of fields that changed — the current
values are always in the main document. Nested objects (`seller`, `images`) are
compared and stored as atomic blobs.

---

## Tech stack

- **Java 21** — scraper runtime
- **Maven** — build & dependency management
- **MongoDB 7** — persistence
- **Apache HttpClient 5** — HTTP requests
- **Jackson** — JSON parsing
- **Logback / SLF4J** — logging
