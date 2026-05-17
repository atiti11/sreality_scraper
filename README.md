# Sreality — Java Homework

End-to-end Czech real-estate data platform built around the unofficial
[sreality.cz](https://www.sreality.cz) API. Estates are scraped on a schedule,
enriched with RUIAN geography and CSU demographic statistics, written to
Postgres with full SCD-Type-2 history, and served through a REST API that
powers a small dashboard.

---

## What is graded

The homework submission consists of two Java modules:

| Module        | Path        | What it is                                                                                     |
|---------------|-------------|------------------------------------------------------------------------------------------------|
| **Pipeline**  | `pipeline/` | Five standalone Java JARs orchestrated by Airflow that build and maintain the Postgres model.  |
| **Backend**   | `backend/`  | Java REST API (Javalin) that queries Postgres and exposes data to the dashboard.               |

Everything else in this repository is **supportive context** — it shows how
the two graded modules fit into a real deployment, but is not part of the
assignment itself:

- `initial-load/` — one-off Java loader used to bootstrap Postgres from an existing MongoDB dump.
- `frontend/` — React + Vite dashboard that consumes the backend API.
- `mongo-init/`, `postgres-init/` — database init scripts used by Docker Compose.

---

## Architecture

```
                     ┌────────────────────────────────────────┐
                     │              GRADED SCOPE              │
                     ├────────────────────────────────────────┤
   sreality.cz ──►   │  JAR 1  scraper  ──►  MongoDB (queue)  │
                     │                              │         │
   CUZK RUIAN  ──►   │  JAR 2  ruian    ──►         ▼         │
                     │                       ┌─────────────┐  │
   CSU XLSX    ──►   │  JAR 3  csu      ──►  │  Postgres   │  │
                     │                       │  (SCD T2)   │  │
                     │  JAR 4  enricher ◄────┤             │  │
                     │                       └──────┬──────┘  │
                     │  JAR 5  reporter  ──► Telegram         │
                     │                              │         │
                     │                              ▼         │
                     │                       backend (REST)   │
                     └──────────────────────────────│─────────┘
                                                    ▼
                                              frontend (React)
```

The pipeline runs every 12 h via Airflow. MongoDB is **only a staging queue** —
documents are deleted after a successful Postgres write. Postgres is the
single source of truth and the only thing the backend reads.

---

## Tech stack

- **Java 21**, **Maven** (multi-module via `pipeline/pom.xml`)
- **Postgres 16** — primary store, SCD Type 2
- **MongoDB 7** — staging queue between scraper and enricher
- **Apache Airflow** — orchestration (12 h cadence)
- **Javalin** — backend REST framework
- **Jackson**, **Apache HttpClient 5**, **JDBC** (no ORM)
- **React + Vite + Tailwind** (frontend, supportive)
- **Docker Compose** for the full stack

---

## Repository layout

```
sreality_scraper/
├── pipeline/                          ← GRADED — Airflow-orchestrated JARs
│   ├── jar0-initial-load/             (helper, runs once at bootstrap)
│   ├── jar1-scraper/                  Sreality API  → MongoDB
│   ├── jar2-ruian/                    CUZK VFR XML → dim_* tables
│   ├── jar3-csu/                      CSU XLSX     → fact_obec_stats
│   ├── jar4-enricher/                 MongoDB      → 14 fact tables + change log
│   ├── jar5-reporter/                 Postgres     → Telegram summary
│   ├── shared/                        common utilities (config, JDBC, logging)
│   ├── airflow/                       DAG definitions
│   ├── schema.sql                     full Postgres schema with comments
│   ├── pom.xml                        parent POM
│   └── README.md                      pipeline-specific docs
│
├── backend/                           ← GRADED — Javalin REST API
│   ├── src/main/java/com/sreality/dashboard/
│   │   ├── App.java                   entry point
│   │   ├── handlers/                  one class per endpoint group
│   │   ├── sql/                       parameterised queries
│   │   └── util/                      small helpers (Pearson, URL builder)
│   └── pom.xml
│
├── initial-load/                      supportive — bootstrap loader
├── frontend/                          supportive — React dashboard
│
├── mongo-init/                        DB bootstrap scripts (users + indexes)
├── postgres-init/                     DB bootstrap scripts (extensions + roles)
│
├── docker-compose.yml                 base services (Mongo + Postgres)
├── docker-compose.pipeline.yml        adds Airflow + JAR runner
├── docker-compose.dashboard.yml       adds backend + frontend (+ nginx)
└── README.md                          this file
```

---

## How to run

### Prerequisites
- Docker + Docker Compose
- Java 21 + Maven (only if you want to build JARs locally instead of in Docker)

### Pipeline (graded)

```bash
cp .env.example .env
# fill in passwords + CSU_XLSX_URLS

# build all JARs
cd pipeline && mvn package -DskipTests && cd ..

# start core infrastructure
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d postgres mongodb

# start Airflow (schema.sql is applied automatically by Postgres on first boot)
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml run --rm airflow-init
docker compose -f docker-compose.yml -f docker-compose.pipeline.yml up -d airflow-webserver airflow-scheduler

# open Airflow at http://localhost:8080
#   1. trigger 'ruian_full_load'  once  (dimensions must exist first)
#   2. trigger 'csu_full_load'    once  (seeds obec_successor + history)
#   3. 'sreality_pipeline' runs every 12h automatically
```

See [`pipeline/README.md`](pipeline/README.md) for the detailed JAR-by-JAR rundown.

The same pipeline also runs unattended on a Hetzner VPS, where it feeds the
public dashboard at <https://reality.annakmentova.cz>. The full VPS setup —
DNS, Caddy + Let's Encrypt, production overlay files — is documented under
[Production deployment](#production-deployment) below.

### Backend (graded)

```bash
cd backend && mvn package -DskipTests
docker compose -f docker-compose.yml -f docker-compose.dashboard.yml up -d backend
# API is at http://localhost:8081
```

### Full stack with frontend (supportive)

```bash
docker compose -f docker-compose.yml \
               -f docker-compose.pipeline.yml \
               -f docker-compose.dashboard.yml up -d
# dashboard at http://localhost:8088 (via nginx + htpasswd)
```

---

## Production deployment

In addition to running locally, the full stack is deployed on a small
Hetzner VPS (1 vCPU / 2 GB RAM, Ubuntu 22.04) and the dashboard is reachable
publicly at **<https://reality.annakmentova.cz>**. The pipeline runs on a
12 h Airflow schedule, the backend serves the API, and the React frontend is
fronted by Caddy which terminates TLS and proxies to the dashboard
containers.

### One-time VPS setup

```bash
# 1. Point DNS at the VPS
#    In your DNS provider, create an A record:
#       reality.annakmentova.cz   →   <VPS public IPv4>
#    (and optionally an AAAA for IPv6).

# 2. SSH in and install Docker + Caddy
ssh root@<VPS_IP>
apt update && apt install -y docker.io docker-compose-plugin caddy git

# 3. Clone the repo and fill in .env
git clone <repo-url> /opt/sreality
cd /opt/sreality
cp .env.example .env
nano .env   # set POSTGRES_PASSWORD, MONGO_PASSWORD, AIRFLOW_FERNET_KEY,
            # DASHBOARD_USER, DASHBOARD_PASSWORD, TELEGRAM_*, CSU_XLSX_URLS

# 4. Build pipeline JARs
cd pipeline && mvn package -DskipTests && cd ..

# 5. Start the whole stack with the production overlays
docker compose \
  -f docker-compose.yml \
  -f docker-compose.pipeline.yml \
  -f docker-compose.dashboard.yml \
  -f docker-compose.dashboard.prod.yml \
  up -d

# 6. Bootstrap the data (one-off)
#    Open the Airflow UI via SSH tunnel:
ssh -L 8080:localhost:8080 root@<VPS_IP>
#    then in a browser at http://localhost:8080:
#      a) trigger DAG  ruian_full_load   (waits for completion)
#      b) trigger DAG  csu_full_load     (waits for completion)
#      c) enable DAG   sreality_pipeline (12h schedule)
```

### Caddy reverse proxy

Create `/etc/caddy/Caddyfile` on the VPS:

```caddy
reality.annakmentova.cz {
    encode zstd gzip

    # Frontend container (nginx) is bound to 127.0.0.1:8088 by
    # docker-compose.dashboard.yml — Caddy proxies to it and terminates TLS.
    reverse_proxy 127.0.0.1:8088
}
```

Then reload Caddy:

```bash
systemctl reload caddy
```

Caddy will automatically obtain and renew a Let's Encrypt certificate for
`reality.annakmentova.cz` on first request. After a minute or two the site is
live over HTTPS.

### Verifying the deployment

```bash
# DNS resolves
dig +short reality.annakmentova.cz

# Cert is valid
curl -sI https://reality.annakmentova.cz | head -1
# → HTTP/2 401   (Basic Auth is in front — credentials are DASHBOARD_USER / DASHBOARD_PASSWORD)

# Containers are healthy
docker compose ps
```

### Production notes

- Postgres and MongoDB **only listen on 127.0.0.1** on the VPS. Database
  access from a laptop goes through an SSH tunnel:
  `ssh -L 5433:localhost:5432 root@<VPS_IP>`, then connect to `localhost:5433`.
- The backend container is **not published to the host** (`ports: !reset []`
  in the prod overlay). Only nginx → backend traffic happens inside the
  `scraper-shared-net` network.
- Both the nginx layer and the backend re-validate the same Basic Auth
  credentials, so even if port 8000 were ever exposed it would still be
  gated.
- The pipeline overlay caps container memory so the whole stack
  (Postgres + Mongo + Airflow + 5 JARs + backend + frontend) fits in 2 GB.

---

## Postgres data model — highlights

Full DDL with comments is in [`pipeline/schema.sql`](pipeline/schema.sql).

**Dimensions** (RUIAN geography hierarchy)
- `dim_kraj`, `dim_okres`, `dim_obec`, `dim_cast_obce`
- `obec_successor` — extinct-municipality → successor map (from CSU OD_KAM)

**Facts**
- `fact_apartment_sale`, `fact_apartment_rent`, … — 14 tables split by property × deal type.
  All use SCD Type 2 (`valid_from`, `valid_to IS NULL` = current row).
- `fact_obec_stats` — yearly CSU statistics per municipality.
- `estate_field_changes` — unified per-field change log across all fact tables (for analytics).
- `estate_detail` — latest description text per estate (not versioned).

**Metadata**
- `ruian_metadata` — freshness tracking for RUIAN snapshots.

---

## Key design decisions

- **Change detection lives in Postgres, not Mongo.** JAR 1 loads
  `(hash_id → content_hash)` from Postgres at the start of each category run.
  Only estates with a changed hash are written to the Mongo staging queue —
  the detail endpoint is skipped for everything else.
- **MongoDB is staging only.** After JAR 4 commits to Postgres, the
  corresponding Mongo documents are deleted. Nothing depends on Mongo as a
  long-term store.
- **SCD Type 2 everywhere.** Every fact row has `valid_from` / `valid_to`.
  Closing a row and opening a new one happens in a single transaction.
- **Bounding-box spatial join — no PostGIS.** RUIAN `CastObce` centroids are
  loaded into Postgres and expanded by ~500 m. Estates are matched via
  point-in-bbox with a centroid-distance tiebreaker.
- **CSU municipality succession.** Extinct municipalities are kept in
  `dim_obec` with `is_active=false`; historical stats are queried via
  `obec_successor` so a successor still "sees" the full predecessor history.
- **No ORM in the backend.** Hand-written SQL in `backend/.../sql/Queries.java`,
  plain JDBC — keeps query plans predictable and the codebase small.

---

## Environment variables

A complete template is in `.env.example`. The most important ones:

| Variable                                  | Purpose                                    |
|-------------------------------------------|--------------------------------------------|
| `POSTGRES_PASSWORD`                       | Postgres superuser password                |
| `MONGO_PASSWORD`                          | Mongo `scraper` user password              |
| `AIRFLOW_FERNET_KEY`                      | Airflow secrets encryption key (generate!) |
| `CSU_XLSX_URLS`                           | Comma-separated CSU XLSX URLs for JAR 3    |
| `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`  | Used by JAR 5 reporter                     |
| `MAX_ESTATES`                             | Dev limit — stop scraper after N estates   |

---

## Useful commands

```bash
# tail logs of one JAR
docker compose -f docker-compose.pipeline.yml logs -f jar1-scraper

# open a Postgres shell
docker compose exec postgres psql -U postgres sreality

# open a Mongo shell
docker compose exec mongodb mongosh -u scraper -p "$MONGO_PASSWORD" \
  --authenticationDatabase sreality sreality

# stop everything (data is preserved)
docker compose down

# wipe DB volumes (DESTRUCTIVE)
docker compose down -v
```

---

## AI usage

This project was developed with substantial assistance from AI coding
assistants — primarily **Claude Code** (Anthropic) and, to a smaller extent,
**ChatGPT** (OpenAI). I want to be transparent about how they were used:

**Where AI helped meaningfully**
- *Scaffolding and refactors.* The repository went through several large
  restructurings (single scraper → 5-JAR pipeline; ad-hoc Mongo schema → SCD
  Type 2 in Postgres). Claude Code was used to propose, execute, and review
  these refactors across many files at once.
- *SQL.* The schema, the SCD-Type-2 upsert pattern, and most non-trivial
  queries in `backend/.../sql/Queries.java` were drafted with AI help and
  then verified by reading the resulting query plans (`EXPLAIN`) and the
  output against real data.
- *Boilerplate and glue code.* Javalin handlers, JDBC plumbing, Jackson
  mapping, Airflow DAG skeletons, Dockerfiles and Compose files — all areas
  where AI produced a first draft that I then trimmed and adjusted.
- *Code review.* I regularly asked the assistant to critique my own code for
  bugs, dead code, and style — several issues found and fixed this way are
  visible in the git history.
- *Documentation.* Most docstrings, the state-machine table in
  `pipeline/jar1-scraper`, and this README itself were AI-drafted and then
  hand-edited.

**Where AI was *not* used**
- *Architecture decisions.* The split into five JARs, the choice to use
  Postgres as the source of truth and Mongo only as a staging queue, the
  bounding-box-without-PostGIS spatial join, the municipality-successor model
  for CSU stats — these are mine. AI helped me prototype alternatives, but
  the final shape of the system reflects deliberate choices I can defend.
- *Understanding.* I read everything the assistant produced before
  committing it. Where I did not understand a generated snippet, I either
  rewrote it or removed it. The code in this repository is code I can walk a
  reviewer through line by line.

**Verification**
- All non-trivial behaviour is covered by tests or by reproducible runs
  against real Sreality / RUIAN / CSU data; passing tests were the bar for
  accepting AI-generated changes.
- AI suggestions were treated as drafts, not as authority. When the
  assistant and the actual data disagreed, the data won.

In short: AI substantially accelerated the *writing* of this project, but the
design, the verification, and the responsibility for the result are mine.

---

## A note to the reviewer

The graded code lives in `pipeline/` and `backend/`. The `initial-load/` and
`frontend/` directories are included so the full system is reproducible
end-to-end, but they are intentionally outside the scope of this submission.
