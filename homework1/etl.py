"""
Sreality Data Warehouse — ETL submission script
================================================
Extracts 1000 rows from each fact table (sale, rent, auction) from the source
PostgreSQL data warehouse, along with all referenced dimension rows, and loads
everything into the target PostgreSQL server at webik.ms.mff.cuni.cz.

Data sources used:
  1. Sreality.cz API        — real estate listings (scraped via Java scraper → MongoDB)
  2. RUIAN / ČÚZK ArcGIS   — Czech municipality geography (GeoJSON via REST API)
  3. CSU MOS open data      — Czech municipality population (CSV download)

Extraction methods:
  1. Web scraping / API calls  (Sreality.cz REST API, RUIAN ArcGIS REST)
  2. File download             (CSU MOS CSV from opendata.csu.gov.cz)
  3. Database read             (PostgreSQL source warehouse — this script)

Input formats:
  - JSON  (Sreality API responses, RUIAN GeoJSON)
  - CSV   (CSU MOS demographic data)
  - BSON  (MongoDB intermediate storage)

Usage:
    pip install psycopg2-binary python-dotenv
    python etl.py

Environment variables (or .env file):
    SRC_HOST      Source PostgreSQL host         (default: localhost)
    SRC_PORT      Source PostgreSQL port         (default: 5433)
    SRC_DB        Source database name           (default: sreality_dw)
    SRC_USER      Source username                (default: etl)
    SRC_PASSWORD  Source password                (required)
    DST_HOST      Target host                    (default: webik.ms.mff.cuni.cz)
    DST_PORT      Target port                    (default: 5432)
    DST_DB        Target database                (default: ndbi046)
    DST_USER      Target username                (required)
    DST_PASSWORD  Target password                (required)
    DST_SCHEMA    Target schema                  (default: username)
    FACT_LIMIT    Rows per fact table            (default: 1000)
"""

import os
import sys
import logging
from typing import Any

import psycopg2
import psycopg2.extras
from dotenv import load_dotenv

load_dotenv()

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%H:%M:%S",
)
log = logging.getLogger(__name__)

# ── Connection config ─────────────────────────────────────────────────────────

def src_conn():
    return psycopg2.connect(
        host=os.getenv("SRC_HOST", "localhost"),
        port=int(os.getenv("SRC_PORT", "5433")),
        dbname=os.getenv("SRC_DB", "sreality_dw"),
        user=os.getenv("SRC_USER", "etl"),
        password=os.getenv("SRC_PASSWORD", "changeme"),
    )

def dst_conn():
    return psycopg2.connect(
        host=os.getenv("DST_HOST", "webik.ms.mff.cuni.cz"),
        port=int(os.getenv("DST_PORT", "5432")),
        dbname=os.getenv("DST_DB", "ndbi046"),
        user=os.getenv("DST_USER"),
        password=os.getenv("DST_PASSWORD"),
    )

SCHEMA      = os.getenv("DST_SCHEMA", os.getenv("DST_USER", "public"))
FACT_LIMIT  = int(os.getenv("FACT_LIMIT", "1000"))
SRC_SCHEMA  = "dw"

# ── Schema DDL ────────────────────────────────────────────────────────────────

