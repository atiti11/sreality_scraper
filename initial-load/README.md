# Initial Load — MongoDB → Postgres (one-time, non-destructive)

Reads all documents from every MongoDB collection and writes them into the
correct Postgres fact tables. **Does not delete anything from MongoDB.**

## When to run

Run this **once** before switching to the regular Airflow pipeline.

Prerequisites (in order):
1. Postgres is running and schema is applied (via `docker-compose.pipeline.yml`)
2. RUIAN dimensions are loaded (`java -jar pipeline/jars/jar2-ruian.jar`)
3. MongoDB has data from the existing scraper

## Build

```bash
# From project root — install original scraper and pipeline modules first
mvn install -DskipTests

cd pipeline
mvn install -DskipTests   # installs pipeline-shared and jar4-enricher into .m2

cd ../initial-load
mvn package -DskipTests
# produces: target/initial-load.jar
```

## Run

### Dry run first (counts documents, writes nothing)
```bash
INITIAL_LOAD_DRY_RUN=true \
MONGO_HOST=localhost MONGO_PORT=27018 \
MONGO_DATABASE=sreality MONGO_USERNAME=scraper MONGO_PASSWORD=changeme \
PG_HOST=localhost PG_PORT=5433 \
PG_DATABASE=sreality PG_USERNAME=sreality PG_PASSWORD=changeme \
java -Xmx512m -jar target/initial-load.jar
```

### Full load
```bash
MONGO_HOST=localhost MONGO_PORT=27018 \
MONGO_DATABASE=sreality MONGO_USERNAME=scraper MONGO_PASSWORD=changeme \
PG_HOST=localhost PG_PORT=5433 \
PG_DATABASE=sreality PG_USERNAME=sreality PG_PASSWORD=changeme \
java -Xmx512m -jar target/initial-load.jar
```

The log is also written to `initial-load.log` in the current directory.

The loader now writes estates even when the RUIAN spatial join fails. Those rows are inserted with null `obec_id`/`cast_obce_id` and can be counted in the load summary.

## After the load — verification queries

```sql
-- How many estates loaded per type?
SELECT 'apartment_sale' AS tbl, COUNT(*) FROM fact_apartment_sale
UNION ALL SELECT 'apartment_rent', COUNT(*) FROM fact_apartment_rent
UNION ALL SELECT 'house_sale',     COUNT(*) FROM fact_house_sale
UNION ALL SELECT 'house_rent',     COUNT(*) FROM fact_house_rent
UNION ALL SELECT 'land_sale',      COUNT(*) FROM fact_land_sale;

-- How many were matched to obec / cast_obce / unmatched?
SELECT COUNT(*) FILTER (WHERE obec_id IS NOT NULL) AS obec_matched,
       COUNT(*) FILTER (WHERE cast_obce_id IS NOT NULL) AS cast_obce_matched,
       COUNT(*) FILTER (WHERE obec_id IS NULL)       AS unmatched
FROM fact_apartment_sale;

-- Active vs inactive
SELECT is_active, COUNT(*) FROM fact_apartment_sale GROUP BY is_active;

-- Price range sanity check
SELECT MIN(price_asked_czk), MAX(price_asked_czk), AVG(price_asked_czk)
FROM fact_apartment_sale WHERE valid_to IS NULL;

-- Earliest first_seen_date (should match your scraping start date)
SELECT MIN(first_seen_date) FROM fact_apartment_sale;
```

## After verification — clean up MongoDB

Once satisfied, delete MongoDB documents manually:

```js
// In mongosh — example for one collection
use sreality
db.apartments_sale.deleteMany({})
// repeat for all 15 collections
```

Or use a small script:
```js
const cols = [
  "apartments_sale","apartments_rent","apartments_auction",
  "houses_sale","houses_rent","houses_auction",
  "land_sale","land_rent","land_auction",
  "commercial_sale","commercial_rent","commercial_auction",
  "other_sale","other_rent","other_auction"
];
cols.forEach(c => { const r = db[c].deleteMany({}); print(c, r.deletedCount); });
```

## Error handling

Estates that fail (GPS outside RUIAN bounding boxes, missing property_type, etc.)
are **counted and logged** but not written to Postgres. They are safe to ignore
or investigate manually. Most errors will be estates with no Czech GPS coordinates
(foreign listings, test data, etc.).

The load is **idempotent** — re-running it will skip estates already in Postgres
(same content_hash) and only insert new ones.
