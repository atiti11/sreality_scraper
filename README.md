# Sreality Scraper

Periodically scrapes [sreality.cz](https://www.sreality.cz) and persists listings to a MongoDB database. Designed to run on a VPS via Docker Compose.

---

## Project structure

```
sreality-scraper/
├── src/
│   ├── main/
│   │   ├── java/com/sreality/scraper/
│   │   │   └── Main.java               ← entry point (to be implemented)
│   │   └── resources/
│   │       └── logback.xml             ← logging config
│   └── test/
│       └── java/com/sreality/scraper/
├── mongo-init/
│   └── 01-init-scraper.js              ← DB user + indexes on first boot
├── Dockerfile                          ← multi-stage build (Maven → JRE-Alpine)
├── docker-compose.yml                  ← scraper + MongoDB services
├── pom.xml                             ← Maven dependencies
├── .env.example                        ← template for secrets
└── .gitignore
```

---

## Quick start

### 1. Create your `.env` file

```bash
cp .env.example .env
# Edit .env – set real passwords before deploying!
```

### 2. Build & run locally

```bash
docker compose up --build
```

### 3. Deploy to VPS

```bash
# clone the project
git clone 
git pull

# run the production docker
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d --build
```

---

## Services

| Service         | Image                        | Port (host)          | Notes                              |
|-----------------|------------------------------|----------------------|------------------------------------|
| `mongodb`       | `mongo:7.0`                  | `127.0.0.1:27017`    | Only accessible locally on the VPS |
| `scraper`       | Built from `Dockerfile`      | —                    | No inbound ports needed            |

---

## Useful commands

```bash
# View scraper logs live
docker compose logs -f scraper

# Open a MongoDB shell
docker compose exec mongodb mongosh -u admin -p adminpassword

# Stop everything
docker compose down

# Stop and wipe the database volume (destructive!)
docker compose down -v
```

---

## Tech stack

- **Java 21** — scraper runtime
- **Maven** — build & dependency management
- **MongoDB 7** — persistence
- **Jsoup** — HTML parsing
- **Apache HttpClient 5** — HTTP requests
- **Jackson** — JSON parsing
- **Logback / SLF4J** — logging