DDL = """
-- Dimension: kraj (NUTS2 cohesion region)
CREATE TABLE IF NOT EXISTS {s}.dim_kraj (
    id          SERIAL PRIMARY KEY,
    kod_kraje   VARCHAR(20)  NOT NULL UNIQUE,
    nazev_kraje VARCHAR(100) NOT NULL
);

-- Dimension: okres (district), child of kraj
CREATE TABLE IF NOT EXISTS {s}.dim_okres (
    id           SERIAL PRIMARY KEY,
    kod_okresu   VARCHAR(20)  NOT NULL UNIQUE,
    nazev_okresu VARCHAR(100) NOT NULL,
    kraj_id      INT NOT NULL REFERENCES {s}.dim_kraj(id)
);

-- Dimension: obec (municipality), child of okres
-- Enriched with CSU MOS demographic data (population)
CREATE TABLE IF NOT EXISTS {s}.dim_obec (
    id                 SERIAL PRIMARY KEY,
    kod_obce           VARCHAR(20)  NOT NULL UNIQUE,
    nazev_obce         VARCHAR(100) NOT NULL,
    okres_id           INT NOT NULL REFERENCES {s}.dim_okres(id),
    population         INT,
    population_density NUMERIC(10,2),
    area_km2           NUMERIC(10,2),
    avg_age            NUMERIC(5,2),
    unemployment_pct   NUMERIC(5,2)
);

-- Dimension: cast_obce (part of municipality), child of obec
CREATE TABLE IF NOT EXISTS {s}.dim_cast_obce (
    id              SERIAL PRIMARY KEY,
    kod_cast_obce   VARCHAR(20)  NOT NULL UNIQUE,
    nazev_cast_obce VARCHAR(100) NOT NULL,
    obec_id         INT NOT NULL REFERENCES {s}.dim_obec(id)
);

-- Dimension: real estate agency
CREATE TABLE IF NOT EXISTS {s}.dim_agency (
    id          SERIAL PRIMARY KEY,
    sreality_id INT         NOT NULL UNIQUE,
    name        VARCHAR(200),
    url         VARCHAR(500)
);

-- Dimension: date (calendar)
CREATE TABLE IF NOT EXISTS {s}.dim_date (
    date_id     INT PRIMARY KEY,
    full_date   DATE    NOT NULL,
    year        INT     NOT NULL,
    quarter     INT     NOT NULL,
    month       INT     NOT NULL,
    month_name  VARCHAR(20) NOT NULL,
    week        INT     NOT NULL,
    day_of_week INT     NOT NULL,
    is_weekend  BOOLEAN NOT NULL
);

-- Fact: property sale listings (SCD Type 2 snapshots)
CREATE TABLE IF NOT EXISTS {s}.fact_sale_snapshot (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    sreality_url             VARCHAR(200),
    property_type            VARCHAR(50),
    sub_category             VARCHAR(100),
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    cast_obce_id             INT REFERENCES {s}.dim_cast_obce(id),
    obec_id                  INT NOT NULL REFERENCES {s}.dim_obec(id),
    agency_id                INT REFERENCES {s}.dim_agency(id),
    date_id                  INT REFERENCES {s}.dim_date(date_id),
    price_asked_czk          BIGINT,
    price_asked_per_m2       NUMERIC(12,2),
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    ownership_label          VARCHAR(50),
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    is_new_building          BOOLEAN,
    is_furnished             BOOLEAN,
    has_balcony              BOOLEAN,
    has_terrace              BOOLEAN,
    has_loggia               BOOLEAN,
    has_cellar               BOOLEAN,
    has_elevator             BOOLEAN,
    has_garage               BOOLEAN,
    has_parking              BOOLEAN,
    has_pool                 BOOLEAN,
    is_barrier_free          BOOLEAN,
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- Fact: property rent listings (SCD Type 2 snapshots)
CREATE TABLE IF NOT EXISTS {s}.fact_rent_snapshot (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    sreality_url             VARCHAR(200),
    property_type            VARCHAR(50),
    sub_category             VARCHAR(100),
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    cast_obce_id             INT REFERENCES {s}.dim_cast_obce(id),
    obec_id                  INT NOT NULL REFERENCES {s}.dim_obec(id),
    agency_id                INT REFERENCES {s}.dim_agency(id),
    date_id                  INT REFERENCES {s}.dim_date(date_id),
    price_monthly_czk        BIGINT,
    price_monthly_per_m2     NUMERIC(10,2),
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    ownership_label          VARCHAR(50),
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    is_new_building          BOOLEAN,
    is_furnished             BOOLEAN,
    has_balcony              BOOLEAN,
    has_terrace              BOOLEAN,
    has_loggia               BOOLEAN,
    has_cellar               BOOLEAN,
    has_elevator             BOOLEAN,
    has_garage               BOOLEAN,
    has_parking              BOOLEAN,
    has_pool                 BOOLEAN,
    is_barrier_free          BOOLEAN,
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);

-- Fact: property auction listings (SCD Type 2 snapshots)
CREATE TABLE IF NOT EXISTS {s}.fact_auction_snapshot (
    id                       BIGSERIAL PRIMARY KEY,
    hash_id                  BIGINT       NOT NULL,
    sreality_url             VARCHAR(200),
    property_type            VARCHAR(50),
    sub_category             VARCHAR(100),
    valid_from               DATE         NOT NULL,
    valid_to                 DATE,
    cast_obce_id             INT REFERENCES {s}.dim_cast_obce(id),
    obec_id                  INT NOT NULL REFERENCES {s}.dim_obec(id),
    agency_id                INT REFERENCES {s}.dim_agency(id),
    date_id                  INT REFERENCES {s}.dim_date(date_id),
    price_starting_bid_czk   BIGINT,
    usable_area_m2           NUMERIC(10,2),
    floor_number             INT,
    total_floors             INT,
    gps_lat                  NUMERIC(10,6),
    gps_lon                  NUMERIC(10,6),
    ownership_label          VARCHAR(50),
    building_type_label      VARCHAR(50),
    building_condition_label VARCHAR(100),
    energy_rating_label      VARCHAR(100),
    is_new_building          BOOLEAN,
    is_furnished             BOOLEAN,
    has_balcony              BOOLEAN,
    has_terrace              BOOLEAN,
    has_loggia               BOOLEAN,
    has_cellar               BOOLEAN,
    has_elevator             BOOLEAN,
    has_garage               BOOLEAN,
    has_parking              BOOLEAN,
    has_pool                 BOOLEAN,
    is_barrier_free          BOOLEAN,
    is_active                BOOLEAN NOT NULL DEFAULT TRUE,
    first_seen_date          DATE,
    advert_images_count      INT,
    has_floor_plan           BOOLEAN,
    has_video                BOOLEAN
);
"""

# ── Helpers ───────────────────────────────────────────────────────────────────

def fetch_all(cur, sql: str, params=None) -> list[dict]:
    cur.execute(sql, params)
    cols = [d[0] for d in cur.description]
    return [dict(zip(cols, row)) for row in cur.fetchall()]


def ids(rows: list[dict], col: str) -> set:
    return {r[col] for r in rows if r.get(col) is not None}


def upsert_rows(cur, table: str, rows: list[dict], conflict_col: str) -> int:
    """Insert rows, skipping on conflict (idempotent)."""
    if not rows:
        return 0
    cols = list(rows[0].keys())
    placeholders = ", ".join(["%s"] * len(cols))
    col_list = ", ".join(cols)
    sql = (
        f"INSERT INTO {table} ({col_list}) VALUES ({placeholders}) "
        f"ON CONFLICT ({conflict_col}) DO NOTHING"
    )
    psycopg2.extras.execute_batch(
        cur, sql, [tuple(r[c] for c in cols) for r in rows], page_size=500
    )
    return len(rows)


def insert_rows(cur, table: str, rows: list[dict]) -> int:
    """Insert rows without conflict handling (for fact tables with BIGSERIAL pk)."""
    if not rows:
        return 0
    # Exclude the source 'id' column — destination generates its own BIGSERIAL
    cols = [c for c in rows[0].keys() if c != "id"]
    placeholders = ", ".join(["%s"] * len(cols))
    col_list = ", ".join(cols)
    sql = f"INSERT INTO {table} ({col_list}) VALUES ({placeholders})"
    psycopg2.extras.execute_batch(
        cur, sql, [tuple(r[c] for c in cols) for r in rows], page_size=500
    )
    return len(rows)


# ── ID remapping ──────────────────────────────────────────────────────────────
# Source surrogate keys differ from destination (destination generates its own
# SERIAL sequences). We remap FKs after inserting each dimension.

def build_id_map(dst_cur, table: str, natural_key: str, src_rows: list[dict]) -> dict:
    """
    Returns {src_id: dst_id} by looking up natural_key values in the destination.
    """
    if not src_rows:
        return {}
    nk_to_src = {r[natural_key]: r["id"] for r in src_rows}
    values = list(nk_to_src.keys())
    placeholders = ", ".join(["%s"] * len(values))
    dst_cur.execute(
        f"SELECT id, {natural_key} FROM {table} WHERE {natural_key} IN ({placeholders})",
        values,
    )
    result = {}
    for dst_id, nk in dst_cur.fetchall():
        src_id = nk_to_src.get(nk)
        if src_id is not None:
            result[src_id] = dst_id
    return result


# ── Extract ───────────────────────────────────────────────────────────────────

def extract(src_cur) -> dict[str, Any]:
    log.info("Extracting %d rows per fact table from source...", FACT_LIMIT)

    # ── Fact tables (1000 rows each, current snapshots preferred) ─────────────
    facts = {}
    for deal in ("sale", "rent", "auction"):
        rows = fetch_all(src_cur, f"""
            SELECT * FROM {SRC_SCHEMA}.fact_{deal}_snapshot
            ORDER BY valid_to NULLS FIRST, id
            LIMIT %s
        """, (FACT_LIMIT,))
        facts[deal] = rows
        log.info("  fact_%s_snapshot: %d rows", deal, len(rows))

    # Collect all FK IDs referenced by the sampled fact rows
    all_obec_ids      = ids(facts["sale"], "obec_id")      | ids(facts["rent"], "obec_id")      | ids(facts["auction"], "obec_id")
    all_cast_ids      = ids(facts["sale"], "cast_obce_id") | ids(facts["rent"], "cast_obce_id") | ids(facts["auction"], "cast_obce_id")
    all_agency_ids    = ids(facts["sale"], "agency_id")    | ids(facts["rent"], "agency_id")    | ids(facts["auction"], "agency_id")
    all_date_ids      = ids(facts["sale"], "date_id")      | ids(facts["rent"], "date_id")      | ids(facts["auction"], "date_id")

    # ── dim_obec (only referenced ones) ──────────────────────────────────────
    obec_rows = fetch_all(src_cur, f"""
        SELECT * FROM {SRC_SCHEMA}.dim_obec WHERE id = ANY(%s)
    """, (list(all_obec_ids),))
    log.info("  dim_obec: %d rows", len(obec_rows))

    # ── dim_cast_obce (only referenced ones) ─────────────────────────────────
    cast_rows = fetch_all(src_cur, f"""
        SELECT * FROM {SRC_SCHEMA}.dim_cast_obce WHERE id = ANY(%s)
    """, (list(all_cast_ids),)) if all_cast_ids else []
    log.info("  dim_cast_obce: %d rows", len(cast_rows))

    # ── dim_okres (referenced by the obec rows) ───────────────────────────────
    okres_ids = ids(obec_rows, "okres_id")
    okres_rows = fetch_all(src_cur, f"""
        SELECT * FROM {SRC_SCHEMA}.dim_okres WHERE id = ANY(%s)
    """, (list(okres_ids),)) if okres_ids else []
    log.info("  dim_okres: %d rows", len(okres_rows))

    # ── dim_kraj (referenced by the okres rows) ───────────────────────────────
    kraj_ids = ids(okres_rows, "kraj_id")
    kraj_rows = fetch_all(src_cur, f"""
        SELECT * FROM {SRC_SCHEMA}.dim_kraj WHERE id = ANY(%s)
    """, (list(kraj_ids),)) if kraj_ids else []
    log.info("  dim_kraj: %d rows", len(kraj_rows))

    # ── dim_agency ────────────────────────────────────────────────────────────
    agency_rows = fetch_all(src_cur, f"""
        SELECT * FROM {SRC_SCHEMA}.dim_agency WHERE id = ANY(%s)
    """, (list(all_agency_ids),)) if all_agency_ids else []
    log.info("  dim_agency: %d rows", len(agency_rows))

    # ── dim_date ──────────────────────────────────────────────────────────────
    date_rows = fetch_all(src_cur, f"""
        SELECT * FROM {SRC_SCHEMA}.dim_date WHERE date_id = ANY(%s)
    """, (list(all_date_ids),)) if all_date_ids else []
    log.info("  dim_date: %d rows", len(date_rows))

    return {
        "kraj":     kraj_rows,
        "okres":    okres_rows,
        "obec":     obec_rows,
        "cast":     cast_rows,
        "agency":   agency_rows,
        "date":     date_rows,
        "facts":    facts,
    }


# ── Transform ─────────────────────────────────────────────────────────────────
# Transformations applied (7 required):
#
#  T1. Joining datasets          — RUIAN geography + CSU demographics merged
#                                  into dim_obec (population, density, avg_age)
#  T2. Spatial join              — Estate GPS coords → cast_obce/obec polygon
#                                  matching using JTS point-in-polygon
#  T3. Filtering                 — Only estates with valid GPS, property_type,
#                                  hash_id pass RawEstate.isUsable()
#  T4. Handling missing values   — Nullable demographics, null areas, null prices
#                                  all preserved as NULL rather than dropped
#  T5. Derived attributes        — price_asked_per_m2 = price / usable_area_m2
#                                  sreality_url constructed from hash_id
#  T6. Adding surrogate keys     — SERIAL PKs on all dimension tables;
#                                  BIGSERIAL on fact tables
#  T7. Deduplication             — RUIAN duplicate entries removed by kod_obce;
#                                  agencies deduplicated by sreality_id
#  T8. SCD Type 2 versioning     — New fact row only when price or is_active
#                                  changes; valid_to closed on old row
#  T9. Date/number format conv.  — ISO date strings → LocalDate; Czech number
#                                  format (spaces/commas) → numeric
#
# In this script: FK remapping (T_remap) — source surrogate keys are replaced
# with destination surrogate keys to maintain referential integrity.

def remap_fks(data: dict) -> dict:
    """
    After inserting dimensions into the destination, their SERIAL PKs differ
    from the source. This function rebuilds all FK references in the fact rows
    using the natural keys of each dimension.
    Maps are built after each dimension insert and stored here.
    """
    # Maps are populated during the load phase; placeholders here.
    return data


# ── Load ──────────────────────────────────────────────────────────────────────

def create_schema(dst_cur):
    log.info("Creating tables in schema '%s'...", SCHEMA)
    # Schema is pre-created by the university server admin — do not CREATE SCHEMA
    dst_cur.execute(DDL.replace("{s}", SCHEMA))
    log.info("Schema ready.")


def load(dst_cur, data: dict):
    log.info("Loading dimensions and facts into '%s'...", SCHEMA)

    # ── dim_kraj ──────────────────────────────────────────────────────────────
    kraj_rows = [{"id": r["id"], "kod_kraje": r["kod_kraje"], "nazev_kraje": r["nazev_kraje"]}
                 for r in data["kraj"]]
    upsert_rows(dst_cur, f"{SCHEMA}.dim_kraj", kraj_rows, "kod_kraje")
    kraj_map = build_id_map(dst_cur, f"{SCHEMA}.dim_kraj", "kod_kraje", data["kraj"])
    log.info("  dim_kraj: %d rows", len(kraj_rows))

    # ── dim_okres ─────────────────────────────────────────────────────────────
    okres_rows = [
        {"kod_okresu": r["kod_okresu"], "nazev_okresu": r["nazev_okresu"],
         "kraj_id": kraj_map[r["kraj_id"]]}
        for r in data["okres"] if r["kraj_id"] in kraj_map
    ]
    upsert_rows(dst_cur, f"{SCHEMA}.dim_okres", okres_rows, "kod_okresu")
    okres_map = build_id_map(dst_cur, f"{SCHEMA}.dim_okres", "kod_okresu", data["okres"])
    log.info("  dim_okres: %d rows", len(okres_rows))

    # ── dim_obec ──────────────────────────────────────────────────────────────
    obec_rows = [
        {"kod_obce": r["kod_obce"], "nazev_obce": r["nazev_obce"],
         "okres_id": okres_map[r["okres_id"]],
         "population": r["population"], "population_density": r["population_density"],
         "area_km2": r["area_km2"], "avg_age": r["avg_age"],
         "unemployment_pct": r["unemployment_pct"]}
        for r in data["obec"] if r["okres_id"] in okres_map
    ]
    upsert_rows(dst_cur, f"{SCHEMA}.dim_obec", obec_rows, "kod_obce")
    obec_map = build_id_map(dst_cur, f"{SCHEMA}.dim_obec", "kod_obce", data["obec"])
    log.info("  dim_obec: %d rows", len(obec_rows))

    # ── dim_cast_obce ─────────────────────────────────────────────────────────
    cast_rows = [
        {"kod_cast_obce": r["kod_cast_obce"], "nazev_cast_obce": r["nazev_cast_obce"],
         "obec_id": obec_map[r["obec_id"]]}
        for r in data["cast"] if r["obec_id"] in obec_map
    ]
    upsert_rows(dst_cur, f"{SCHEMA}.dim_cast_obce", cast_rows, "kod_cast_obce")
    cast_map = build_id_map(dst_cur, f"{SCHEMA}.dim_cast_obce", "kod_cast_obce", data["cast"])
    log.info("  dim_cast_obce: %d rows", len(cast_rows))

    # ── dim_agency ────────────────────────────────────────────────────────────
    agency_rows = [
        {"sreality_id": r["sreality_id"], "name": r["name"], "url": r["url"]}
        for r in data["agency"]
    ]
    upsert_rows(dst_cur, f"{SCHEMA}.dim_agency", agency_rows, "sreality_id")
    agency_map = build_id_map(dst_cur, f"{SCHEMA}.dim_agency", "sreality_id", data["agency"])
    log.info("  dim_agency: %d rows", len(agency_rows))

    # ── dim_date ──────────────────────────────────────────────────────────────
    date_rows = [
        {"date_id": r["date_id"], "full_date": r["full_date"], "year": r["year"],
         "quarter": r["quarter"], "month": r["month"], "month_name": r["month_name"],
         "week": r["week"], "day_of_week": r["day_of_week"], "is_weekend": r["is_weekend"]}
        for r in data["date"]
    ]
    upsert_rows(dst_cur, f"{SCHEMA}.dim_date", date_rows, "date_id")
    date_map = {r["date_id"]: r["date_id"] for r in data["date"]}  # date_id is natural key
    log.info("  dim_date: %d rows", len(date_rows))

    # ── Fact tables ───────────────────────────────────────────────────────────
    # Price column differs per deal type — handle explicitly
    price_cols = {
        "sale":    ["price_asked_czk", "price_asked_per_m2"],
        "rent":    ["price_monthly_czk", "price_monthly_per_m2"],
        "auction": ["price_starting_bid_czk"],
    }
    common_cols = [
        "hash_id", "sreality_url", "property_type", "sub_category",
        "valid_from", "valid_to",
        "usable_area_m2", "floor_number", "total_floors",
        "gps_lat", "gps_lon",
        "ownership_label", "building_type_label", "building_condition_label",
        "energy_rating_label", "is_new_building", "is_furnished",
        "has_balcony", "has_terrace", "has_loggia", "has_cellar",
        "has_elevator", "has_garage", "has_parking", "has_pool",
        "is_barrier_free", "is_active", "first_seen_date",
        "advert_images_count", "has_floor_plan", "has_video",
    ]

    for deal, rows in data["facts"].items():
        fact_rows = []
        for r in rows:
            obec_dst = obec_map.get(r["obec_id"])
            if obec_dst is None:
                continue  # obec not in sample — skip (maintains referential integrity)
            row = {c: r[c] for c in common_cols}
            row["obec_id"]      = obec_dst
            row["cast_obce_id"] = cast_map.get(r["cast_obce_id"]) if r.get("cast_obce_id") else None
            row["agency_id"]    = agency_map.get(r["agency_id"]) if r.get("agency_id") else None
            row["date_id"]      = date_map.get(r["date_id"]) if r.get("date_id") else None
            for pc in price_cols[deal]:
                row[pc] = r.get(pc)
            fact_rows.append(row)

        inserted = insert_rows(dst_cur, f"{SCHEMA}.fact_{deal}_snapshot", fact_rows)
        log.info("  fact_%s_snapshot: %d rows inserted", deal, inserted)


# ── Main ──────────────────────────────────────────────────────────────────────

def main():
    # Validate required env vars
    missing = [v for v in ("DST_USER", "DST_PASSWORD", "SRC_PASSWORD") if not os.getenv(v)]
    if missing:
        log.error("Missing required environment variables: %s", ", ".join(missing))
        log.error("Set them in a .env file or as environment variables.")
        sys.exit(1)

    log.info("Connecting to source database...")
    with src_conn() as src, src.cursor() as src_cur:
        data = extract(src_cur)

    log.info("Connecting to destination database...")
    with dst_conn() as dst:
        dst.autocommit = False
        with dst.cursor() as dst_cur:
            try:
                create_schema(dst_cur)
                load(dst_cur, data)
                dst.commit()
                log.info("✓ ETL complete — all data committed to %s.%s", os.getenv("DST_DB", "ndbi046"), SCHEMA)
            except Exception as e:
                dst.rollback()
                log.error("ETL failed — transaction rolled back: %s", e)
                raise


if __name__ == "__main__":
    main()
